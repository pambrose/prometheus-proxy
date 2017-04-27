package io.prometheus.proxy;

import com.google.common.util.concurrent.Service;
import io.prometheus.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyListener
    extends Service.Listener {

  private static final Logger logger = LoggerFactory.getLogger(ProxyListener.class);

  @Override
  public void starting() {
    super.starting();
    logger.info("Starting {}", Proxy.class.getSimpleName());
  }

  @Override
  public void running() {
    super.running();
    logger.info("{} is running", Proxy.class.getSimpleName());
  }

  @Override
  public void stopping(Service.State from) {
    super.stopping(from);
    logger.info("{} is stopping", Proxy.class.getSimpleName());
  }

  @Override
  public void terminated(Service.State from) {
    super.terminated(from);
    logger.info("{} is terminated", Proxy.class.getSimpleName());
  }

  @Override
  public void failed(Service.State from, Throwable t) {
    super.failed(from, t);
    logger.info("{} failed on {}", Proxy.class.getSimpleName(), from, t);
  }
}
