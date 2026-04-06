package website;

// --8<-- [start:embedded-java]
import io.prometheus.Agent;
import io.prometheus.agent.EmbeddedAgentInfo;

public class EmbeddedAgentJavaExample {
  public static void main(String[] args) {
    // Start the agent in the background
    EmbeddedAgentInfo agentInfo =
      Agent.startAsyncAgent("agent-config.conf", true, true);

    System.out.println("Agent started: " + agentInfo.getAgentName());

    // Your application code runs here...
    // The agent runs in background threads

    // Stop the agent when your application exits
    agentInfo.shutdown();
  }
}
// --8<-- [end:embedded-java]
