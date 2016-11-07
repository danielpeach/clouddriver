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

package com.netflix.spinnaker.clouddriver.appengine.provider.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.agent.Agent
import com.netflix.spinnaker.cats.provider.ProviderSynchronizerTypeWrapper
import com.netflix.spinnaker.cats.thread.NamedThreadFactory
import com.netflix.spinnaker.clouddriver.appengine.AppEngineCloudProvider
import com.netflix.spinnaker.clouddriver.appengine.provider.AppEngineProvider
import com.netflix.spinnaker.clouddriver.appengine.security.AppEngineNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsRepository
import com.netflix.spinnaker.clouddriver.security.ProviderUtils
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Scope

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Configuration
class AppEngineProviderConfig implements Runnable {
  AppEngineCloudProvider appEngineCloudProvider
  AppEngineProvider appEngineProvider
  AccountCredentialsRepository accountCredentialsRepository
  ObjectMapper objectMapper
  Registry registry

  @Bean
  @DependsOn('appEngineNamedAccountCredentials')
  AppEngineProvider appEngineProvider(AppEngineCloudProvider appEngineCloudProvider,
                                      AccountCredentialsRepository accountCredentialsRepository,
                                      ObjectMapper objectMapper,
                                      Registry registry) {
    this.appEngineProvider = new AppEngineProvider(appEngineCloudProvider,
                                                   Collections.newSetFromMap(new ConcurrentHashMap<Agent, Boolean>()))
    this.appEngineCloudProvider = appEngineCloudProvider
    this.accountCredentialsRepository = accountCredentialsRepository
    this.objectMapper = objectMapper
    this.registry = registry

    ScheduledExecutorService poller = Executors
      .newSingleThreadScheduledExecutor(new NamedThreadFactory(AppEngineProviderConfig.class.getSimpleName()))

    poller.scheduleAtFixedRate(this, 0, 30, TimeUnit.SECONDS)

    appEngineProvider
  }

  class AppEngineProviderSynchronizerTypeWrapper implements ProviderSynchronizerTypeWrapper {
    @Override
    Class getSynchronizerType() {
      return AppEngineProviderSynchronizer
    }
  }

  class AppEngineProviderSynchronizer { }

  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  @Bean
  AppEngineProviderSynchronizer synchronizeAppEngineProvider(AppEngineProvider appEngineProvider,
                                                             AppEngineCloudProvider appEngineCloudProvider,
                                                             AccountCredentialsRepository accountCredentialsRepository,
                                                             ObjectMapper objectMapper,
                                                             Registry registry) {
    def allAccounts = ProviderUtils.buildThreadSafeSetOfAccounts(accountCredentialsRepository,
                                                                 AppEngineNamedAccountCredentials)

    appEngineProvider.agents.clear()

    allAccounts.each { AppEngineNamedAccountCredentials credentials ->

    }

    new AppEngineProviderSynchronizer()
  }

  @Override
  void run() {
    synchronizeAppEngineProvider(appEngineProvider,
                                 appEngineCloudProvider,
                                 accountCredentialsRepository,
                                 objectMapper,
                                 registry)
  }
}
