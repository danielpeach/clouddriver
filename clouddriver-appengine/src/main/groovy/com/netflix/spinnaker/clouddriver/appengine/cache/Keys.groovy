package com.netflix.spinnaker.clouddriver.appengine.cache

import com.netflix.frigga.Names
import com.netflix.spinnaker.clouddriver.appengine.AppEngineCloudProvider
import groovy.util.logging.Slf4j

@Slf4j
class Keys {
  static enum Namespace {
    APPLICATIONS,
    CLUSTERS,
    SERVER_GROUPS,
    INSTANCES,
    LOAD_BALANCERS,

    ON_DEMAND

    static String provider = AppEngineCloudProvider.ID

    final String ns

    private Namespace() {
      def parts = name().split('_')

      ns = parts.tail().inject(new StringBuilder(parts.head().toLowerCase())) { val, next -> val.append(next.charAt(0)).append(next.substring(1).toLowerCase()) }
    }

    String toString() {
      ns
    }
  }

  static Map<String, String> parse(String key) {
    def parts = key.split(':')

    if (parts.length < 2 || parts[0] != AppEngineCloudProvider.ID) {
      return null
    }

    def result = [provider: parts[0], type: parts[1]]

    switch (result.type) {
      case Namespace.APPLICATIONS.ns:
        result << [application: parts[2]]
        break
      case Namespace.CLUSTERS.ns:
        def names = Names.parseName(parts[4])
        result << [
          account: parts[2],
          application: parts[3],
          name: parts[4],
          cluster: parts[4],
          stack: names.stack,
          detail: names.detail,
        ]
        break
      default:
        return null
      break
    }

    result
  }

  static String getApplicationKey(String application) {
    "$AppEngineCloudProvider.ID:${Namespace.APPLICATIONS}:${application}"
  }
}
