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

import com.netflix.spinnaker.clouddriver.deploy.DescriptionValidator
import com.netflix.spinnaker.clouddriver.google.GoogleOperation
import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleScalingPolicyDescription
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperations
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.validation.Errors

@GoogleOperation(AtomicOperations.UPSERT_SCALING_POLICY)
@Component
class UpsertGoogleScalingPolicyDescriptionValidator extends
    DescriptionValidator<UpsertGoogleScalingPolicyDescription> {

  @Autowired
  AccountCredentialsProvider accountCredentialsProvider

  @Override
  void validate(List priorDescriptions, UpsertGoogleScalingPolicyDescription description, Errors errors) {
    def helper = new StandardGceAttributeValidator("upsertGoogleScalingPolicyDescription", errors)

    helper.validateCredentials(description.accountName, accountCredentialsProvider)


    description.autoscalingPolicy.with {
      helper.validateNonNegativeLong(minNumReplicas, "autoscalingPolicy.minNumReplicas")
      helper.validateNonNegativeLong(maxNumReplicas, "autoscalingPolicy.maxNumReplicas")
      helper.validateNonNegativeLong(coolDownPeriodSec, "autoscalingPolicy.coolDownPeriodSec")
      helper.validateMaxNotLessThanMin(minNumReplicas,
        maxNumReplicas,
        "autoscalingPolicy.minNumReplicas",
        "autoscalingPolicy.maxNumReplicas")

      if (cpuUtilization) {
        helper.validateBetweenZeroAndOneDouble(cpuUtilization.utilizationTarget,
          "autoscalingPolicy.cpuUtilization.utilizationTarget")
      }

      if (loadBalancingUtilization) {
        helper.validateBetweenZeroAndOneDouble(loadBalancingUtilization.utilizationTarget) {
          "autoscalingPolicy.loadBalancingUtilization.utilizationTarget"
        }
      }

      if (customMetricUtilizations) {
        customMetricUtilizations.eachWithIndex { utilization, index ->
          helper.validateNotEmpty(utilization.metric,
            "autoscalingPolicy.customMetricUtilizations${index}.metric")
          helper.validateBetweenZeroAndOneDouble(utilization.utilizationTarget,
            "autoscalingPolicy.customMetricUtilizations${index}.utilizationTarget")
        }
      }

    }
  }
}
