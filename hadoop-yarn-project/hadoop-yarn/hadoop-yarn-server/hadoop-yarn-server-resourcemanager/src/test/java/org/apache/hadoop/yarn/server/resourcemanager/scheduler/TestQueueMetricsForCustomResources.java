/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.metrics2.MetricsSource;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.impl.MetricsSystemImpl;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceInformation;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.resourcetypes.ResourceTypesTestHelper;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;


import org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .QueueMetricsForCustomResources.QueueMetricsCustomResource;
import org.apache.hadoop.yarn.util.resource.ResourceUtils;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES;
import static org.apache.hadoop.yarn.resourcetypes.ResourceTypesTestHelper
    .extractCustomResourcesAsStrings;
import static org.apache.hadoop.yarn.resourcetypes.ResourceTypesTestHelper.newResource;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.AGGREGATE_CONTAINERS_ALLOCATED;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.AGGREGATE_CONTAINERS_RELEASED;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.AGGREGATE_MEMORY_MB_SECONDS_PREEMPTED;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.AGGREGATE_VCORE_SECONDS_PREEMPTED;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.ALLOCATED_CONTAINERS;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.ALLOCATED_MB;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.ALLOCATED_V_CORES;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.AVAILABLE_MB;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.AVAILABLE_V_CORES;

import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.PENDING_CONTAINERS;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.PENDING_MB;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .ResourceMetricsChecker.ResourceMetricsKey.PENDING_V_CORES;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.RESERVED_CONTAINERS;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.RESERVED_MB;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.RESERVED_V_CORES;

import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.ALLOCATED_CUSTOM_RES1;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.ALLOCATED_CUSTOM_RES2;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.AVAILABLE_CUSTOM_RES1;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.AVAILABLE_CUSTOM_RES2;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.PENDING_CUSTOM_RES1;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.PENDING_CUSTOM_RES2;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.RESERVED_CUSTOM_RES1;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.RESERVED_CUSTOM_RES2;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.AGGREGATE_PREEMPTED_SECONDS_CUSTOM_RES1;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceMetricsChecker.ResourceMetricsKey.AGGREGATE_PREEMPTED_SECONDS_CUSTOM_RES2;
import static org.apache.hadoop.yarn.server.resourcemanager.scheduler.TestQueueMetrics.queueSource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestQueueMetricsForCustomResources {
  public enum MetricsForCustomResource {
    ALLOCATED, AVAILABLE, PENDING, RESERVED, AGGREGATE_PREEMPTED_SECONDS
  }

  public static final long GB = 1024; // MB
  private static final Configuration CONF = new Configuration();
  public static final String CUSTOM_RES_1 = "custom_res_1";
  public static final String CUSTOM_RES_2 = "custom_res_2";
  public static final String USER = "alice";
  private Resource defaultResource;
  private MetricsSystem ms;

  @Before
  public void setUp() {
    ms = new MetricsSystemImpl();
    QueueMetrics.clearQueueMetrics();
    initializeResourceTypes();
    createDefaultResource();
  }

  private void createDefaultResource() {
    defaultResource = newResource(4 * GB, 4,
        ImmutableMap.<String, String> builder()
            .put(CUSTOM_RES_1, String.valueOf(15 * GB))
            .put(CUSTOM_RES_2, String.valueOf(20 * GB))
            .build());
  }

  private void initializeResourceTypes() {
    Map<String, ResourceInformation> riMap = new HashMap<>();

    ResourceInformation memory = ResourceInformation.newInstance(
        ResourceInformation.MEMORY_MB.getName(),
        ResourceInformation.MEMORY_MB.getUnits(),
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB,
        DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB);
    ResourceInformation vcores = ResourceInformation.newInstance(
        ResourceInformation.VCORES.getName(),
        ResourceInformation.VCORES.getUnits(),
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES,
        DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES);
    ResourceInformation res1 = ResourceInformation.newInstance(CUSTOM_RES_1,
        ResourceInformation.VCORES.getUnits(), 0, 2000);
    ResourceInformation res2 = ResourceInformation.newInstance(CUSTOM_RES_2,
        ResourceInformation.VCORES.getUnits(), 0, 2000);

    riMap.put(ResourceInformation.MEMORY_URI, memory);
    riMap.put(ResourceInformation.VCORES_URI, vcores);
    riMap.put(CUSTOM_RES_1, res1);
    riMap.put(CUSTOM_RES_2, res2);
    ResourceUtils.initializeResourcesFromResourceInformationMap(riMap);
  }

  private static void assertCustomResourceValue(
      MetricsForCustomResource metricsType,
      Resource res,
      String resourceName,
      long expectedValue) {
    Long value = res.getResourceValue(resourceName);
    assertCustomResourceValueInternal(metricsType, resourceName,
        expectedValue, value);
  }

  private static void assertCustomResourceValueInternal(
      MetricsForCustomResource metricsType, String resourceName, long
      expectedValue, Long value) {
    assertNotNull(
            "QueueMetrics should have custom resource metrics value " +
                "for resource: " + resourceName, value);
    assertEquals(String.format(
            "QueueMetrics should have custom resource metrics value %d " +
                "for resource: %s for metrics type %s",
            expectedValue, resourceName, metricsType), expectedValue,
            (long) value);
  }

  private static Map<String, String> getCustomResourcesWithValue(long value) {
    return ImmutableMap.<String, String>builder()
        .put(CUSTOM_RES_1, String.valueOf(value))
        .put(CUSTOM_RES_2, String.valueOf(value))
        .build();
  }

  private QueueInfo createFourLevelQueueHierarchy() {
    QueueInfo root = new QueueInfo(null, "root", ms, CONF, USER);
    QueueInfo sub = new QueueInfo(root, "root.subQ", ms, CONF, USER);
    QueueInfo sub2 = new QueueInfo(sub, "root.subQ2", ms, CONF, USER);
    return new QueueInfo(sub2, "root.subQ2.leafQ", ms, CONF, USER);
  }

  private QueueInfo createBasicQueueHierarchy() {
    QueueInfo root = new QueueInfo(null, "root", ms, CONF, USER);
    return new QueueInfo(root, "root.leaf", ms, CONF, USER);
  }

  private QueueMetricsTestData.Builder
      createQueueMetricsTestDataWithContainers(int containers) {
    return createDefaultQueueMetricsTestData()
        .withContainers(containers);
  }

  private QueueMetricsTestData.Builder createDefaultQueueMetricsTestData() {
    return QueueMetricsTestData.Builder.create()
        .withUser(USER)
        .withPartition(RMNodeLabelsManager.NO_LABEL);
  }

  private void testIncreasePendingResources(QueueMetricsTestData testData) {
    testIncreasePendingResourcesInternal(testData.containers, testData);
  }

  private void testIncreasePendingResourcesWithoutContainer(
      QueueMetricsTestData testData) {
    testIncreasePendingResourcesInternal(1, testData);
  }

  private void testIncreasePendingResourcesInternal(int containers,
      QueueMetricsTestData testData) {
    testData.leafQueue.queueMetrics.incrPendingResources(testData.partition,
        testData.user, containers, testData.resource);

    ResourceMetricsChecker checker = ResourceMetricsChecker
        .create()
        .gaugeInt(PENDING_CONTAINERS, containers)
        .gaugeLong(PENDING_MB, containers *
            testData.resource.getMemorySize())
        .gaugeInt(PENDING_V_CORES, containers *
              testData.resource.getVirtualCores())
        .gaugeLong(PENDING_CUSTOM_RES1,
            containers * testData.customResourceValues.get(CUSTOM_RES_1))
        .gaugeLong(PENDING_CUSTOM_RES2,
            containers * testData.customResourceValues.get(CUSTOM_RES_2));
    Map<String, Long> expected = new HashMap<>();
    for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
      expected.put(entry.getKey(), entry.getValue() * containers);
    }
    assertAllPendingMetrics(testData.leafQueue, checker,
        MetricsForCustomResource.PENDING, expected);
  }

  private void testAllocateResources(boolean decreasePending,
      QueueMetricsTestData testData) {
    testData.leafQueue.queueMetrics.allocateResources(testData.partition,
        testData.user, testData.containers, testData.resource, decreasePending);

    ResourceMetricsChecker checker = ResourceMetricsChecker
        .create()
        .gaugeInt(ALLOCATED_CONTAINERS, testData.containers)
        .counter(AGGREGATE_CONTAINERS_ALLOCATED, testData.containers)
        .gaugeLong(ALLOCATED_MB, testData.containers *
            testData.resource.getMemorySize())
        .gaugeInt(ALLOCATED_V_CORES, testData.containers *
            testData.resource.getVirtualCores())
        .gaugeInt(PENDING_CONTAINERS, 0)
        .gaugeLong(PENDING_MB, 0)
        .gaugeInt(PENDING_V_CORES, 0)
        .gaugeLong(ALLOCATED_CUSTOM_RES1,
            testData.containers
                * testData.customResourceValues.get(CUSTOM_RES_1))
        .gaugeLong(ALLOCATED_CUSTOM_RES2,
            testData.containers
                * testData.customResourceValues.get(CUSTOM_RES_2))
        .checkAgainst(testData.leafQueue.queueSource);
    if (decreasePending) {
      Map<String, Long> expected = new HashMap<>();
      for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
        expected.put(entry.getKey(), 0L);
      }
      assertAllPendingMetrics(testData.leafQueue, checker,
          MetricsForCustomResource.PENDING, expected);
    }
    if (!testData.customResourceValues.isEmpty()) {
      Map<String, Long> expected = new HashMap<>();
      for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
        expected.put(entry.getKey(), entry.getValue() * testData.containers);
      }
      assertAllAllocatedMetrics(testData.leafQueue, checker,
          MetricsForCustomResource.ALLOCATED, expected);
    }
  }

  private void testUpdatePreemptedSeconds(QueueMetricsTestData testData,
      int seconds) {
    testData.leafQueue.queueMetrics.updatePreemptedMemoryMBSeconds(
        testData.resource.getMemorySize() * seconds);
    testData.leafQueue.queueMetrics.updatePreemptedVcoreSeconds(
        testData.resource.getVirtualCores() * seconds);
    testData.leafQueue.queueMetrics.updatePreemptedSecondsForCustomResources(
        testData.resource, seconds);

    ResourceMetricsChecker checker = ResourceMetricsChecker
        .create()
        .counter(AGGREGATE_MEMORY_MB_SECONDS_PREEMPTED,
            testData.resource.getMemorySize() * seconds)
        .counter(AGGREGATE_VCORE_SECONDS_PREEMPTED,
            testData.resource.getVirtualCores() * seconds)
        .gaugeLong(AGGREGATE_PREEMPTED_SECONDS_CUSTOM_RES1,
            testData.customResourceValues.get(CUSTOM_RES_1) * seconds)
        .gaugeLong(AGGREGATE_PREEMPTED_SECONDS_CUSTOM_RES2,
            testData.customResourceValues.get(CUSTOM_RES_2) * seconds);

    Map<String, Long> expected = new HashMap<>();
    for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
      expected.put(entry.getKey(), entry.getValue() * seconds);
    }
    assertQueuePreemptedMetricsOnly(testData.leafQueue, checker,
        MetricsForCustomResource.AGGREGATE_PREEMPTED_SECONDS, expected);
  }

  private Resource convertPreemptedSecondsToResource(QueueMetrics qm) {
    QueueMetricsCustomResource customValues = qm
        .getAggregatedPreemptedSecondsResources();
    MutableCounterLong vcoreSeconds = qm
        .getAggregateVcoreSecondsPreempted();
    MutableCounterLong memorySeconds = qm
        .getAggregateMemoryMBSecondsPreempted();
    return Resource.newInstance(
        memorySeconds.value(), (int) vcoreSeconds.value(),
        customValues.getValues());
  }

  private void testReserveResources(QueueMetricsTestData testData) {
    testData.leafQueue.queueMetrics.reserveResource(testData.partition,
        testData.user, testData.resource);

    ResourceMetricsChecker checker = ResourceMetricsChecker
        .create()
        .gaugeInt(RESERVED_CONTAINERS, 1)
        .gaugeLong(RESERVED_MB, testData.resource.getMemorySize())
        .gaugeInt(RESERVED_V_CORES, testData.resource.getVirtualCores())
        .gaugeLong(RESERVED_CUSTOM_RES1,
            testData.customResourceValues.get(CUSTOM_RES_1))
        .gaugeLong(RESERVED_CUSTOM_RES2,
            testData.customResourceValues.get(CUSTOM_RES_2))
        .checkAgainst(testData.leafQueue.queueSource);
    assertAllReservedMetrics(testData.leafQueue, checker,
        MetricsForCustomResource.RESERVED, testData.customResourceValues);
  }

  private void testGetAllocatedResources(QueueMetricsTestData testData) {
    testAllocateResources(false, testData);

    Resource res = testData.leafQueue.queueMetrics.getAllocatedResources();
    if (testData.customResourceValues.size() > 0) {
      assertCustomResourceValueInternal(MetricsForCustomResource.ALLOCATED,
          CUSTOM_RES_1,
          testData.customResourceValues.get(CUSTOM_RES_1) * testData.containers,
          res.getResourceValue(CUSTOM_RES_1));
      assertCustomResourceValueInternal(MetricsForCustomResource.ALLOCATED,
          CUSTOM_RES_2,
          testData.customResourceValues.get(CUSTOM_RES_2) * testData.containers,
          res.getResourceValue(CUSTOM_RES_2));
    }
  }

  private void assertAllPendingMetrics(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    assertAllPendingQueueMetrics(queueInfo, checker, metricsType,
        expectedCustomResourceValues);

    //assert leaf and root userSources
    checker = ResourceMetricsChecker.createFromChecker(checker)
        .checkAgainst(queueInfo.userSource);
    ResourceMetricsChecker.createFromChecker(checker)
        .checkAgainst(queueInfo.getRoot().userSource);
  }

  private void assertQueuePendingMetricsOnly(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    assertAllPendingQueueMetrics(queueInfo, checker, metricsType,
        expectedCustomResourceValues);
  }

  private void assertAllPendingQueueMetrics(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    // assert normal resource metrics values
    queueInfo.checkAllQueueSources(checker);

    // assert custom resource metrics values
    checkAllPendingQueueMetrics(queueInfo, metricsType, CUSTOM_RES_1,
        expectedCustomResourceValues.get(CUSTOM_RES_1));
    checkAllPendingQueueMetrics(queueInfo, metricsType, CUSTOM_RES_2,
        expectedCustomResourceValues.get(CUSTOM_RES_2));
  }

  private void checkAllPendingQueueMetrics(QueueInfo queueInfo,
      MetricsForCustomResource metricsType, String resName, long resValue) {
    assertCustomResourceValue(metricsType,
        queueInfo.queueMetrics.getPendingResources(), resName, resValue);
    if (queueInfo.getParentQueueInfo() != null) {
      checkAllPendingQueueMetrics(queueInfo.getParentQueueInfo(), metricsType,
          resName, resValue);
    }
  }

  private void assertAllAllocatedMetrics(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    assertAllAllocatedQueueMetrics(queueInfo, checker, metricsType,
        expectedCustomResourceValues);

    //assert leaf and root userSources
    checker = ResourceMetricsChecker.createFromChecker(checker)
        .checkAgainst(queueInfo.userSource);
    ResourceMetricsChecker.createFromChecker(checker)
        .checkAgainst(queueInfo.getRoot().userSource);
  }

  private void assertQueueAllocatedMetricsOnly(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    assertAllAllocatedQueueMetrics(queueInfo, checker, metricsType,
        expectedCustomResourceValues);
  }

  private void assertAllAllocatedQueueMetrics(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    // assert normal resource metrics values
    queueInfo.checkAllQueueSources(checker);

    // assert custom resource metrics values
    checkAllAllocatedQueueMetrics(queueInfo, metricsType, CUSTOM_RES_1,
        expectedCustomResourceValues.get(CUSTOM_RES_1));
    checkAllAllocatedQueueMetrics(queueInfo, metricsType, CUSTOM_RES_2,
        expectedCustomResourceValues.get(CUSTOM_RES_2));
  }

  private void checkAllAllocatedQueueMetrics(QueueInfo queueInfo,
      MetricsForCustomResource metricsType, String resName, long resValue) {
    assertCustomResourceValue(metricsType,
        queueInfo.queueMetrics.getAllocatedResources(), resName, resValue);
    if (queueInfo.getParentQueueInfo() != null) {
      checkAllAllocatedQueueMetrics(queueInfo.getParentQueueInfo(), metricsType,
          resName, resValue);
    }
  }

  private void assertAllPreemptedMetrics(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    assertAllPreemptedQueueMetrics(queueInfo, checker, metricsType,
        expectedCustomResourceValues);

    //assert leaf and root userSources
    checker = ResourceMetricsChecker.createFromChecker(checker)
        .checkAgainst(queueInfo.userSource);
    ResourceMetricsChecker.createFromChecker(checker)
        .checkAgainst(queueInfo.getRoot().userSource);
  }

  private void assertQueuePreemptedMetricsOnly(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    assertAllPreemptedQueueMetrics(queueInfo, checker, metricsType,
        expectedCustomResourceValues);
  }

  private void assertAllPreemptedQueueMetrics(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    // assert normal resource metrics values
    queueInfo.checkAllQueueSources(checker);

    // assert custom resource metrics values
    checkAllPreemptedQueueMetrics(queueInfo, metricsType, CUSTOM_RES_1,
        expectedCustomResourceValues.get(CUSTOM_RES_1));
    checkAllPreemptedQueueMetrics(queueInfo, metricsType, CUSTOM_RES_2,
        expectedCustomResourceValues.get(CUSTOM_RES_2));
  }

  private void checkAllPreemptedQueueMetrics(QueueInfo queueInfo,
      MetricsForCustomResource metricsType, String resName, long resValue) {
    assertCustomResourceValue(metricsType,
        convertPreemptedSecondsToResource(queueInfo.queueMetrics), resName, resValue);
    if (queueInfo.getParentQueueInfo() != null) {
      checkAllPreemptedQueueMetrics(queueInfo.getParentQueueInfo(), metricsType,
          resName, resValue);
    }
  }

  private void assertAllReservedMetrics(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    assertAllReservedQueueMetrics(queueInfo, checker, metricsType,
        expectedCustomResourceValues);

    //assert leaf and root userSources
    checker = ResourceMetricsChecker.createFromChecker(checker)
        .checkAgainst(queueInfo.userSource);
    ResourceMetricsChecker.createFromChecker(checker)
        .checkAgainst(queueInfo.getRoot().userSource);
  }

  private void assertQueueReservedMetricsOnly(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    assertAllReservedQueueMetrics(queueInfo, checker, metricsType,
        expectedCustomResourceValues);
  }

  private void assertAllReservedQueueMetrics(QueueInfo queueInfo,
      ResourceMetricsChecker checker,
      MetricsForCustomResource metricsType,
      Map<String, Long> expectedCustomResourceValues) {
    // assert normal resource metrics values
    queueInfo.checkAllQueueSources(checker);

    // assert custom resource metrics values
    checkAllReservedQueueMetrics(queueInfo, metricsType, CUSTOM_RES_1,
        expectedCustomResourceValues.get(CUSTOM_RES_1));
    checkAllReservedQueueMetrics(queueInfo, metricsType, CUSTOM_RES_2,
        expectedCustomResourceValues.get(CUSTOM_RES_2));
  }

  private void checkAllReservedQueueMetrics(QueueInfo queueInfo,
      MetricsForCustomResource metricsType, String resName, long resValue) {
    assertCustomResourceValue(metricsType,
        queueInfo.queueMetrics.getReservedResources(), resName, resValue);
    if (queueInfo.getParentQueueInfo() != null) {
      checkAllReservedQueueMetrics(queueInfo.getParentQueueInfo(), metricsType,
          resName, resValue);
    }
  }

  @Test
  public void testSetAvailableResourcesToQueue1() {
    String queueName = "single";
    QueueMetrics metrics = QueueMetrics.forQueue(ms, queueName, null,
        false, CONF);
    MetricsSource queueSource = queueSource(ms, queueName);

    metrics.setAvailableResourcesToQueue(newResource(
        GB, 4,
        ImmutableMap.<String, String> builder()
            .put(CUSTOM_RES_1, String.valueOf(5 * GB))
            .put(CUSTOM_RES_2, String.valueOf(6 * GB))
            .build()));
    ResourceMetricsChecker.create()
        .gaugeLong(AVAILABLE_MB, GB)
        .gaugeInt(AVAILABLE_V_CORES, 4)
        .gaugeLong(AVAILABLE_CUSTOM_RES1, 5 * GB)
        .gaugeLong(AVAILABLE_CUSTOM_RES2, 6 * GB)
        .checkAgainst(queueSource);

    assertCustomResourceValue(MetricsForCustomResource.AVAILABLE,
        metrics.getAvailableResources(), CUSTOM_RES_1, 5 * GB);
    assertCustomResourceValue(MetricsForCustomResource.AVAILABLE,
        metrics.getAvailableResources(), CUSTOM_RES_2, 6 * GB);
  }

  @Test
  public void testSetAvailableResourcesToQueue2() {
    String queueName = "single";
    QueueMetrics metrics = QueueMetrics.forQueue(ms, queueName, null,
        false, CONF);
    MetricsSource queueSource = queueSource(ms, queueName);

    metrics.setAvailableResourcesToQueue(null,
        newResource(GB, 4,
        ImmutableMap.<String, String> builder()
            .put(CUSTOM_RES_1, String.valueOf(15 * GB))
            .put(CUSTOM_RES_2, String.valueOf(20 * GB))
            .build()));
    ResourceMetricsChecker.create()
        .gaugeLong(AVAILABLE_MB, GB)
        .gaugeInt(AVAILABLE_V_CORES, 4)
        .gaugeLong(AVAILABLE_CUSTOM_RES1, 15 * GB)
        .gaugeLong(AVAILABLE_CUSTOM_RES2, 20 * GB)
        .checkAgainst(queueSource);

    assertCustomResourceValue(MetricsForCustomResource.AVAILABLE,
        metrics.getAvailableResources(), CUSTOM_RES_1, 15 * GB);
    assertCustomResourceValue(MetricsForCustomResource.AVAILABLE,
        metrics.getAvailableResources(), CUSTOM_RES_2, 20 * GB);
  }

  @Test
  public void testIncreasePendingResources() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withLeafQueue(createBasicQueueHierarchy())
        .withResourceToDecrease(
            newResource(GB, 2, getCustomResourcesWithValue(2 * GB)), 2)
        .withResources(defaultResource)
        .build();

    testIncreasePendingResources(testData);
  }

  @Test
  public void testDecreasePendingResources() {
    Resource resourceToDecrease =
        newResource(GB, 2, getCustomResourcesWithValue(2 * GB));
    int containersToDecrease = 2;
    int containers = 5;
    QueueMetricsTestData testData =
        createQueueMetricsTestDataWithContainers(containers)
            .withLeafQueue(createBasicQueueHierarchy())
        .withResourceToDecrease(resourceToDecrease, containers)
        .withResources(defaultResource)
        .build();

    //compute expected values
    final int vCoresToDecrease = resourceToDecrease.getVirtualCores();
    final long memoryMBToDecrease = resourceToDecrease.getMemorySize();
    final int containersAfterDecrease = containers - containersToDecrease;
    final long customRes1ToDecrease =
      resourceToDecrease.getResourceValue(CUSTOM_RES_1);
    final long customRes2ToDecrease =
      resourceToDecrease.getResourceValue(CUSTOM_RES_2);

    final int vcoresAfterDecrease =
        (defaultResource.getVirtualCores() * containers)
            - (vCoresToDecrease * containersToDecrease);
    final long memoryAfterDecrease =
        (defaultResource.getMemorySize() * containers)
            - (memoryMBToDecrease * containersToDecrease);
    final long customResource1AfterDecrease =
      (testData.customResourceValues.get(CUSTOM_RES_1) * containers)
          - (customRes1ToDecrease * containersToDecrease);
    final long customResource2AfterDecrease =
      (testData.customResourceValues.get(CUSTOM_RES_2) * containers)
          - (customRes2ToDecrease * containersToDecrease);

    //first, increase resources to be able to decrease some
    testIncreasePendingResources(testData);

    //decrease resources
    testData.leafQueue.queueMetrics.decrPendingResources(testData.partition,
        testData.user, containersToDecrease,
        ResourceTypesTestHelper.newResource(memoryMBToDecrease,
            vCoresToDecrease,
            extractCustomResourcesAsStrings(resourceToDecrease)));

    //check
    ResourceMetricsChecker checker = ResourceMetricsChecker
        .create()
        .gaugeInt(PENDING_CONTAINERS, containersAfterDecrease)
        .gaugeLong(PENDING_MB, memoryAfterDecrease)
        .gaugeInt(PENDING_V_CORES, vcoresAfterDecrease)
        .gaugeLong(PENDING_CUSTOM_RES1, customResource1AfterDecrease)
        .gaugeLong(PENDING_CUSTOM_RES2, customResource2AfterDecrease)
        .checkAgainst(testData.leafQueue.queueSource);

    Map<String, Long> expected = new HashMap<>();
    for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
      expected.put(entry.getKey(), entry.getValue() * containers
          - (resourceToDecrease.getResourceValue(entry.getKey()) * containersToDecrease));
    }
    assertAllPendingMetrics(testData.leafQueue, checker,
        MetricsForCustomResource.PENDING, expected);
  }

  @Test
  public void testAllocateResourcesWithoutDecreasePending() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withLeafQueue(createBasicQueueHierarchy())
        .withResources(defaultResource)
        .build();

    testAllocateResources(false, testData);
  }

  @Test
  public void testAllocateResourcesWithDecreasePending() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withLeafQueue(createBasicQueueHierarchy())
        .withResourceToDecrease(
            newResource(GB, 2, getCustomResourcesWithValue(2 * GB)), 2)
        .withResources(defaultResource)
        .build();

    //first, increase pending resources to be able to decrease some
    testIncreasePendingResources(testData);

    //then allocate with decrease pending resources
    testAllocateResources(true, testData);
  }

  @Test
  public void testAllocateResourcesWithoutContainer() {
    QueueMetricsTestData testData = createDefaultQueueMetricsTestData()
        .withLeafQueue(createBasicQueueHierarchy())
        .withResources(defaultResource)
        .build();

    //first, increase pending resources
    testIncreasePendingResourcesWithoutContainer(testData);

    Resource resource = testData.resource;
    testData.leafQueue.queueMetrics.allocateResources(testData.partition,
        testData.user, resource);

    ResourceMetricsChecker checker = ResourceMetricsChecker.create()
        .gaugeLong(ALLOCATED_MB, resource.getMemorySize())
        .gaugeInt(ALLOCATED_V_CORES, resource.getVirtualCores())
        .gaugeInt(PENDING_CONTAINERS, 1).gaugeLong(PENDING_MB, 0)
        .gaugeInt(PENDING_V_CORES, 0)
        .gaugeLong(ALLOCATED_CUSTOM_RES1,
            testData.customResourceValues.get(CUSTOM_RES_1))
        .gaugeLong(ALLOCATED_CUSTOM_RES2,
            testData.customResourceValues.get(CUSTOM_RES_2));

    checker.checkAgainst(testData.leafQueue.queueSource);
    checker.checkAgainst(testData.leafQueue.getRoot().queueSource);

    Map<String, Long> expected = new HashMap<>();
    for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
      expected.put(entry.getKey(), 0L);
    }
    assertAllPendingMetrics(testData.leafQueue, checker,
        MetricsForCustomResource.PENDING, expected);
    expected.clear();
    for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
      expected.put(entry.getKey(), entry.getValue());
    }
    assertAllAllocatedMetrics(testData.leafQueue, checker,
        MetricsForCustomResource.ALLOCATED, expected);
  }

  @Test
  public void testReleaseResources() {
    int containers = 5;
    QueueMetricsTestData testData =
        createQueueMetricsTestDataWithContainers(containers)
            .withLeafQueue(createBasicQueueHierarchy())
        .withResourceToDecrease(defaultResource, containers)
        .withResources(defaultResource)
        .build();

    //first, allocate some resources so that we can release some
    testAllocateResources(false, testData);

    testData.leafQueue.queueMetrics.releaseResources(testData.partition,
        testData.user, containers, defaultResource);

    ResourceMetricsChecker checker = ResourceMetricsChecker
        .create()
        .counter(AGGREGATE_CONTAINERS_ALLOCATED, containers)
        .counter(AGGREGATE_CONTAINERS_RELEASED, containers)
        .checkAgainst(testData.leafQueue.queueSource);
    Map<String, Long> expected = new HashMap<>();
    for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
      expected.put(entry.getKey(), 0L);
    }
    assertAllAllocatedMetrics(testData.leafQueue, checker,
        MetricsForCustomResource.ALLOCATED, expected);
  }

  @Test
  public void testUpdatePreemptedSecondsForCustomResources() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withLeafQueue(createFourLevelQueueHierarchy())
        .withResources(defaultResource)
        .build();

    final int seconds = 1;
    testUpdatePreemptedSeconds(testData, seconds);
  }

  @Test
  public void testUpdatePreemptedSecondsForCustomResourcesMoreSeconds() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withLeafQueue(createFourLevelQueueHierarchy())
        .withResources(defaultResource)
        .build();

    final int seconds = 15;
    testUpdatePreemptedSeconds(testData, seconds);
  }

  @Test
  public void testReserveResources() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withLeafQueue(createBasicQueueHierarchy())
        .withResources(defaultResource)
        .build();

    testReserveResources(testData);
  }

  @Test
  public void testUnreserveResources() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withLeafQueue(createBasicQueueHierarchy())
        .withResources(defaultResource)
        .build();

    testReserveResources(testData);

    testData.leafQueue.queueMetrics.unreserveResource(testData.partition,
        testData.user, defaultResource);

    ResourceMetricsChecker checker = ResourceMetricsChecker
        .create()
        .gaugeInt(RESERVED_CONTAINERS, 0)
        .gaugeLong(RESERVED_MB, 0)
        .gaugeInt(RESERVED_V_CORES, 0)
        .gaugeLong(RESERVED_CUSTOM_RES1, 0).gaugeLong(RESERVED_CUSTOM_RES2, 0)
        .checkAgainst(testData.leafQueue.queueSource);
    Map<String, Long> expected = new HashMap<>();
    for (Map.Entry<String, Long> entry : testData.customResourceValues.entrySet()) {
      expected.put(entry.getKey(), 0L);
    }
    assertAllReservedMetrics(testData.leafQueue, checker,
        MetricsForCustomResource.RESERVED, expected);
  }

  @Test
  public void testGetAllocatedResourcesWithCustomResources() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withLeafQueue(createBasicQueueHierarchy())
        .withResources(defaultResource)
        .build();

    testGetAllocatedResources(testData);
  }

  @Test
  public void testGetAllocatedResourcesWithoutCustomResources() {
    QueueMetricsTestData testData = createQueueMetricsTestDataWithContainers(5)
        .withResources(newResource(4 * GB, 4, Collections.<String, String>emptyMap()))
        .withLeafQueue(createBasicQueueHierarchy())
        .build();

    testGetAllocatedResources(testData);
  }

}
