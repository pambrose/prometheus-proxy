package com.sudothought.common;

import io.grpc.Attributes;

public interface Constants {
  String                 AGENT_ID        = "agent-id";
  Attributes.Key<String> ATTRIB_AGENT_ID = Attributes.Key.of(AGENT_ID);
}
