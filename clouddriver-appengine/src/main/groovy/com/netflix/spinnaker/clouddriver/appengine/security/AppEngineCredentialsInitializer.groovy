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

package com.netflix.spinnaker.clouddriver.appengine.security

import com.netflix.spinnaker.cats.module.CatsModule
import com.netflix.spinnaker.clouddriver.appengine.config.AppEngineConfigurationProperties
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsRepository
import com.netflix.spinnaker.clouddriver.security.CredentialsInitializerSynchronizable
import com.netflix.spinnaker.clouddriver.security.ProviderUtils
import org.apache.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@Component
@Configuration
class AppEngineCredentialsInitializer implements CredentialsInitializerSynchronizable {
  private static final Logger log = Logger.getLogger(this.class.simpleName)

  @Autowired
  String appEngineApplicationName

  @Autowired
  AccountCredentialsRepository accountCredentialsRepository

  @Bean
  List<? extends AppEngineNamedAccountCredentials> appEngineNamedAccountCredentials(
    AppEngineConfigurationProperties appEngineConfigurationProperties) {
    synchronizeAppEngineAccounts(appEngineConfigurationProperties, null)
  }

  @Override
  String getCredentialsSynchronizationBeanName() {
    return "synchronizeAppEngineAccounts"
  }

  List<?> synchronizeAppEngineAccounts(AppEngineConfigurationProperties appEngineConfigurationProperties, CatsModule catsModule) {
    def (ArrayList<AppEngineConfigurationProperties.ManagedAccount> accountsToAdd, List<String> namesOfDeletedAccounts) =
      ProviderUtils.calculateAccountDeltas(accountCredentialsRepository,
                                           AppEngineNamedAccountCredentials,
                                           appEngineConfigurationProperties.accounts)

    accountsToAdd.each { AppEngineConfigurationProperties.ManagedAccount managedAccount ->
      try {
        def jsonKey = AppEngineCredentialsInitializer.getJsonKey(managedAccount)
        def appEngineAccount = new AppEngineNamedAccountCredentials.Builder()
          .name(managedAccount.name)
          .environment(managedAccount.environment ?: managedAccount.name)
          .accountType(managedAccount.accountType ?: managedAccount.name)
          .project(managedAccount.project)
          .jsonKey(jsonKey)
          .jsonPath(managedAccount.jsonPath)
          .applicationName(appEngineApplicationName)
          .requiredGroupMembership(managedAccount.requiredGroupMembership)
          .build()

        accountCredentialsRepository.save(managedAccount.name, appEngineAccount)
      } catch (e) {
        log.info("Could not load account $managedAccount.name for App Engine", e)
      }
    }

    ProviderUtils.unscheduleAndDeregisterAgents(namesOfDeletedAccounts, catsModule)

    accountCredentialsRepository.all.findAll {
      it instanceof AppEngineNamedAccountCredentials
    } as List
  }

  private static String getJsonKey(AppEngineConfigurationProperties.ManagedAccount managedAccount) {
    def inputStream = managedAccount.inputStream

    inputStream ? new String(inputStream.bytes) : null
  }
}
