package io.prometheus.proxy;

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.MoreExecutors;
import io.prometheus.Proxy;
import io.prometheus.common.GenericServiceListener;
import io.prometheus.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentContextCleanupService
    extends AbstractExecutionThreadService {

  private static final Logger logger = LoggerFactory.getLogger(AgentContextCleanupService.class);

  private final Proxy proxy;

  public AgentContextCleanupService(final Proxy proxy) {
    this.proxy = proxy;
    this.addListener(new GenericServiceListener(this), MoreExecutors.directExecutor());
  }

  @Override
  protected void run()
      throws Exception {
    final long maxInactivitySecs = this.proxy.getConfigVals().internal.maxAgentInactivitySecs;
    final long threadPauseSecs = this.proxy.getConfigVals().internal.staleAgentCheckPauseSecs;
    while (this.isRunning()) {
      this.proxy.getAgentContextMap()
                .forEach(
                    (agentId, agentContext) -> {
                      final long inactivitySecs = agentContext.inactivitySecs();
                      if (inactivitySecs > maxInactivitySecs) {
                        logger.info("Evicting agent after {} secs of inactivty {}", inactivitySecs, agentContext);
                        this.proxy.removeAgentContext(agentId);
                        this.proxy.getMetrics().agentEvictions.inc();
                      }
                    });
      Utils.sleepForSecs(threadPauseSecs);
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("max inactivity secs", this.proxy.getConfigVals().internal.maxAgentInactivitySecs)
                      .add("pause secs", this.proxy.getConfigVals().internal.staleAgentCheckPauseSecs)
                      .toString();
  }
}
