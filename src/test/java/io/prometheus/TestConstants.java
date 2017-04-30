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