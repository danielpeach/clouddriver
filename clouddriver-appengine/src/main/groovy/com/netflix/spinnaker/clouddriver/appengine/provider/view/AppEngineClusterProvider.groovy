package com.netflix.spinnaker.clouddriver.appengine.provider.view

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.frigga.Names
import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.clouddriver.appengine.AppEngineCloudProvider
import com.netflix.spinnaker.clouddriver.appengine.cache.Keys
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineApplication
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineCluster
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineInstance
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineLoadBalancer
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineServerGroup
import com.netflix.spinnaker.clouddriver.model.ClusterProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.APPLICATIONS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.CLUSTERS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.INSTANCES
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.LOAD_BALANCERS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.SERVER_GROUPS

@Component
class AppEngineClusterProvider implements ClusterProvider<AppEngineCluster> {
  private final AppEngineCloudProvider appEngineCloudProvider
  private final Cache cacheView
  private final ObjectMapper objectMapper
  private final AppEngineApplicationProvider appEngineApplicationProvider

  @Autowired
  AppEngineClusterProvider(AppEngineCloudProvider appEngineCloudProvider,
                           Cache cacheView,
                           ObjectMapper objectMapper,
                           AppEngineApplicationProvider appEngineApplicationProvider) {
    this.appEngineCloudProvider = appEngineCloudProvider
    this.cacheView = cacheView
    this.objectMapper = objectMapper
    this.appEngineApplicationProvider = appEngineApplicationProvider
  }

  @Override
  Set<AppEngineCluster> getClusters(String applicationName, String account) {
    CacheData application = cacheView.get(APPLICATIONS.ns,
                                          Keys.getApplicationKey(applicationName),
                                          RelationshipCacheFilter.include(CLUSTERS.ns))
    if (!application) {
      return [] as Set
    }

    Collection<String> clusterKeys = application.relationships[CLUSTERS.ns].findAll { Keys.parse(it).account == account }
    Collection<CacheData> clusterData = cacheView.getAll(CLUSTERS.ns, clusterKeys)
    translateClusters(clusterData, true)
  }

  @Override
  Map<String, Set<AppEngineCluster>> getClusters() {
    Collection<CacheData> clusterData = cacheView.getAll(CLUSTERS.ns)
    translateClusters(clusterData, true).groupBy { it.accountName } as Map<String, Set<AppEngineCluster>>
  }

  @Override
  AppEngineCluster getCluster(String applicationName, String account, String clusterName) {
    List<CacheData> clusterData =
      [cacheView.get(CLUSTERS.ns, Keys.getClusterKey(account, applicationName, clusterName))] - null

    return clusters ?
      translateClusters(clusterData, true)
        .inject(new AppEngineCluster(), { AppEngineCluster acc, AppEngineCluster val ->
          acc.name = acc.name ?: val.name
          acc.accountName = acc.accountName ?: val.accountName
          acc.loadBalancers.addAll(val.loadBalancers)
          acc.serverGroups.addAll(val.serverGroups)
          return acc
        }) : null
  }

  @Override
  AppEngineServerGroup getServerGroup(String account, String region, String serverGroupName) {

  }

  @Override
  Map<String, Set<AppEngineCluster>> getClusterSummaries(String applicationName) {
    translateClusters(getClusterData(applicationName), false)?.groupBy { it.accountName } as Map<String, Set<AppEngineCluster>>
  }

  @Override
  Map<String, Set<AppEngineCluster>> getClusterDetails(String applicationName) {
    translateClusters(getClusterData(applicationName), true)?.groupBy { it.accountName } as Map<String, Set<AppEngineCluster>>
  }

  Set<CacheData> getClusterData(String applicationName) {
    AppEngineApplication application = appEngineApplicationProvider.getApplication(applicationName)

    def clusterKeys = []
    application?.clusterNames?.each { String accountName, Set<String> clusterNames ->
      clusterKeys.addAll(clusterNames.collect { clusterName ->
        Keys.getClusterKey(accountName, applicationName, clusterName)
      })
    }

    cacheView.getAll(CLUSTERS.ns,
                     clusterKeys,
                     RelationshipCacheFilter.include(SERVER_GROUPS.ns, LOAD_BALANCERS.ns))
  }

  @Override
  String getCloudProviderId() {
    AppEngineCloudProvider.ID
  }

  Collection<AppEngineCluster> translateClusters(Collection<CacheData> clusterData, boolean includeDetails) {
    Map<String, AppEngineLoadBalancer> loadBalancers = includeDetails ?
      translateLoadBalancers(AppEngineProviderUtils.resolveRelationshipDataForCollection(
        cacheView,
        clusterData,
        LOAD_BALANCERS.ns)) : null

    Map<String, Set<AppEngineServerGroup>> serverGroups = includeDetails ?
      translateServerGroups(AppEngineProviderUtils.resolveRelationshipDataForCollection(
        cacheView,
        clusterData,
        SERVER_GROUPS.ns,
        RelationshipCacheFilter.include(INSTANCES.ns, LOAD_BALANCERS.ns))) : null

    return clusterData.collect { CacheData clusterDataEntry ->
      Map<String, String> clusterKey = Keys.parse(clusterDataEntry.id)

      def cluster = new AppEngineCluster(accountName: clusterKey.account, name: clusterKey.name)

      if (includeDetails) {
        cluster.loadBalancers = clusterDataEntry.relationships[LOAD_BALANCERS.ns]?.findResults { id ->
          loadBalancers.get(id)
        }
        cluster.serverGroups = serverGroups[cluster.name]?.findAll { it.account == cluster.accountName } ?: []
      } else {
        cluster.loadBalancers = clusterDataEntry.relationships[LOAD_BALANCERS.ns].collect { loadBalancerKey ->
          def parts = Keys.parse(loadBalancerKey)
          new AppEngineLoadBalancer(name: parts.name, account: parts.account)
        }
        cluster.serverGroups = clusterDataEntry.relationships[SERVER_GROUPS.ns].collect { serverGroupKey ->
          def parts = Keys.parse(serverGroupKey)
          new AppEngineServerGroup(name: parts.name, account: parts.account)
        }
      }
      cluster
    }
  }

  Map<String, Set<AppEngineServerGroup>> translateServerGroups(Collection<CacheData> serverGroupData) {
    Map<String, Set<AppEngineInstance>> instances = AppEngineProviderUtils
      .preserveRelationshipDataForCollection(cacheView, serverGroupData, INSTANCES.ns, RelationshipCacheFilter.none())
      .collectEntries { String key, Collection<CacheData> cacheData ->
        [(key): cacheData.collect { AppEngineProviderUtils.instanceFromCacheData(objectMapper, it) }]
      }

    return serverGroupData.inject([:].withDefault { [] }, { Map<String, Set<AppEngineServerGroup>> acc, CacheData cacheData ->
      def serverGroup = AppEngineProviderUtils.serverGroupFromCacheData(objectMapper, cacheData, instances[cacheData.id] ?: ([] as Set))
      acc[Names.parseName(serverGroup.name).cluster].add(serverGroup)
      acc
    })
  }

  static Map<String, AppEngineLoadBalancer> translateLoadBalancers(Collection<CacheData> loadBalancerData) {
    loadBalancerData.collectEntries { loadBalancerEntry ->
      def parts = Keys.parse(loadBalancerEntry.id)
      [(loadBalancerEntry.id): new AppEngineLoadBalancer(name: parts.name, account: parts.account)]
    }
  }
}
