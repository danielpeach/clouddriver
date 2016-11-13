/*
 * Copyright 2016 Google, Inc.
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

package com.netflix.spinnaker.clouddriver.appengine.provider.agent

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.api.client.googleapis.batch.BatchRequest
import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import com.google.api.services.appengine.v1.model.Instance
import com.google.api.services.appengine.v1.model.ListInstancesResponse
import com.google.api.services.appengine.v1.model.ListVersionsResponse
import com.google.api.services.appengine.v1.model.Service
import com.google.api.services.appengine.v1.model.Version
import com.netflix.frigga.Names
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.agent.AgentDataType
import com.netflix.spinnaker.cats.agent.CacheResult
import com.netflix.spinnaker.cats.agent.DefaultCacheResult
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.clouddriver.appengine.AppEngineCloudProvider
import com.netflix.spinnaker.clouddriver.appengine.cache.Keys
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineInstance
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineServerGroup
import com.netflix.spinnaker.clouddriver.appengine.provider.callbacks.AppEngineCallback
import com.netflix.spinnaker.clouddriver.appengine.security.AppEngineNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport
import com.netflix.spinnaker.clouddriver.kubernetes.provider.view.MutableCacheData
import groovy.util.logging.Slf4j

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE
import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.INFORMATIVE
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.APPLICATIONS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.CLUSTERS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.INSTANCES
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.LOAD_BALANCERS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.SERVER_GROUPS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.ON_DEMAND

@Slf4j
class AppEngineServerGroupCachingAgent extends AbstractAppEngineCachingAgent implements OnDemandAgent {
  final String category = "serverGroup"

  final OnDemandMetricsSupport metricsSupport

  static final Set<AgentDataType> types = Collections.unmodifiableSet([
    AUTHORITATIVE.forType(APPLICATIONS.ns),
    AUTHORITATIVE.forType(CLUSTERS.ns),
    AUTHORITATIVE.forType(SERVER_GROUPS.ns),
    AUTHORITATIVE.forType(INSTANCES.ns),
    AUTHORITATIVE.forType(LOAD_BALANCERS.ns),
  ] as Set)

  String agentType = "${accountName}/${credentials.region}/${AppEngineServerGroupCachingAgent.simpleName}"

  AppEngineServerGroupCachingAgent(String accountName,
                                   AppEngineNamedAccountCredentials credentials,
                                   ObjectMapper objectMapper,
                                   Registry registry) {
    super(accountName, objectMapper, credentials)
    this.metricsSupport = new OnDemandMetricsSupport(
      registry,
      this,
      "$AppEngineCloudProvider.ID:$OnDemandAgent.OnDemandType.ServerGroup")
  }

  @Override
  String getSimpleName() {
    AppEngineServerGroupCachingAgent.simpleName
  }

  @Override
  Collection<AgentDataType> getProvidedDataTypes() {
    types
  }

  @Override
  String getOnDemandAgentType() {
    "${getAgentType()}-OnDemand"
  }

  @Override
  boolean handles(OnDemandAgent.OnDemandType type, String cloudProvider) {
    type == OnDemandAgent.OnDemandType.ServerGroup && cloudProvider == AppEngineCloudProvider.ID
  }

  @Override
  OnDemandAgent.OnDemandResult handle(ProviderCache providerCache, Map<String, ? extends Object> data) {
    if (!data.containsKey("serverGroupName") || data.account != accountName) {
      return null
    }

    def serverGroupName = data.serverGroupName.toString()
    def loadBalancerName = data.loadBalancer.toString()
    Version serverGroup = loadServerGroup()
  }

  @Override
  CacheResult loadData(ProviderCache providerCache) {
    Long start = System.currentTimeMillis()
    Map<String, List<Version>> serverGroupsByLoadBalancerName = loadServerGroups()
    Map<String, List<Instance>> instancesByServerGroupName = loadInstances(serverGroupsByLoadBalancerName)
    List<CacheData> evictFromOnDemand = []
    List<CacheData> keepInOnDemand = []

    def serverGroupKeys = serverGroupsByLoadBalancerName.collectMany([], { String versionName, List<Version> versions ->
      versions.collect { version -> Keys.getServerGroupKey(accountName, version.getId(), credentials.region) }
    })

    providerCache.getAll(ON_DEMAND.ns, serverGroupKeys).each { CacheData onDemandEntry ->
      if (onDemandEntry.attributes.cacheTime < start && onDemandEntry.attributes.processedCount > 0) {
        evictFromOnDemand << onDemandEntry
      } else {
        keepInOnDemand << onDemandEntry
      }
    }

    def onDemandMap = keepInOnDemand.collectEntries { CacheData onDemandEntry -> [(onDemandEntry.id): onDemandEntry] }
    def result = buildCacheResult(serverGroupsByLoadBalancerName,
                                  instancesByServerGroupName,
                                  onDemandMap,
                                  evictFromOnDemand*.id,
                                  start)

    result.cacheResults[ON_DEMAND.ns].each { CacheData onDemandEntry ->
      onDemandEntry.attributes.processedTime = System.currentTimeMillis()
      onDemandEntry.attributes.processedCount = (onDemandEntry.attributes.processedCount ?: 0) + 1
    }

    result
  }

  CacheResult buildCacheResult(Map<String, List<Version>> serverGroupsByLoadBalancerName,
                               Map<String, List<Instance>> instancesByServerGroupName,
                               Map<String, CacheData> onDemandKeep,
                               List<String> onDemandEvict,
                               Long start) {
    log.info "Describing items in $agentType"

    Map<String, MutableCacheData> cachedApplications = MutableCacheData.mutableCacheMap()
    Map<String, MutableCacheData> cachedClusters = MutableCacheData.mutableCacheMap()
    Map<String, MutableCacheData> cachedServerGroups = MutableCacheData.mutableCacheMap()
    Map<String, MutableCacheData> cachedLoadBalancers = MutableCacheData.mutableCacheMap()
    Map<String, MutableCacheData> cachedInstances = MutableCacheData.mutableCacheMap()

    serverGroupsByLoadBalancerName.each { String loadBalancerName, List<Version> serverGroups ->
      serverGroups.each { Version serverGroup ->
        def onDemandData = onDemandKeep ?
          onDemandKeep[Keys.getServerGroupKey(accountName, serverGroup.getId(), credentials.region)] :
          null

        if (onDemandData && onDemandData.attributes.cacheTime >= start) {
          Map<String, List<CacheData>> cacheResults = objectMapper.readValue(onDemandData.attributes.cacheResults as String,
                                                                             new TypeReference<Map<String, List<MutableCacheData>>>() {})
          cache(cacheResults, APPLICATIONS.ns, cachedApplications)
          cache(cacheResults, CLUSTERS.ns, cachedClusters)
          cache(cacheResults, SERVER_GROUPS.ns, cachedServerGroups)
          cache(cacheResults, INSTANCES.ns, cachedInstances)
          cache(cacheResults, LOAD_BALANCERS.ns, cachedLoadBalancers)
        } else {
          def serverGroupName = serverGroup.getId()
          def names = Names.parseName(serverGroupName)
          def instances = instancesByServerGroupName[serverGroupName]
          def applicationName = names.app
          def clusterName = names.cluster

          def serverGroupKey = Keys.getServerGroupKey(accountName, serverGroupName, credentials.region)
          def applicationKey = Keys.getApplicationKey(applicationName)
          def clusterKey = Keys.getClusterKey(accountName, applicationName, clusterName)
          def loadBalancerKey = Keys.getLoadBalancerKey(accountName, applicationName, loadBalancerName)

          cachedApplications[applicationKey].with {
            attributes.name = applicationName
            relationships[CLUSTERS.ns].add(clusterKey)
            relationships[SERVER_GROUPS.ns].add(serverGroupKey)
            relationships[LOAD_BALANCERS.ns].add(loadBalancerKey)
          }

          cachedClusters[clusterKey].with {
            attributes.name = clusterName
            relationships[APPLICATIONS.ns].add(applicationKey)
            relationships[SERVER_GROUPS.ns].add(serverGroupKey)
            relationships[LOAD_BALANCERS.ns].add(loadBalancerKey)
          }

          def instanceKeys = instances.inject([], { ArrayList keys, instance ->
            def instanceName = instance.getId()
            def key = Keys.getInstanceKey(accountName, instanceName)
            cachedInstances[key].with {
              attributes.name = instanceName
              attributes.instance = new AppEngineInstance(instance)
              relationships[APPLICATIONS.ns].add(applicationKey)
              relationships[CLUSTERS.ns].add(clusterKey)
              relationships[SERVER_GROUPS.ns].add(serverGroupKey)
              relationships[LOAD_BALANCERS.ns].add(loadBalancerKey)
            }
            keys << key
            keys
          })

          cachedServerGroups[serverGroupKey].with {
            attributes.name = serverGroupName
            attributes.serverGroup = new AppEngineServerGroup(serverGroup,
                                                              accountName,
                                                              credentials.region,
                                                              loadBalancerName)
            relationships[APPLICATIONS.ns].add(applicationKey)
            relationships[CLUSTERS.ns].add(clusterKey)
            relationships[INSTANCES.ns].addAll(instanceKeys)
          }


          cachedLoadBalancers[loadBalancerKey].with {
            relationships[SERVER_GROUPS.ns].add(serverGroupKey)
            relationships[INSTANCES.ns].addAll(instanceKeys)
          }
        }
      }
    }

    log.info("Caching ${cachedApplications.size()} applications in ${agentType}")
    log.info("Caching ${cachedClusters.size()} clusters in ${agentType}")
    log.info("Caching ${cachedServerGroups.size()} server groups in ${agentType}")
    log.info("Caching ${cachedLoadBalancers.size()} load balancers in ${agentType}")
    log.info("Caching ${cachedInstances.size()} instances in ${agentType}")

    new DefaultCacheResult([
      (APPLICATIONS.ns): cachedApplications.values(),
      (CLUSTERS.ns): cachedClusters.values(),
      (SERVER_GROUPS.ns): cachedServerGroups.values(),
      (LOAD_BALANCERS.ns): cachedLoadBalancers.values(),
      (INSTANCES.ns): cachedInstances.values(),
      (ON_DEMAND.ns): onDemandKeep.values()
    ], [
      (ON_DEMAND.ns): onDemandEvict
    ])
  }

  static void cache(Map<String, List<CacheData>> cacheResults, String cacheNamespace, Map<String, CacheData> cacheDataById) {
    cacheResults[cacheNamespace].each {
      def existingCacheData = cacheDataById[it.id]
      if (existingCacheData) {
        existingCacheData.attributes.putAll(it.attributes)
        it.relationships.each { String relationshipName, Collection<String> relationships ->
          existingCacheData.relationships[relationshipName].addAll(relationships)
        }
      } else {
        cacheDataById[it.id] = it
      }
    }
  }

  Map<String, List<Version>> loadServerGroups() {
    def project = credentials.project
    def loadBalancers = credentials.appengine.apps().services().list(project).execute().getServices()
    BatchRequest batch = credentials.appengine.batch()
    Map<String, List<Version>> serverGroupsByLoadBalancerName = [:].withDefault { [] }

    loadBalancers.each { loadBalancer ->
      def loadBalancerName = loadBalancer.getId()

      def callback = new AppEngineCallback<ListVersionsResponse>()
        .success({ ListVersionsResponse versionsResponse, HttpHeaders responseHeaders ->
          serverGroupsByLoadBalancerName[loadBalancerName].addAll(versionsResponse.getVersions())
        })

      credentials
        .appengine.apps().services().versions().list(project, loadBalancerName).queue(batch, callback)
    }

    batch.execute()
    serverGroupsByLoadBalancerName
  }

  Version loadServerGroup(String serviceName, String versionName) {
    credentials
      .appengine
      .apps()
      .services()
      .versions()
      .get(credentials.project, serviceName, versionName)
      .execute()
  }

  Map<String, List<Instance>> loadInstances(Map<String, List<Version>> serverGroupsByLoadBalancerName) {
    BatchRequest batch = credentials.appengine.batch()
    Map<String, List<Instance>> instancesByServerGroupName = [:].withDefault { [] }

    serverGroupsByLoadBalancerName.each { String loadBalancerName, List<Version> serverGroups ->
      serverGroups.each { Version serverGroup ->
        def serverGroupName = serverGroup.getId()
        def callback = new AppEngineCallback<ListInstancesResponse>()
          .success({ ListInstancesResponse instancesResponse ->
            instancesByServerGroupName[serverGroupName].addAll(instancesResponse.getInstances())
          })

        credentials
          .appengine
          .apps()
          .services()
          .versions()
          .instances()
          .list(credentials.project, loadBalancerName, serverGroupName)
          .queue(batch, callback)
      }
    }

    batch.execute()
    instancesByServerGroupName
  }

  @Override
  Collection<Map> pendingOnDemandRequests(ProviderCache providerCache) {
    def keys = providerCache.getIdentifiers(ON_DEMAND.ns)
    keys = keys.findResults {
      def parse = Keys.parse(it)
      if (parse && parse.account == accountName) {
        return it
      } else {
        return null
      }
    }

    providerCache.getAll(ON_DEMAND.ns, keys).collect {
      [
        details  : Keys.parse(it.id),
        cacheTime: it.attributes.cacheTime,
        processedCount: it.attributes.processedCount,
        processedTime: it.attributes.processedTime
      ]
    }
  }
}
