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

import com.google.common.util.concurrent.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericServiceListener
    extends Service.Listener {

  private static final Logger logger = LoggerFactory.getLogger(GenericServiceListener.class);

  private final Service service;

  public GenericServiceListener(Service service) {
    this.service = service;
  }

  @Override
  public void starting() {
    super.starting();
    logger.info("Starting {}", this.service);
  }

  @Override
  public void running() {
    super.running();
    logger.info("Running {}", this.service);
  }

  @Override
  public void stopping(Service.State from) {
    super.stopping(from);
    logger.info("Stopping {}", this.service);
  }

  @Override
  public void terminated(Service.State from) {
    super.terminated(from);
    logger.info("Terminated {}", this.service);
  }

  @Override
  public void failed(Service.State from, Throwable t) {
    super.failed(from, t);
    logger.info("Failed on {} {}", from, this.service, t);
  }
}
