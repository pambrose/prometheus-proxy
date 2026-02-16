/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prometheus.harness

enum class HarnessConfig(
  val httpServerCount: Int,
  val pathCount: Int,
  val sequentialQueryCount: Int,
  val parallelQueryCount: Int,
  val concurrentClients: Int,
  val addRemoveReps: Int,
) {
  MINI(
    httpServerCount = 1,
    pathCount = 1,
    sequentialQueryCount = 1,
    parallelQueryCount = 1,
    concurrentClients = 1,
    addRemoveReps = 1,
  ),
  SMALL(
    httpServerCount = 3,
    pathCount = 10,
    sequentialQueryCount = 5,
    parallelQueryCount = 2,
    concurrentClients = 5,
    addRemoveReps = 5,
  ),
  MEDIUM(
    httpServerCount = 5,
    pathCount = 50,
    sequentialQueryCount = 1000,
    parallelQueryCount = 10,
    concurrentClients = 100,
    addRemoveReps = 1000,
  ),
  LARGE(
    httpServerCount = 10,
    pathCount = 100,
    sequentialQueryCount = 2000,
    parallelQueryCount = 20,
    concurrentClients = 200,
    addRemoveReps = 3000,
  ),
  XLARGE1(
    httpServerCount = 20,
    pathCount = 250,
    sequentialQueryCount = 5000,
    parallelQueryCount = 30,
    concurrentClients = 300,
    addRemoveReps = 5000,
  ),
  XLARGE2(
    httpServerCount = 40,
    pathCount = 500,
    sequentialQueryCount = 10000,
    parallelQueryCount = 50,
    concurrentClients = 500,
    addRemoveReps = 10000,
  ),
}
