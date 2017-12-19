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

package io.prometheus.common

import com.google.common.util.concurrent.Service
import org.slf4j.LoggerFactory

class GenericServiceListener(private val service: Service) : Service.Listener() {

    override fun starting() {
        super.starting()
        logger.info("Starting {}", this.service)
    }

    override fun running() {
        super.running()
        logger.info("Running {}", this.service)
    }

    override fun stopping(from: Service.State?) {
        super.stopping(from)
        logger.info("Stopping {}", this.service)
    }

    override fun terminated(from: Service.State?) {
        super.terminated(from)
        logger.info("Terminated {}", this.service)
    }

    override fun failed(from: Service.State?, t: Throwable?) {
        super.failed(from, t)
        logger.info("Failed on {} {}", from, this.service, t)
    }

    companion object {

        private val logger = LoggerFactory.getLogger(GenericServiceListener::class.java)
    }
}
