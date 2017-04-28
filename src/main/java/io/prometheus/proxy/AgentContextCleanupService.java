package io.prometheus.proxy;

import com.google.common.util.concurrent.AbstractExecutionThreadService;
import io.prometheus.Proxy;
import io.prometheus.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentContextCleanupService
    extends AbstractExecutionThreadService {

  private static final Logger logger = LoggerFactory.getLogger(AgentContextCleanupService.class);

  private final Proxy proxy;

  public AgentContextCleanupService(final Proxy proxy) {
    this.proxy = proxy;
  }

  @Override
  protected void run()
      throws Exception {
    if (this.proxy.getConfigVals().internal.staleAgentCheckEnabled) {
      final long maxInactivitySecs = this.proxy.getConfigVals().internal.maxAgentInactivitySecs;
      final long threadPauseSecs = this.proxy.getConfigVals().internal.staleAgentCheckPauseSecs;
      logger.info("Agent eviction thread started ({} secs max inactivity secs with {} secs pause)",
                  maxInactivitySecs, threadPauseSecs);

      while (this.isRunning()) {
        this.proxy.getAgentContextMap().forEach(
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
    else {
      logger.info("Agent eviction thread not started");
    }
  }
}
