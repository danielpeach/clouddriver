/*
 * Copyright 2017 Netflix, Inc.
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
package com.netflix.spinnaker.clouddriver.aws.lifecycle

import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.services.sns.model.CreateTopicResult
import com.amazonaws.services.sns.model.SetTopicAttributesRequest
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.CreateQueueResult
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.clouddriver.aws.deploy.description.EnableDisableInstanceDiscoveryDescription
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.discovery.AwsEurekaSupport
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials
import com.netflix.spinnaker.clouddriver.data.task.DefaultTask
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.eureka.deploy.ops.AbstractEurekaSupport.DiscoveryStatus
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

import javax.inject.Provider

class InstanceTerminationLifecycleAgentSpec extends Specification {

  NetflixAmazonCredentials mgmtCredentials = Mock() {
    getAccountId() >> { return "100" }
    getName() >> { return "mgmt" }
  }
  NetflixAmazonCredentials testCredentials = Mock() {
    getAccountId() >> { return "200" }
    getName() >> { return "test" }
  }

  AmazonSQS amazonSQS = Mock()
  AmazonSNS amazonSNS = Mock()
  AccountCredentialsProvider accountCredentialsProvider = Mock() {
    getAll() >>[mgmtCredentials, testCredentials]
  }
  Provider<AwsEurekaSupport> awsEurekaSupportProvider = Mock()
  AwsEurekaSupport awsEurekaSupport = Mock()

  def queueARN = new ARN([mgmtCredentials, testCredentials], "arn:aws:sqs:us-west-2:100:queueName")
  def topicARN = new ARN([mgmtCredentials, testCredentials], "arn:aws:sns:us-west-2:100:topicName")

  @Shared
  def objectMapper = new ObjectMapper()

  @Subject
  def subject = new InstanceTerminationLifecycleAgent(
    objectMapper,
    Mock(AmazonClientProvider),
    accountCredentialsProvider,
    new InstanceTerminationConfigurationProperties(
      'mgmt',
      queueARN.arn,
      topicARN.arn,
      -1,
      -1,
      -1
    ),
    awsEurekaSupportProvider
  )

  def "should create topic if it does not exist"() {
    when:
    def topicId = LaunchFailureNotificationAgent.ensureTopicExists(amazonSNS, topicARN, ['100', '200'], queueARN)

    then:
    topicId == topicARN.arn

    1 * amazonSNS.createTopic(topicARN.name) >> { new CreateTopicResult().withTopicArn(topicARN.arn) }

    // should attach a policy granting SendMessage rights to the source topic
    1 * amazonSNS.setTopicAttributes(new SetTopicAttributesRequest()
      .withTopicArn(topicARN.arn)
      .withAttributeName("Policy")
      .withAttributeValue(LaunchFailureNotificationAgent.buildSNSPolicy(topicARN, ['100', '200']).toJson()))

    // should subscribe the queue to this topic
    1 * amazonSNS.subscribe(topicARN.arn, "sqs", queueARN.arn)
    0 * _
  }

  def 'should create queue if it does not exist'() {
    when:
    def queueId = InstanceTerminationLifecycleAgent.ensureQueueExists(amazonSQS, queueARN, topicARN)

    then:
    queueId == "my-queue-url"

    1 * amazonSQS.createQueue(queueARN.name) >> { new CreateQueueResult().withQueueUrl("my-queue-url") }

    1 * amazonSQS.setQueueAttributes("my-queue-url", [
      "Policy": InstanceTerminationLifecycleAgent.buildSQSPolicy(queueARN, topicARN).toJson()
    ])
    0 * _
  }

  def 'should get update discovery with notification'() {
    given:
    LifecycleMessage message = new LifecycleMessage(
      accountId: '200',
      autoScalingGroupName: 'clouddriver-main-v000',
      ec2InstanceId: 'i-1234',
      lifecycleTransition: 'autoscaling:EC2_INSTANCE_TERMINATING'
    )

    when:
    subject.handleMessage(message, Mock(DefaultTask))

    then:
    1 * accountCredentialsProvider.getAll() >> [mgmtCredentials, testCredentials]
    1 * awsEurekaSupportProvider.get() >> awsEurekaSupport
    1 * awsEurekaSupport.updateDiscoveryStatusForInstances(
      { EnableDisableInstanceDiscoveryDescription arg ->
        arg.credentials == testCredentials
        arg.region == 'us-west-2'
        arg.asgName == 'clouddriver-main-v000'
        arg.instanceIds == ['i-1234']
      },
      _ as Task,
      'handleLifecycleMessage',
      DiscoveryStatus.Disable,
      ['i-1234']
    )
  }

  def 'should process both sns and sqs messages'() {
    given:
    LifecycleMessage lifecycleMessage = new LifecycleMessage(
      accountId: '1234',
      autoScalingGroupName: 'clouddriver-main-v000',
      ec2InstanceId: 'i-1324',
      lifecycleTransition: 'autoscaling:EC2_INSTANCE_TERMINATING'
    )
    String sqsMessage = objectMapper.writeValueAsString(lifecycleMessage)
    String snsMessage = objectMapper.writeValueAsString(new NotificationMessageWrapper(
      subject: 'lifecycle message',
      message: sqsMessage
    ))

    when:
    LifecycleMessage result = subject.unmarshalLifecycleMessage(snsMessage)

    then:
    result.accountId == lifecycleMessage.accountId
    result.autoScalingGroupName == lifecycleMessage.autoScalingGroupName
    result.ec2InstanceId == lifecycleMessage.ec2InstanceId
    result.lifecycleTransition == lifecycleMessage.lifecycleTransition

    when:
    result = subject.unmarshalLifecycleMessage(sqsMessage)

    then:
    result.accountId == lifecycleMessage.accountId
    result.autoScalingGroupName == lifecycleMessage.autoScalingGroupName
    result.ec2InstanceId == lifecycleMessage.ec2InstanceId
    result.lifecycleTransition == lifecycleMessage.lifecycleTransition
  }



}
