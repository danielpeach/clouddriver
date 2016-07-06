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

package com.netflix.spinnaker.clouddriver.google.deploy.validators

import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleScalingPolicyDescription
import com.netflix.spinnaker.clouddriver.google.model.GoogleScalingPolicy
import com.netflix.spinnaker.clouddriver.google.security.FakeGoogleCredentials
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.security.DefaultAccountCredentialsProvider
import com.netflix.spinnaker.clouddriver.security.MapBackedAccountCredentialsRepository
import org.springframework.validation.Errors
import spock.lang.Shared
import spock.lang.Specification

class UpsertGoogleScalingPolicyDescriptionValidatorSpec extends Specification {
  private static final SERVER_GROUP_NAME = "server-group-name"
  private static final ACCOUNT_NAME = "auto"
  private static final ZONE = "us-central1-f"
  private static final CPU_UTILIZATION = new GoogleScalingPolicy.CpuUtilization()
  private static final LOAD_BALANCING_UTILIZATION = new GoogleScalingPolicy.LoadBalancingUtilization()
  private static final METRIC = "myMetric"
  private static final UTILIZATION_TARGET = 0.6
  private static final CUSTOM_METRIC_UTILIZATIONS = [ new GoogleScalingPolicy.CustomMetricUtilization(metric: METRIC, utilizationTarget: UTILIZATION_TARGET) ]
  private static final MIN_NUM_REPLICAS = 1
  private static final MAX_NUM_REPLICAS = 10
  private static final COOL_DOWN_PERIOD_SEC = 60
  private static final GOOGLE_SCALING_POLICY = new GoogleScalingPolicy(minNumReplicas: MIN_NUM_REPLICAS,
    maxNumReplicas: MAX_NUM_REPLICAS,
    coolDownPeriodSec: COOL_DOWN_PERIOD_SEC,
    cpuUtilization: CPU_UTILIZATION,
    loadBalancingUtilization: LOAD_BALANCING_UTILIZATION,
    customMetricUtilizations: CUSTOM_METRIC_UTILIZATIONS)

  @Shared
  UpsertGoogleScalingPolicyDescriptionValidator validator

  void setupSpec() {
    validator = new UpsertGoogleScalingPolicyDescriptionValidator()
    def credentialsRepo = new MapBackedAccountCredentialsRepository()
    def credentialsProvider = new DefaultAccountCredentialsProvider(credentialsRepo)
    def credentials = new GoogleNamedAccountCredentials.Builder().name(ACCOUNT_NAME).credentials(new FakeGoogleCredentials()).build()
    credentialsRepo.save(ACCOUNT_NAME, credentials)
    validator.accountCredentialsProvider = credentialsProvider
  }

  void "pass validation with proper description inputs"() {
    setup:
      def description = new UpsertGoogleScalingPolicyDescription(
        zone: ZONE,
        serverGroupName: SERVER_GROUP_NAME,
        regional: false,
        autoscalingPolicy: GOOGLE_SCALING_POLICY,
        accountName: ACCOUNT_NAME)
      def errors = Mock(Errors)

    when:
      validator.validate([], description, errors)

    then:
      0 * errors._
  }

  void "null input fails validation"() {
    setup:
      def description = new UpsertGoogleScalingPolicyDescription()
      def errors = Mock(Errors)

    when:
      validator.validate([], description, errors)

    then:
      1 * errors.rejectValue('credentials', _)
      1 * errors.rejectValue('zone', _)
      1 * errors.rejectValue('serverGroupName', _)
      1 * errors.rejectValue('autoscalingPolicy', _)
  }
}
