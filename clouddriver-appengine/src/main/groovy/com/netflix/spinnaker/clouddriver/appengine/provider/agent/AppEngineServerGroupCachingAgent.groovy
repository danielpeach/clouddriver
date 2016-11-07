

package com.netflix.spinnaker.clouddriver.appengine.provider.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.api.services.appengine.v1.model.Service
import com.google.api.services.appengine.v1.model.Version
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.agent.AgentDataType
import com.netflix.spinnaker.cats.agent.CacheResult
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.clouddriver.appengine.AppEngineCloudProvider
import com.netflix.spinnaker.clouddriver.appengine.cache.Keys
import com.netflix.spinnaker.clouddriver.appengine.deploy.AppEngineUtils
import com.netflix.spinnaker.clouddriver.appengine.model.AppEngineServerGroup
import com.netflix.spinnaker.clouddriver.appengine.security.AppEngineCredentials
import com.netflix.spinnaker.clouddriver.appengine.security.AppEngineNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport
import groovy.util.logging.Slf4j

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE
import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.INFORMATIVE
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.APPLICATIONS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.CLUSTERS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.INSTANCES
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.SERVER_GROUPS
import static com.netflix.spinnaker.clouddriver.appengine.cache.Keys.Namespace.ON_DEMAND

@Slf4j
class AppEngineServerGroupCachingAgent extends AppEngineCachingAgent implements OnDemandAgent {
  final String category = "serverGroup"

  final OnDemandMetricsSupport metricsSupport

  static final Set<AgentDataType> types = Collections.unmodifiableSet([
    AUTHORITATIVE.forType(APPLICATIONS.ns),
    AUTHORITATIVE.forType(CLUSTERS.ns),
    AUTHORITATIVE.forType(SERVER_GROUPS.ns),
    INFORMATIVE.forType(INSTANCES.ns),
  ] as Set)

  AppEngineServerGroupCachingAgent(String accountName,
                                   AppEngineNamedAccountCredentials credentials,
                                   ObjectMapper objectMapper,
                                   Integer agentIndex,
                                   Integer agentCount,
                                   Registry registry) {
    super(accountName, objectMapper, credentials, agentIndex, agentCount)
    this.metricsSupport = new OnDemandMetricsSupport(registry,
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
    if (!data.containsKey("serverGroupName")) {
      return null
    }

    def serverGroupName = data.serverGroupName
    AppEngineServerGroup serverGroup = metricsSupport.readData {

    }
  }

  @Override
  CacheResult loadData(ProviderCache providerCache) {
    Long start = System.currentTimeMillis()

    List<Version> versionList = loadServerGroups()

    List<CacheData> evictFromOnDemand = []
    List<CacheData> keepInOnDemand = []

    def serverGroupKeys =

    providerCache.getAll(ON_DEMAND.ns,
      )
  }

  List<Version> loadServerGroups() {
    def project = credentials.project
    credentials.appengine.apps().services().list(project).execute().getServices().collect { Service service ->
      credentials.appengine.apps().services().versions().list(project, service.getId()).execute().getVersions()
    }.flatten() as List<Version>
  }
}
