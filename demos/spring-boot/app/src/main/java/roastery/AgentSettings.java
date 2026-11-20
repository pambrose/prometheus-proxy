package roastery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * The embedded agent's settings, bound from {@code roastery.monitoring} in application.yml, whose defaults read the
 * environment: the proxy's agent port as {@code host:port} ({@code PROMETHEUS_PROXY}), its token, the certificate the
 * proxy's is signed with, as PEM or its base64, and the name it was issued to, and the credentials for
 * {@code /actuator/prometheus}.
 */
@ConfigurationProperties("roastery.monitoring")
public record AgentSettings(
    String proxyEndpoint,
    String token,
    String certificate,
    String authority,
    String metricsUser,
    String metricsPassword) {

  /** Whether a proxy is given; without one, as on a developer's machine, the agent stays off. */
  public boolean enabled() {
    return StringUtils.hasText(proxyEndpoint);
  }

  // The token, the certificate, and the password stay out of it, so it can be logged
  @Override
  public String toString() {
    return "AgentSettings[proxyEndpoint=" + proxyEndpoint + ", token=" + shown(token)
        + ", certificate=" + shown(certificate) + ", authority=" + authority + ", metricsUser=" + metricsUser
        + ", metricsPassword=" + shown(metricsPassword) + "]";
  }

  private static String shown(String secret) {
    return StringUtils.hasText(secret) ? "(set)" : "(none)";
  }
}
