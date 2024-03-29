/*
 * *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.runtime;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.privileged.PrivilegedOperationExecutor;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.runtime.ContainerExecutionException;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.runtime.ContainerRuntime;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.runtime.ContainerRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;

/**
 * This class is a {@link ContainerRuntime} implementation that delegates all
 * operations to either a {@link DefaultLinuxContainerRuntime} instance or a
 * {@link DockerLinuxContainerRuntime} instance, depending on whether the
 * {@link DockerLinuxContainerRuntime} instance believes the operation to be
 * requesting a Docker container.
 *
 * @see DockerLinuxContainerRuntime#isDockerContainerRequested
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class DelegatingLinuxContainerRuntime implements LinuxContainerRuntime {
  private static final Logger LOG =
       LoggerFactory.getLogger(DelegatingLinuxContainerRuntime.class);
  private DefaultLinuxContainerRuntime defaultLinuxContainerRuntime;
  private DockerLinuxContainerRuntime dockerLinuxContainerRuntime;
  private EnumSet<LinuxContainerRuntimeConstants.RuntimeType> allowedRuntimes =
      EnumSet.noneOf(LinuxContainerRuntimeConstants.RuntimeType.class);

  @Override
  public void initialize(Configuration conf)
      throws ContainerExecutionException {
    String[] configuredRuntimes = conf.getTrimmedStrings(
        YarnConfiguration.LINUX_CONTAINER_RUNTIME_ALLOWED_RUNTIMES,
        YarnConfiguration.DEFAULT_LINUX_CONTAINER_RUNTIME_ALLOWED_RUNTIMES);
    for (String configuredRuntime : configuredRuntimes) {
      try {
        allowedRuntimes.add(
            LinuxContainerRuntimeConstants.RuntimeType.valueOf(
                configuredRuntime.toUpperCase()));
      } catch (IllegalArgumentException e) {
        throw new ContainerExecutionException("Invalid runtime set in "
            + YarnConfiguration.LINUX_CONTAINER_RUNTIME_ALLOWED_RUNTIMES + " : "
            + configuredRuntime);
      }
    }
    if (isRuntimeAllowed(LinuxContainerRuntimeConstants.RuntimeType.DOCKER)) {
      dockerLinuxContainerRuntime = new DockerLinuxContainerRuntime(
          PrivilegedOperationExecutor.getInstance(conf));
      dockerLinuxContainerRuntime.initialize(conf);
    }
    if (isRuntimeAllowed(LinuxContainerRuntimeConstants.RuntimeType.DEFAULT)) {
      defaultLinuxContainerRuntime = new DefaultLinuxContainerRuntime(
          PrivilegedOperationExecutor.getInstance(conf));
      defaultLinuxContainerRuntime.initialize(conf);
    }
  }

  @VisibleForTesting
  LinuxContainerRuntime pickContainerRuntime(
      Map<String, String> environment) throws ContainerExecutionException {
    LinuxContainerRuntime runtime;
    if (dockerLinuxContainerRuntime != null &&
        DockerLinuxContainerRuntime.isDockerContainerRequested(environment)){
      runtime = dockerLinuxContainerRuntime;
    } else if (defaultLinuxContainerRuntime != null &&
        !DockerLinuxContainerRuntime.isDockerContainerRequested(environment)) {
      runtime = defaultLinuxContainerRuntime;
    } else {
      throw new ContainerExecutionException("Requested runtime not allowed.");
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Using container runtime: " + runtime.getClass()
          .getSimpleName());
    }

    return runtime;
  }

  private LinuxContainerRuntime pickContainerRuntime(Container container)
      throws ContainerExecutionException {
    return pickContainerRuntime(container.getLaunchContext().getEnvironment());
  }

  @Override
  public void prepareContainer(ContainerRuntimeContext ctx)
      throws ContainerExecutionException {
    Container container = ctx.getContainer();
    LinuxContainerRuntime runtime = pickContainerRuntime(container);

    runtime.prepareContainer(ctx);
  }

  @Override
  public void launchContainer(ContainerRuntimeContext ctx)
      throws ContainerExecutionException {
    Container container = ctx.getContainer();
    LinuxContainerRuntime runtime = pickContainerRuntime(container);

    runtime.launchContainer(ctx);
  }

  @Override
  public void signalContainer(ContainerRuntimeContext ctx)
      throws ContainerExecutionException {
    Container container = ctx.getContainer();
    LinuxContainerRuntime runtime = pickContainerRuntime(container);

    runtime.signalContainer(ctx);
  }

  @Override
  public void reapContainer(ContainerRuntimeContext ctx)
      throws ContainerExecutionException {
    Container container = ctx.getContainer();
    LinuxContainerRuntime runtime = pickContainerRuntime(container);

    runtime.reapContainer(ctx);
  }

  @Override
  public String[] getIpAndHost(Container container)
      throws ContainerExecutionException {
    LinuxContainerRuntime runtime = pickContainerRuntime(container);
    return runtime.getIpAndHost(container);
  }

  @VisibleForTesting
  boolean isRuntimeAllowed(
      LinuxContainerRuntimeConstants.RuntimeType runtimeType) {
    return allowedRuntimes.contains(runtimeType);
  }
}