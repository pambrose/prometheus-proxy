/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prometheus.guava

import com.google.common.util.concurrent.AbstractExecutionThreadService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

abstract class GenericExecutionThreadService : AbstractExecutionThreadService() {
    fun startSync(maxWaitSecs: Long = 15, timeUnit: TimeUnit = SECONDS) {
        startAsync()
        awaitRunning(maxWaitSecs, timeUnit)
    }

    fun stopSync(maxWaitSecs: Long = 15, timeUnit: TimeUnit = SECONDS) {
        stopAsync()
        awaitTerminated(maxWaitSecs, timeUnit)
    }
}