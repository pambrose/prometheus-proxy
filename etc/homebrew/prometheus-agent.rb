# Homebrew formula for the prometheus-proxy agent, published in the pambrose/homebrew-tap repository.
#
# The source is etc/homebrew/prometheus-agent.rb in pambrose/prometheus-proxy, where the version and checksum are
# placeholders. `make homebrew-formula` fills them in from a published GitHub release and writes the result to the
# tap's Formula/ directory, so make changes in that source file rather than in the tap's copy.
class PrometheusAgent < Formula
  desc "Firewall-side agent that relays Prometheus scrapes through prometheus-proxy"
  homepage "https://github.com/pambrose/prometheus-proxy"
  url "https://github.com/pambrose/prometheus-proxy/releases/download/@VERSION@/prometheus-agent.jar"
  sha256 "@SHA256@"
  license "Apache-2.0"

  livecheck do
    url :stable
    strategy :github_latest
  end

  # Java 25 is the runtime the Docker images ship and the tests run on.
  depends_on "openjdk@25"

  def install
    libexec.install "prometheus-agent.jar"

    # The JVM flags are the ones etc/docker/agent.Dockerfile passes to silence JDK 25's native-access and
    # sun.misc.Unsafe startup warnings. Older JDKs reject --sun-misc-unsafe-memory-access, so the script always uses
    # openjdk@25 rather than honoring a JAVA_HOME set for other work.
    (bin/"prometheus-agent").write <<~SHELL
      #!/bin/bash
      exec "#{Language::Java.java_home("25")}/bin/java" \\
        --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \\
        -jar "#{libexec}/prometheus-agent.jar" "$@"
    SHELL

    # The config `brew services` runs the agent with. It goes through etc.install rather than a write into etc, so an
    # upgrade keeps a copy the user has edited and puts the new default beside it as prometheus-agent.conf.default.
    (buildpath/"prometheus-agent.conf").write <<~HOCON
      // Config for `brew services start prometheus-agent`. Every setting is described in
      // https://github.com/pambrose/prometheus-proxy/blob/master/config/config.conf
      agent {
        // The proxy to connect to; its gRPC port defaults to 50051.
        proxy.hostname = localhost

        // Each entry exposes one endpoint as a path on the proxy, which Prometheus then scrapes.
        pathConfigs = [
          // {
          //   name: "My app"
          //   path: myapp_metrics
          //   url: "http://localhost:8080/metrics"
          // }
        ]
      }
    HOCON
    etc.install "prometheus-agent.conf"
  end

  def caveats
    <<~EOS
      Before starting the agent as a service, set the proxy and the endpoints to scrape in:
        #{etc}/prometheus-agent.conf
    EOS
  end

  service do
    run [opt_bin/"prometheus-agent", "--config", etc/"prometheus-agent.conf"]
    keep_alive true
    log_path var/"log/prometheus-agent.log"
    error_log_path var/"log/prometheus-agent.log"
  end

  test do
    assert_match "Version: #{version}", shell_output("#{bin}/prometheus-agent --version")
  end
end
