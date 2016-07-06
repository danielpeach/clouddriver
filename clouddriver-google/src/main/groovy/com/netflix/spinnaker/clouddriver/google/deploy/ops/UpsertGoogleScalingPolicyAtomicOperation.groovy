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

package com.netflix.spinnaker.clouddriver.google.deploy.ops

import com.google.api.services.compute.model.Autoscaler
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.google.deploy.GCEUtil
import com.netflix.spinnaker.clouddriver.google.deploy.GoogleOperationPoller
import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleScalingPolicyDescription
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import com.netflix.spinnaker.clouddriver.data.task.Task
import org.springframework.beans.factory.annotation.Autowired

class UpsertGoogleScalingPolicyAtomicOperation implements AtomicOperation<Void> {
  private static final String BASE_PHASE = "UPSERT_SCALING_POLICY"

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  @Autowired
  private GoogleOperationPoller googleOperationPoller

  private final UpsertGoogleScalingPolicyDescription description

  UpsertGoogleScalingPolicyAtomicOperation(UpsertGoogleScalingPolicyDescription description) {
    this.description = description
  }

  /**
   * curl -X POST -H "Content-Type: application/json" -d '[ { "upsertScalingPolicy": { "serverGroupName": "autoscale-zonal", "zone": "us-central1-f", "credentials": "my-google-account", "autoscalingPolicy": { "coolDownPeriodSec": 60, "cpuUtilization": { "utilizationTarget": 0.9 }, "maxNumReplicas": 3, "minNumReplicas": 2} } } ]' localhost:7002/gce/ops
   * curl -X POST -H "Content-Type: application/json" -d '[ { "upsertScalingPolicy": { "serverGroupName": "autoscale-regional", "regional": true, "zone": "us-central1-f", "credentials": "my-google-account", "autoscalingPolicy": { "coolDownPeriodSec": 60, "cpuUtilization": { "utilizationTarget": 0.9 }, "loadBalancingUtilization": { "utilizationTarget" : 0.8 }, "maxNumReplicas": 3, "minNumReplicas": 2, "customMetricUtilizations" : [ { "metric": "myMetric", "utilizationTarget": 0.9, "utilizationTargetType" : "DELTA_PER_SECOND" } ] } } } ]' localhost:7002/gce/ops
  */

  @Override
  Void operate(List priorOutputs) {
    def serverGroupName = description.serverGroupName
    task.updateStatus BASE_PHASE, "Initializing upsert of scaling policy for $serverGroupName..."

    def credentials = description.credentials
    def project = credentials.project
    def compute = credentials.compute
    def zone = description.zone
    def isRegional = description.regional
    def region = GCEUtil.getRegionFromZone(project, zone, compute)
    def location = isRegional ? region : zone
    def autoscaler = buildAutoscaler(credentials, project, location, serverGroupName, isRegional)

    task.updateStatus BASE_PHASE, "Attempting to retrieve existing autoscaler for $serverGroupName..."

    def scalingPolicies
    if (isRegional) {
      scalingPolicies = compute.regionAutoscalers().list(project, region).execute().getItems()
    } else {
      scalingPolicies = compute.autoscalers().list(project, zone).execute().getItems()
    }

    def scalingPolicyExistsForMIG = scalingPolicies.any { it.name == serverGroupName }

    def operation
    if (scalingPolicyExistsForMIG) {
      task.updateStatus BASE_PHASE, "Updating existing autoscaler $serverGroupName..."

      if (isRegional) {
        operation = compute.regionAutoscalers().update(project, region, autoscaler).execute()
      } else {
        operation = compute.autoscalers().update(project, zone, autoscaler).execute()
      }
    } else {
      task.updateStatus BASE_PHASE, "Creating new autoscaler $serverGroupName..."

      if (isRegional) {
        operation = compute.regionAutoscalers().insert(project, region, autoscaler).execute()
      } else {
        operation = compute.autoscalers().insert(project, zone, autoscaler).execute()
      }
    }

    def operationName = operation.getName()
    if (isRegional) {
      googleOperationPoller.waitForRegionalOperation(compute, project, region, operationName, null, task,
        "regional autoscaler $serverGroupName", BASE_PHASE)
    } else {
      googleOperationPoller.waitForZonalOperation(compute, project, zone, operationName, null, task,
        "zonal autoscaler $serverGroupName", BASE_PHASE)
    }

    task.updateStatus BASE_PHASE, "Done updating/creating autoscaler $serverGroupName..."
    null
  }

  Autoscaler buildAutoscaler(GoogleNamedAccountCredentials credentials, String project, String location, String serverGroupName, Boolean isRegional) {
    def managedInstanceGroup
    if (isRegional) {
      managedInstanceGroup = GCEUtil.queryRegionalManagedInstanceGroup(project, location, serverGroupName, credentials)
    } else {
      managedInstanceGroup = GCEUtil.queryZonalManagedInstanceGroup(project, location, serverGroupName, credentials)
    }
    GCEUtil.buildAutoscaler(serverGroupName,
                            managedInstanceGroup.getZone(),
                            managedInstanceGroup.getSelfLink(),
                            description.autoscalingPolicy)
  }
}
