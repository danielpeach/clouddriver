package com.netflix.spinnaker.clouddriver.appengine.provider.view

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.clouddriver.appengine.AppEngineCloudProvider
import com.netflix.spinnaker.clouddriver.appengine.cache.Keys
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineCluster
import com.netflix.spinnaker.clouddriver.model.ClusterProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.APPLICATIONS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.CLUSTERS
//
//@Component
//class AppEngineClusterProvider implements ClusterProvider {
//  private final AppEngineCloudProvider appEngineCloudProvider
//  private final Cache cacheView
//  private final ObjectMapper objectMapper
//
//  @Autowired
//  AppEngineClusterProvider(AppEngineCloudProvider appEngineCloudProvider,
//                           Cache cacheView,
//                           ObjectMapper objectMapper) {
//    this.appEngineCloudProvider = appEngineCloudProvider
//    this.cacheView = cacheView
//    this.objectMapper = objectMapper
//  }
//
//  @Override
//  Set<AppEngineCluster> getClusters(String applicationName, String account) {
//    CacheData application = cacheView.get(APPLICATIONS.ns,
//                                          Keys.getApplicationKey(applicationName),
//                                          RelationshipCacheFilter.include(CLUSTERS.ns))
//    if (!application) {
//      return [] as Set
//    }
//
//    Collection<String> clusterKeys = application.relationships[CLUSTERS.ns].findAll { Keys.parse(it).account == account }
//  }
//
//  @Override
//  String getCloudProviderId() {
//    AppEngineCloudProvider.ID
//  }
//}
