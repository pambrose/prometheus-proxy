/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.common;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class MetricsConfig {
  public static MetricsConfig create(final boolean enabled,
                                     final int port,
                                     final ConfigVals.Proxy2.Metrics2 metrics) {

    return new AutoValue_MetricsConfig(enabled,
                                       port,
                                       metrics.path,
                                       metrics.standardExportsEnabled,
                                       metrics.memoryPoolsExportsEnabled,
                                       metrics.garbageCollectorExportsEnabled,
                                       metrics.threadExportsEnabled,
                                       metrics.classLoadingExportsEnabled,
                                       metrics.versionInfoExportsEnabled);
  }

  public static MetricsConfig create(final boolean enabled,
                                     final int port,
                                     final ConfigVals.Agent.Metrics metrics) {

    return new AutoValue_MetricsConfig(enabled,
                                       port,
                                       metrics.path,
                                       metrics.standardExportsEnabled,
                                       metrics.memoryPoolsExportsEnabled,
                                       metrics.garbageCollectorExportsEnabled,
                                       metrics.threadExportsEnabled,
                                       metrics.classLoadingExportsEnabled,
                                       metrics.versionInfoExportsEnabled);
  }

  public abstract boolean enabled();

  public abstract int port();

  public abstract String path();

  public abstract boolean standardExportsEnabled();

  public abstract boolean memoryPoolsExportsEnabled();

  public abstract boolean garbageCollectorExportsEnabled();

  public abstract boolean threadExportsEnabled();

  public abstract boolean classLoadingExportsEnabled();

  public abstract boolean versionInfoExportsEnabled();
}


