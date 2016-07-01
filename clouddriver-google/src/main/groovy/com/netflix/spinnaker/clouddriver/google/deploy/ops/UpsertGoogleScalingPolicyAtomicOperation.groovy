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
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult
import com.netflix.spinnaker.clouddriver.google.deploy.GCEUtil
import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleScalingPolicyDescription
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import com.netflix.spinnaker.clouddriver.data.task.Task

class UpsertGoogleScalingPolicyAtomicOperation implements AtomicOperation<DeploymentResult> {
  private static final String BASE_PHASE = "UPSERT_SCALING_POLICY"

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  private final UpsertGoogleScalingPolicyDescription description

  UpsertGoogleScalingPolicyAtomicOperation(UpsertGoogleScalingPolicyDescription description) {
    this.description = description
  }
  /**
  * curl -X POST -H "Content-Type: application/json" -d '[ { "upsertScalingPolicy": { "serverGroupName": "autoscale-orca-test-v000", "zone": "us-central1-f", "credentials": "my-google-account", "autoscalingPolicy": { "coolDownPeriodSec": 60, "cpuUtilization": { "utilizationTarget": 0.9 }, "maxNumReplicas": 3, "minNumReplicas": 2} } } ]' localhost:7002/gce/ops
  */
  @Override
  DeploymentResult operate(List priorOutputs) {
    task.updateStatus BASE_PHASE, "Initializing upsert of scaling policy"

    def credentials = description.credentials
    def project = credentials.project
    def compute = credentials.compute
    def zone = description.zone
    def serverGroupName = description.serverGroupName
    def isRegional = description.regional
    def region = GCEUtil.getRegionFromZone(project, zone, compute)

    if (isRegional) {
      task.updateStatus BASE_PHASE, "Creating regional autoscaler for $serverGroupName..."

      def managedInstanceGroup = GCEUtil.queryRegionalManagedInstanceGroup(project, region, serverGroupName, credentials)
      Autoscaler autoscaler = GCEUtil.buildAutoscaler(serverGroupName,
                                                      managedInstanceGroup.getZone(),
                                                      managedInstanceGroup.getSelfLink(),
                                                      description.autoscalingPolicy)

      compute.regionAutoscalers().insert(project, region, autoscaler).execute()
    } else {
      task.updateStatus BASE_PHASE, "Creating zonal autoscaler for $serverGroupName..."

      def managedInstanceGroup = GCEUtil.queryZonalManagedInstanceGroup(project, zone, serverGroupName, credentials)
      Autoscaler autoscaler = GCEUtil.buildAutoscaler(serverGroupName,
                                                      managedInstanceGroup.getZone(),
                                                      managedInstanceGroup.getSelfLink(),
                                                      description.autoscalingPolicy)

      compute.autoscalers().insert(project, zone, autoscaler).execute()
    }

    task.updateStatus BASE_PHASE, "Done creating autoscaler for $serverGroupName"
    new DeploymentResult()
  }
}
