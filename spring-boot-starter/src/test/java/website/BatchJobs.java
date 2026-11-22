/*
 * Copyright © 2026 Paul Ambrose
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

package website;

import io.prometheus.metrics.model.registry.PrometheusRegistry;

/** An app's batch jobs, with a metrics registry of their own, for {@link AgentCustomizations}. */
public interface BatchJobs {
  /**
   * The jobs' registry, which isn't the app's.
   *
   * @return the registry
   */
  PrometheusRegistry registry();
}
