package website

// --8<-- [start:embedded-kotlin]
import io.prometheus.Agent

fun main() {
  // Start the agent in the background
  val agentInfo = Agent.startAsyncAgent(
    configFilename = "agent-config.conf",
    exitOnMissingConfig = true,
  )

  println("Agent started: ${agentInfo.agentName}")

  // Your application code runs here...
  // The agent runs in background coroutines

  // Stop the agent when your application exits
  agentInfo.shutdown()
}
// --8<-- [end:embedded-kotlin]
