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

import com.google.api.services.compute.Compute
import com.google.api.services.compute.model.Autoscaler
import com.google.api.services.compute.model.AutoscalingPolicy
import com.google.api.services.compute.model.AutoscalingPolicyCpuUtilization
import com.google.api.services.compute.model.AutoscalingPolicyCustomMetricUtilization
import com.google.api.services.compute.model.AutoscalingPolicyLoadBalancingUtilization
import com.google.api.services.compute.model.InstanceGroupManager
import com.google.api.services.compute.model.Operation
import com.google.api.services.compute.model.RegionAutoscalerList
import com.google.api.services.compute.model.Zone
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.google.deploy.GCEUtil
import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleScalingPolicyDescription
import com.netflix.spinnaker.clouddriver.google.model.GoogleScalingPolicy
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import spock.lang.Specification
import spock.lang.Subject

class UpsertGoogleScalingPolicyAtomicOperationUnitSpec extends Specification {
  private static final PROJECT_NAME = "my-project"
  private static final SERVER_GROUP_NAME = "server-group-name"
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
  private static final SELF_LINK = "https://www.googleapis.com/compute/v1/projects/shared-spinnaker/zones/us-central1-f/instances/my-app7-dev-v000-1"
  private static final REGION = "us-central1"
  private static final INSTANCE_GROUP_MANAGER = new InstanceGroupManager(zone: ZONE, selfLink: SELF_LINK)
  private static final AUTOSCALER = GCEUtil.buildAutoscaler(SERVER_GROUP_NAME, ZONE, SELF_LINK, GOOGLE_SCALING_POLICY)
  private static final REGION_URL = "https://www.googleapis.com/compute/v1/projects/$PROJECT_NAME/regions/$REGION"
  private static final EMPTY_AUTOSCALER_LIST = new RegionAutoscalerList()


  def setupSpec() {
    TaskRepository.threadLocalTask.set(Mock(Task))
  }


  void "can create zonal and regional scaling policies"() {
    setup:
      def computeMock = Mock(Compute)
      def zonesMock = Mock(Compute.Zones)
      def zonesGetMock = Mock(Compute.Zones.Get)
      def zonesGetReal = new Zone(region: [REGION_URL])

      def credentials = new GoogleNamedAccountCredentials.Builder().project(PROJECT_NAME).compute(computeMock).build()
      def description = new UpsertGoogleScalingPolicyDescription(
        zone: ZONE,
        serverGroupName: SERVER_GROUP_NAME,
        autoscalingPolicy: GOOGLE_SCALING_POLICY,
        credentials: credentials)

      def instanceGroupManagersMock = Mock(Compute.InstanceGroupManagers)
      def instanceGroupManagersGetMock = Mock(Compute.InstanceGroupManagers.Get)
      def autoscalerMock = Mock(Compute.Autoscalers)
      def listMock = Mock(Compute.Autoscalers.List)
      def insertMock = Mock(Compute.Autoscalers.Insert)

      @Subject def operation = new UpsertGoogleScalingPolicyAtomicOperation(description)

    when:
      operation.operate([])

    then:
      // get region from zone
      1 * computeMock.zones() >> zonesMock
      1 * zonesMock.get(PROJECT_NAME, ZONE) >> zonesGetMock
      1 * zonesGetMock.execute() >> zonesGetReal

      // check if autoscaler exists
      1 * computeMock.autoscalers() >> autoscalerMock
      1 * autoscalerMock.list(PROJECT_NAME, ZONE) >> listMock
      1 * listMock.execute() >> EMPTY_AUTOSCALER_LIST

      // get instance group manager
      1 * computeMock.instanceGroupManagers() >> instanceGroupManagersMock
      1 * instanceGroupManagersMock.get(PROJECT_NAME, ZONE, SERVER_GROUP_NAME) >> instanceGroupManagersGetMock
      1 * instanceGroupManagersGetMock.execute() >> INSTANCE_GROUP_MANAGER

      // insert autoscaler
      1 * computeMock.autoscalers() >> autoscalerMock
      1 * autoscalerMock.insert(PROJECT_NAME, ZONE, AUTOSCALER) >> insertMock
      1 * insertMock.execute() 
  }

}
