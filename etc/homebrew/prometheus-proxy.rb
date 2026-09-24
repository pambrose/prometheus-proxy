# Homebrew formula for the proxy (prometheus-proxy.jar), published in the pambrose/homebrew-tap repository.
#
# The source is etc/homebrew/prometheus-proxy.rb in pambrose/prometheus-proxy, where the version and checksum are
# placeholders. `make homebrew-formulae` fills them in from a published GitHub release and writes the result to the
# tap's Formula/ directory, so make changes in that source file rather than in the tap's copy.
class PrometheusProxy < Formula
  desc "Proxy that lets Prometheus scrape endpoints behind a firewall through agents"
  homepage "https://github.com/pambrose/prometheus-proxy"
  url "https://github.com/pambrose/prometheus-proxy/releases/download/@VERSION@/prometheus-proxy.jar"
  sha256 "@SHA256@"
  license "Apache-2.0"

  livecheck do
    url :stable
    strategy :github_latest
  end

  # Java 25 is the runtime the Docker images ship and the tests run on.
  depends_on "openjdk@25"

  def install
    libexec.install "prometheus-proxy.jar"

    # The JVM flags are the ones etc/docker/proxy.Dockerfile passes to silence JDK 25's native-access and
    # sun.misc.Unsafe startup warnings. Older JDKs reject --sun-misc-unsafe-memory-access, so the script always uses
    # openjdk@25 rather than honoring a JAVA_HOME set for other work.
    (bin/"prometheus-proxy").write <<~SHELL
      #!/bin/bash
      exec "#{Language::Java.java_home("25")}/bin/java" \\
        --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \\
        -jar "#{libexec}/prometheus-proxy.jar" "$@"
    SHELL

    # The config `brew services` runs the proxy with. It goes through etc.install rather than a write into etc, so an
    # upgrade keeps a copy the user has edited and puts the new default beside it as prometheus-proxy.conf.default.
    (buildpath/"prometheus-proxy.conf").write <<~HOCON
      // Config for `brew services start prometheus-proxy`. Every setting is described in
      // https://github.com/pambrose/prometheus-proxy/blob/master/config/config.conf
      proxy {
        // The port Prometheus scrapes, and the port agents connect to.
        http.port = 8080
        agent.port = 50051

        // Until agents must authenticate (agentToken, per-agent auth, or mutual TLS), any peer that can reach
        // agent.port can register as an agent. See https://pambrose.github.io/prometheus-proxy/security/
      }
    HOCON
    etc.install "prometheus-proxy.conf"
  end

  def caveats
    <<~EOS
      Prometheus scrapes the proxy on port 8080 and agents connect to it on port 50051. Until agents
      must authenticate, any peer that can reach port 50051 can register as an agent. Set the ports
      and agent authentication for the service in:
        #{etc}/prometheus-proxy.conf
    EOS
  end

  service do
    run [opt_bin/"prometheus-proxy", "--config", etc/"prometheus-proxy.conf"]
    keep_alive true
    log_path var/"log/prometheus-proxy.log"
    error_log_path var/"log/prometheus-proxy.log"
  end

  test do
    assert_match "Version: #{version}", shell_output("#{bin}/prometheus-proxy --version")
  end
end
