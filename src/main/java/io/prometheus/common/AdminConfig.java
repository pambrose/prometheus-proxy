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
public abstract class AdminConfig {
  public static AdminConfig create(final boolean enabled,
                                   final int port,
                                   final ConfigVals.Proxy2.Admin2 admin) {

    return new AutoValue_AdminConfig(enabled,
                                     port,
                                     admin.pingPath,
                                     admin.versionPath,
                                     admin.healthCheckPath,
                                     admin.threadDumpPath);
  }

  public static AdminConfig create(final boolean enabled,
                                   final int port,
                                   final ConfigVals.Agent.Admin admin) {

    return new AutoValue_AdminConfig(enabled,
                                     port,
                                     admin.pingPath,
                                     admin.versionPath,
                                     admin.healthCheckPath,
                                     admin.threadDumpPath);
  }

  public abstract boolean enabled();

  public abstract int port();

  public abstract String pingPath();

  public abstract String versionPath();

  public abstract String healthCheckPath();

  public abstract String threadDumpPath();
}


