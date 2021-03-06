/*
 * Copyright 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.model

import com.google.api.client.googleapis.batch.BatchRequest
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.services.compute.model.HealthStatus
import com.google.api.services.compute.model.InstanceGroupManager
import com.google.api.services.compute.model.InstanceGroupManagerList
import com.netflix.frigga.Names
import com.netflix.spinnaker.clouddriver.google.config.GoogleConfigurationProperties
import com.netflix.spinnaker.clouddriver.google.model.callbacks.AutoscalerAggregatedListCallback
import com.netflix.spinnaker.clouddriver.google.model.callbacks.ImagesCallback
import com.netflix.spinnaker.clouddriver.google.model.callbacks.InstanceAggregatedListCallback
import com.netflix.spinnaker.clouddriver.google.model.callbacks.MIGSCallback
import com.netflix.spinnaker.clouddriver.google.model.callbacks.NetworkLoadBalancersCallback
import com.netflix.spinnaker.clouddriver.google.model.callbacks.RegionsCallback
import com.netflix.spinnaker.clouddriver.google.model.callbacks.Utils
import com.netflix.spinnaker.clouddriver.google.provider.view.GoogleSecurityGroupProvider
import com.netflix.spinnaker.clouddriver.google.security.GoogleCredentials
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider
import org.apache.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value

import javax.annotation.PostConstruct
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

class GoogleResourceRetriever {
  protected final Logger log = Logger.getLogger(GoogleResourceRetriever.class)

  @Autowired
  AccountCredentialsProvider accountCredentialsProvider

  @Autowired
  GoogleConfigurationProperties googleConfigurationProperties

  @Autowired
  GoogleSecurityGroupProvider googleSecurityGroupProvider

  @Autowired
  String googleApplicationName

  @Value('${default.build.host:http://builds.netflix.com/}')
  String defaultBuildHost

  protected Lock cacheLock = new ReentrantLock()

  // The value of these fields are always assigned atomically and the collections are never modified after assignment.
  private appMap = new HashMap<String, GoogleApplication>()
  private standaloneInstanceMap = new HashMap<String, List<GoogleInstance>>()
  private imageMap = new HashMap<String, List<Map>>()
  // accountName -> region -> GoogleLoadBalancer
  private networkLoadBalancerMap = new HashMap<String, Map<String, List<GoogleLoadBalancer>>>()

  @PostConstruct
  void init() {
    log.info "Initializing GoogleResourceRetriever thread..."

    // Load all resources initially in 10 seconds, and then every googleConfigurationProperties.pollingIntervalSeconds seconds thereafter.
    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate({
      try {
        load()
      } catch (Throwable t) {
        t.printStackTrace()
      }
    }, 10, googleConfigurationProperties.pollingIntervalSeconds, TimeUnit.SECONDS)
  }

  // TODO(duftler): Handle paginated results.
  private void load() {
    def googleAccountSet = accountCredentialsProvider.all.findAll {
      it instanceof GoogleNamedAccountCredentials
    } as Set<GoogleNamedAccountCredentials>

    if (!googleAccountSet) {
      if (appMap) {
        log.info "Flushing application map..."

        appMap = new HashMap<String, GoogleApplication>()
      }

      return
    }

    log.info "Loading GCE resources..."

    cacheLock.lock()

    log.info "Acquired cacheLock for reloading cache."

    try {
      def tempAppMap = new HashMap<String, GoogleApplication>()
      def tempStandaloneInstanceMap = new HashMap<String, List<GoogleInstance>>()
      def tempImageMap = new HashMap<String, List<Map>>()
      def tempNetworkLoadBalancerMap = new HashMap<String, Map<String, List<GoogleLoadBalancer>>>()

      googleAccountSet.each { googleAccount ->
        def accountName = googleAccount.name
        def credentials = googleAccount.credentials
        def project = credentials.project
        def compute = credentials.compute

        BatchRequest regionsBatch = buildBatchRequest(compute, googleApplicationName)
        BatchRequest migsBatch = buildBatchRequest(compute, googleApplicationName)
        BatchRequest instanceGroupsBatch = buildBatchRequest(compute, googleApplicationName)
        BatchRequest instancesBatch = buildBatchRequest(compute, googleApplicationName)
        Map<String, GoogleServerGroup> instanceNameToGoogleServerGroupMap = new HashMap<String, GoogleServerGroup>()

        def regions = compute.regions().list(project).execute().getItems()
        def googleSecurityGroups = googleSecurityGroupProvider.getAllByAccount(false, accountName)
        def regionsCallback = new RegionsCallback(tempAppMap,
                                                  accountName,
                                                  project,
                                                  compute,
                                                  googleSecurityGroups,
                                                  tempImageMap,
                                                  defaultBuildHost,
                                                  instanceNameToGoogleServerGroupMap,
                                                  migsBatch,
                                                  instanceGroupsBatch)

        regions.each { region ->
          compute.regions().get(project, region.getName()).queue(regionsBatch, regionsCallback)
        }

        // Retrieve all configured autoscaling policies.
        def autoscalerAggregatedListCallback = new AutoscalerAggregatedListCallback(tempAppMap, accountName)

        compute.autoscalers().aggregatedList(project).queue(instanceGroupsBatch, autoscalerAggregatedListCallback)

        // Image lists are keyed by account in imageMap.
        if (!tempImageMap[accountName]) {
          tempImageMap[accountName] = new ArrayList<Map>()
        }

        // Retrieve all available images for this project.
        compute.images().list(project).queue(regionsBatch, new ImagesCallback(tempImageMap[accountName], false))

        // Retrieve all available images from each configured imageProject for this account.
        googleAccount.imageProjects.each { imageProject ->
          compute.images().list(imageProject).queue(regionsBatch, new ImagesCallback(tempImageMap[accountName], false))
        }

        // Retrieve pruned list of available images for known public image projects.
        def imagesCallback = new ImagesCallback(tempImageMap[accountName], true)

        googleConfigurationProperties.baseImageProjects.each { baseImageProject ->
          compute.images().list(baseImageProject).queue(regionsBatch, imagesCallback)
        }

        // Network load balancer maps are keyed by account in networkLoadBalancerMap.
        if (!tempNetworkLoadBalancerMap[accountName]) {
          tempNetworkLoadBalancerMap[accountName] = new HashMap<String, List<GoogleLoadBalancer>>()
        }

        def instanceNameToLoadBalancerHealthStatusMap = new HashMap<String, Map<String, List<HealthStatus>>>()

        // Retrieve all available network load balancers for this project.
        def networkLoadBalancersCallback = new NetworkLoadBalancersCallback(tempNetworkLoadBalancerMap[accountName],
                                                                            instanceNameToLoadBalancerHealthStatusMap,
                                                                            accountName,
                                                                            project,
                                                                            compute,
                                                                            migsBatch,
                                                                            instanceGroupsBatch)

        compute.forwardingRules().aggregatedList(project).queue(regionsBatch, networkLoadBalancersCallback)

        executeIfRequestsAreQueued(regionsBatch)
        executeIfRequestsAreQueued(migsBatch)
        executeIfRequestsAreQueued(instanceGroupsBatch)

        // Standalone instance maps are keyed by account in standaloneInstanceMap.
        if (!tempStandaloneInstanceMap[accountName]) {
          tempStandaloneInstanceMap[accountName] = new ArrayList<GoogleInstance>()
        }

        def instanceAggregatedListCallback =
          new InstanceAggregatedListCallback(googleSecurityGroups,
                                             instanceNameToGoogleServerGroupMap,
                                             tempStandaloneInstanceMap[accountName],
                                             instanceNameToLoadBalancerHealthStatusMap)

        compute.instances().aggregatedList(project).queue(instancesBatch, instanceAggregatedListCallback)
        executeIfRequestsAreQueued(instancesBatch)
      }

      populateLoadBalancerServerGroups(tempAppMap, tempNetworkLoadBalancerMap)

      appMap = tempAppMap
      standaloneInstanceMap = tempStandaloneInstanceMap
      imageMap = tempImageMap
      networkLoadBalancerMap = tempNetworkLoadBalancerMap
    } finally {
      cacheLock.unlock()
    }

    log.info "Finished loading GCE resources."
  }

  public static void populateLoadBalancerServerGroups(HashMap<String, GoogleApplication> tempAppMap,
                                                      Map<String, Map<String, List<GoogleLoadBalancer>>> tempNetworkLoadBalancerMap) {
    // Build a reverse index from load balancers to server groups.
    // First level is keyed by accountName and second level is keyed by load balancer name.
    // Value at end of path is a list of google server groups.
    def loadBalancerNameToServerGroupsMap = [:].withDefault { [:].withDefault { [] } }

    tempAppMap.each { applicationName, googleApplication ->
      googleApplication.clusters.each { accountName, clusterMap ->
        clusterMap.each { clusterName, googleCluster ->
          googleCluster.serverGroups.each { googleServerGroup ->
            def loadBalancerNames = googleServerGroup.getLoadBalancers()

            loadBalancerNames.each { loadBalancerName ->
              loadBalancerNameToServerGroupsMap[accountName][loadBalancerName] << googleServerGroup
            }
          }
        }
      }
    }

    // Populate each load balancer with its summary server group and instance data.
    loadBalancerNameToServerGroupsMap.each { accountName, lbNameToServerGroupsMap ->
      lbNameToServerGroupsMap.each { loadBalancerName, serverGroupList ->
        serverGroupList.each { serverGroup ->
          def loadBalancer = tempNetworkLoadBalancerMap[accountName]?.get(serverGroup.getRegion())?.find {
            it.name == loadBalancerName
          }

          if (loadBalancer) {
            def instances = [] as Set
            def detachedInstances = [] as Set

            serverGroup.instances.each { instance ->
              def instanceNames = loadBalancer instanceof Map ? loadBalancer["instanceNames"] : loadBalancer.anyProperty()["instanceNames"]

              // Only include the instances from the server group that are also registered with the load balancer.
              if (instanceNames?.contains(instance.name)) {
                // Only include the health returned by this load balancer.
                def loadBalancerHealth = instance.health.find {
                  it.type == "LoadBalancer"
                }?.loadBalancers?.find {
                  it.loadBalancerName == loadBalancerName
                }

                def health = loadBalancerHealth ?
                             [
                               state      : loadBalancerHealth.state,
                               description: loadBalancerHealth.description
                             ] :
                             [
                               state      : "Unknown",
                               description: "Unable to determine load balancer health."
                             ]

                instances << [
                  id    : instance.name,
                  zone  : Utils.getLocalName(instance.getZone()),
                  health: health
                ]
              } else {
                detachedInstances << instance.name
              }
            }

            def serverGroupSummary = [
              name      :        serverGroup.name,
              isDisabled:        serverGroup.isDisabled(),
              instances :        instances,
              detachedInstances: detachedInstances
            ]

            loadBalancer.serverGroups << serverGroupSummary
          }
        }
      }
    }
  }

  static executeIfRequestsAreQueued(BatchRequest batch) {
    if (batch.size()) {
      batch.execute()
    }
  }

  static BatchRequest buildBatchRequest(def compute, def googleApplicationName) {
    return compute.batch(
      new HttpRequestInitializer() {
        @Override
        void initialize(HttpRequest request) throws IOException {
          request.headers.setUserAgent(googleApplicationName);
        }
      }
    )
  }

  void handleCacheUpdate(Map<String, ? extends Object> data) {
    log.info "Refreshing cache for server group $data.serverGroupName in account $data.account..."

    if (cacheLock.tryLock()) {
      log.info "Acquired cacheLock for updating cache."

      try {
        def accountCredentials = accountCredentialsProvider.getCredentials(data.account)

        if (accountCredentials?.credentials instanceof GoogleCredentials) {
          GoogleCredentials credentials = accountCredentials.credentials

          def project = credentials.project
          def compute = credentials.compute

          BatchRequest instanceGroupsBatch = buildBatchRequest(compute, googleApplicationName)
          BatchRequest instancesBatch = buildBatchRequest(compute, googleApplicationName)

          def tempAppMap = new HashMap<String, GoogleApplication>()
          def instanceNameToGoogleServerGroupMap = new HashMap<String, GoogleServerGroup>()
          def googleSecurityGroups = googleSecurityGroupProvider.getAllByAccount(false, data.account)
          def migsCallback = new MIGSCallback(tempAppMap,
                                              data.region,
                                              data.zone,
                                              data.account,
                                              project,
                                              compute,
                                              googleSecurityGroups,
                                              imageMap,
                                              defaultBuildHost,
                                              instanceNameToGoogleServerGroupMap,
                                              instanceGroupsBatch)

          // Handle 404 here (especially when this is called after destroying a managed instance group).
          InstanceGroupManager instanceGroupManager = null

          try {
            instanceGroupManager = compute.instanceGroupManagers().get(project, data.zone, data.serverGroupName).execute()
          } catch (GoogleJsonResponseException e) {
            // Nothing to do here except leave instanceGroupManager null. 404 can land us here.
          }

          // If the InstanceGroupManager was returned, query all of its details and instances.
          if (instanceGroupManager) {
            InstanceGroupManagerList instanceGroupManagerList = new InstanceGroupManagerList(items: [instanceGroupManager])

            migsCallback.onSuccess(instanceGroupManagerList, null)

            // Retrieve all configured autoscaling policies.
            def autoscalerAggregatedListCallback = new AutoscalerAggregatedListCallback(tempAppMap, data.account)

            compute.autoscalers().aggregatedList(project).queue(instanceGroupsBatch, autoscalerAggregatedListCallback)

            executeIfRequestsAreQueued(instanceGroupsBatch)

            // TODO(duftler): Would be more efficient to retrieve just the instances for the server group's zone.
            def instanceAggregatedListCallback =
              new InstanceAggregatedListCallback(googleSecurityGroups, instanceNameToGoogleServerGroupMap, null, null)

            compute.instances().aggregatedList(project).queue(instancesBatch, instanceAggregatedListCallback)
            executeIfRequestsAreQueued(instancesBatch)
          }

          // Apply the naming-convention to derive application and cluster names from server group name.
          Names names = Names.parseName(data.serverGroupName)
          def appName = names.app.toLowerCase()
          def clusterName = names.cluster

          // Attempt to retrieve the requested server group from the containing cluster.
          GoogleCluster cluster = Utils.retrieveOrCreatePathToCluster(tempAppMap, data.account, appName, clusterName)
          GoogleServerGroup googleServerGroup = cluster.serverGroups.find { googleServerGroup ->
            googleServerGroup.name == data.serverGroupName
          }

          if (googleServerGroup) {
            // Migrate any existing load balancer health states to the newly-updated server group.
            migrateInstanceLoadBalancerHealthStates(googleServerGroup, data.account, appName, clusterName)
          }

          // Now update the cache with the new information.
          createUpdatedApplicationMap(data.account, data.serverGroupName, googleServerGroup)

          log.info "Finished refreshing cache for server group $data.serverGroupName in account $data.account."
        }
      } finally {
        cacheLock.unlock()
      }
    } else {
      log.info "Unable to acquire cacheLock for updating cache."
    }
  }

  /*
   * Migrate any existing load balancer health states to the new server group. All parameters are required.
   */
  void migrateInstanceLoadBalancerHealthStates(GoogleServerGroup newGoogleServerGroup,
                                               String accountName,
                                               String appName,
                                               String clusterName) {
    // Walk the path from the application down to the instances.
    GoogleCluster googleCluster = appMap.get(appName)?.clusters?.get(accountName)?.get(clusterName) ?: null

    if (!googleCluster) {
      return
    }

    GoogleServerGroup origGoogleServerGroup = googleCluster.getServerGroups().find { googleServerGroup ->
      googleServerGroup.name == newGoogleServerGroup.name
    }

    if (!origGoogleServerGroup) {
      return
    }

    // Iterate over each of the original server group's instances.
    origGoogleServerGroup.instances.each { origGoogleInstance ->
      // See if the instance is still present in the new server group.
      GoogleInstance newGoogleInstance = newGoogleServerGroup.instances.find { newGoogleInstance ->
        newGoogleInstance.name == origGoogleInstance.name
      }

      if (newGoogleInstance) {
        // Look for load balancer health states.
        List<Map> origLoadBalancerHealthStates = origGoogleInstance.getProperty("health")?.findAll { origHealthStateMap ->
          origHealthStateMap.type == "LoadBalancer"
        }

        // Migrate any existing load balancer health states.
        if (origLoadBalancerHealthStates) {
          // There shouldn't be any way to have an instance with a null "health" property, but playing it safe here anyway.
          List<Map> newHealthStates = newGoogleInstance.getProperty("health") ?: []

          // Add any found original load balancer health states. It's ok to do a shallow copy here since the original server
          // group is about to be replaced in the appMap with the new server group.
          newHealthStates += origLoadBalancerHealthStates

          newGoogleInstance.setProperty("health", newHealthStates)
        }
      }
    }
  }

  /*
   * Clone the existing map and either update or remove the specified server group. The parameters accountName and
   * serverGroupName are required, but newGoogleServerGroup can be null.
   */
  void createUpdatedApplicationMap(String accountName, String serverGroupName, GoogleServerGroup newGoogleServerGroup) {
    // Clone the map prior to mutating it.
    def tempMap = Utils.deepCopyApplicationMap(appMap)

    // Apply the naming-convention to derive application and cluster names from server group name.
    def names = Names.parseName(serverGroupName)
    def appName = names.app.toLowerCase()
    def clusterName = names.cluster

    // Retrieve the containing cluster in the newly-cloned map.
    GoogleCluster cluster = Utils.retrieveOrCreatePathToCluster(tempMap, accountName, appName, clusterName)

    // Find any matching server groups in the cluster.
    def oldGoogleServerGroupsToRemove = cluster.serverGroups.findAll { existingGoogleServerGroup ->
      existingGoogleServerGroup.name == serverGroupName
    }

    // Remove any matches.
    cluster.serverGroups -= oldGoogleServerGroupsToRemove

    // If a newly-retrieved server group exists, add it to the containing cluster in the newly-cloned map.
    if (newGoogleServerGroup) {
      cluster.serverGroups << newGoogleServerGroup
    }

    appMap = tempMap
  }

  Map<String, GoogleApplication> getApplicationsMap() {
    return appMap
  }

  Map<String, List<GoogleInstance>> getStandaloneInstanceMap() {
    return standaloneInstanceMap
  }

  Map<String, List<Map>> getImageMap() {
    return imageMap
  }

  Map<String, Map<String, List<GoogleLoadBalancer>>> getNetworkLoadBalancerMap() {
    return networkLoadBalancerMap
  }
}
