package io.prometheus.common;

import com.google.common.util.concurrent.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericServiceListener
    extends Service.Listener {

  private static final Logger logger = LoggerFactory.getLogger(GenericServiceListener.class);

  private final String name;

  public GenericServiceListener(Service service) {
    this.name = service.getClass().getSimpleName();
  }

  @Override
  public void starting() {
    super.starting();
    logger.info("Starting {}", this.name);
  }

  @Override
  public void running() {
    super.running();
    logger.info("{} is running", this.name);
  }

  @Override
  public void stopping(Service.State from) {
    super.stopping(from);
    logger.info("{} is stopping", this.name);
  }

  @Override
  public void terminated(Service.State from) {
    super.terminated(from);
    logger.info("{} terminated", this.name);
  }

  @Override
  public void failed(Service.State from, Throwable t) {
    super.failed(from, t);
    logger.info("{} failed on {}", this.name, from, t);
  }
}
