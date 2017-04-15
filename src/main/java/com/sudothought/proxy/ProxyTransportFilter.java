package com.sudothought.proxy;

import com.sudothought.agent.AgentContext;
import com.sudothought.common.Constants;
import io.grpc.Attributes;
import io.grpc.ServerTransportFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class ProxyTransportFilter
    extends ServerTransportFilter {

  private static final Logger logger = LoggerFactory.getLogger(ProxyTransportFilter.class);

  private final Proxy proxy;

  public ProxyTransportFilter(Proxy proxy) {
    this.proxy = proxy;
  }

  private String getRemoteAddr(Attributes attributes) {
    final Optional<Attributes.Key<?>> key_opt = attributes.keys()
                                                          .stream()
                                                          .filter(key -> key.toString().equals("remote-addr"))
                                                          .findFirst();
    if (key_opt.isPresent()) {
      final Attributes.Key<Object> key = (Attributes.Key<Object>) key_opt.get();
      final Object val = attributes.get(key);
      if (val != null)
        return val.toString();
    }

    return "Unknown";
  }

  @Override
  public Attributes transportReady(final Attributes attributes) {
    final String remote_addr = this.getRemoteAddr(attributes);
    final AgentContext agentContext = new AgentContext(remote_addr);
    final String agentId = agentContext.getAgentId();
    this.proxy.addAgentContext(agentId, agentContext);
    logger.info("Connected to {} agent_id: {}", remote_addr, agentId);
    return Attributes.newBuilder()
                     .set(Constants.ATTRIB_AGENT_ID, agentId)
                     .setAll(attributes)
                     .build();
  }

  @Override
  public void transportTerminated(final Attributes attributes) {
    final String agentId = attributes.get(Constants.ATTRIB_AGENT_ID);
    this.proxy.removePathByAgentId(agentId);
    final AgentContext agentContext = this.proxy.removeAgentContext(agentId);
    logger.info("Disconnected from {} agent_id: {}", agentContext.getRemoteAddr(), agentId);
    super.transportTerminated(attributes);
  }
}
