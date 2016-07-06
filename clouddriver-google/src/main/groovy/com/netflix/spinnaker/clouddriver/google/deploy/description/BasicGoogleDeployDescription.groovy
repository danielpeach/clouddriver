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

package com.netflix.spinnaker.clouddriver.google.deploy.description

import com.netflix.spinnaker.clouddriver.deploy.DeployDescription
import com.netflix.spinnaker.clouddriver.google.model.GoogleScalingPolicy
import groovy.transform.AutoClone
import groovy.transform.Canonical
import groovy.transform.ToString

@AutoClone
@Canonical
@ToString(includeSuper = true, includeNames = true)
class BasicGoogleDeployDescription extends BaseGoogleInstanceDescription implements DeployDescription {
  String application
  String stack
  String freeFormDetails
  Integer targetSize
  String region
  Boolean regional
  String zone
  List<String> loadBalancers
  Set<String> securityGroups
  GoogleScalingPolicy autoscalingPolicy
  Source source = new Source()

  @Canonical
  static class Source {
    // TODO(duftler): Add accountName/credentials to support cloning from one account to another.
    String region
    String serverGroupName
  }
}
