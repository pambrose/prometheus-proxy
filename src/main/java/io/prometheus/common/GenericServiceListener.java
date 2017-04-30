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
