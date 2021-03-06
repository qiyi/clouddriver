/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.provider.view

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.clouddriver.google.GoogleCloudProvider
import com.netflix.spinnaker.clouddriver.google.cache.Keys
import com.netflix.spinnaker.clouddriver.google.model.GoogleInstance2
import com.netflix.spinnaker.clouddriver.google.model.GoogleLoadBalancer2
import com.netflix.spinnaker.clouddriver.google.model.GoogleSecurityGroup
import com.netflix.spinnaker.clouddriver.google.model.health.GoogleLoadBalancerHealth
import com.netflix.spinnaker.clouddriver.google.security.GoogleCredentials
import com.netflix.spinnaker.clouddriver.model.InstanceProvider
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

import static com.netflix.spinnaker.clouddriver.google.cache.Keys.Namespace.*

@ConditionalOnProperty(value = "google.providerImpl", havingValue = "new")
@Component
@Slf4j
class GoogleInstanceProvider implements InstanceProvider<GoogleInstance2.View> {

  @Autowired
  final Cache cacheView

  @Autowired
  GoogleCloudProvider googleCloudProvider

  @Autowired
  AccountCredentialsProvider accountCredentialsProvider

  @Autowired
  ObjectMapper objectMapper

  @Autowired
  GoogleSecurityGroupProvider securityGroupProvider

  final String platform = GoogleCloudProvider.GCE

  @Override
  GoogleInstance2.View getInstance(String account, String _ /*region*/, String id) {
    Set<GoogleSecurityGroup> securityGroups = securityGroupProvider.getAll(false)
    def key = Keys.getInstanceKey(googleCloudProvider, account, id)
    getInstanceCacheDatas([key])?.findResult { CacheData cacheData ->
      instanceFromCacheData(cacheData, securityGroups)?.view
    }
  }

  /**
   * Non-interface method for efficient building of GoogleInstance2 models during cluster or server group requests.
   */
  List<GoogleInstance2> getInstances(List<String> instanceKeys, Set<GoogleSecurityGroup> securityGroups) {
    getInstanceCacheDatas(instanceKeys)?.collect {
      instanceFromCacheData(it, securityGroups)
    }
  }

  Collection<CacheData> getInstanceCacheDatas(List<String> keys) {
    cacheView.getAll(INSTANCES.ns,
                     keys,
                     RelationshipCacheFilter.include(LOAD_BALANCERS.ns,
                                                     SERVER_GROUPS.ns))
  }

  @Override
  String getConsoleOutput(String account, String region, String id) {
    def accountCredentials = accountCredentialsProvider.getCredentials(account)

    if (!(accountCredentials?.credentials instanceof GoogleCredentials)) {
      throw new IllegalArgumentException("Invalid credentials: ${account}:${region}")
    }

    def credentials = accountCredentials.credentials
    def project = credentials.project
    def compute = credentials.compute
    def googleInstance = getInstance(account, region, id)

    if (googleInstance) {
      return compute.instances().getSerialPortOutput(project, googleInstance.zone, id).execute().contents
    }

    return null
  }

  GoogleInstance2 instanceFromCacheData(CacheData cacheData, Set<GoogleSecurityGroup> securityGroups) {
    GoogleInstance2 instance = objectMapper.convertValue(cacheData.attributes, GoogleInstance2)

    def loadBalancerKeys = cacheData.relationships[LOAD_BALANCERS.ns]
    cacheView.getAll(LOAD_BALANCERS.ns, loadBalancerKeys).each { CacheData loadBalancerCacheData ->
      GoogleLoadBalancer2 loadBalancer = objectMapper.convertValue(loadBalancerCacheData.attributes, GoogleLoadBalancer2)
      instance.loadBalancerHealths << loadBalancer.healths.findAll { GoogleLoadBalancerHealth health ->
        health.instanceName == instance.name
      }
    }

    def serverGroupKey = cacheData.relationships[SERVER_GROUPS.ns]?.first()
    if (serverGroupKey) {
      instance.serverGroup = Keys.parse(googleCloudProvider, serverGroupKey).serverGroup
    }

    instance.securityGroups = GoogleSecurityGroupProvider.getMatchingServerGroupNames(
        securityGroups,
        instance.tags.items as Set<String>,
        instance.networkName)
    
    instance
  }


}
