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

package io.prometheus;

import com.google.common.collect.Lists;
import okhttp3.OkHttpClient;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TestConstants {
  static final ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();
  static final OkHttpClient    OK_HTTP_CLIENT   = new OkHttpClient();
  static final Random          RANDOM           = new Random();
  static final int             REPS             = 1000;
  static final int             PROXY_PORT       = 9500;
  static final List<String>    args             = Lists.newArrayList("--config", "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/etc/test-configs/travis.conf");

  private TestConstants() {}
}