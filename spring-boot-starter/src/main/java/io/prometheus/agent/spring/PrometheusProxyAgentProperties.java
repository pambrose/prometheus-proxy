/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prometheus.agent.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The embedded agent's settings, the {@code prometheus-proxy.agent.*} properties. Only {@link #getProxy() proxy} is
 * needed: without it, as on a developer's machine, the agent stays off. {@link #toString()} shows the token, the
 * passwords, and the PEMs only as set or not, so the settings can be logged.
 */
@ConfigurationProperties("prometheus-proxy.agent")
public class PrometheusProxyAgentProperties {
  /** Whether to run the agent at all; it also needs a proxy. */
  private boolean enabled = true;

  /**
   * The proxy the agent connects to, as host or host:port (the port defaults to 50051), or several, separated by
   * commas, in the order the agent fails over through them. Unset or empty, the agent stays off.
   */
  private String proxy;

  /** The token the agent presents to the proxy, which has to match the proxy's. */
  private String token;

  /** The certificate the agent trusts the proxy's certificate by, as PEM text or its base64. Turns TLS on. */
  private String trustCertificate;

  /**
   * The name the proxy's certificate was issued to, when it isn't the host the agent connects to. Used with
   * trust-certificate.
   */
  private String authority;

  /** The agent's own certificate chain, as PEM text or its base64, for a proxy that requires mutual TLS. */
  private String clientCertificate;

  /** The private key of client-certificate, as PEM text or its base64. */
  private String clientKey;

  /**
   * The agent's name, as the proxy's dashboard and logs show it. Defaults to spring.application.name, followed by the
   * build's version when the app has build info, which tells an old deployment from a new one.
   */
  private String name;

  /** The proxy's path the app's metrics are served at. Defaults to spring.application.name followed by _metrics. */
  private String path;

  /** The labels of the app's path, in the proxy's service discovery. */
  private Map<String, String> labels = new LinkedHashMap<>();

  /** What the agent serves at path: the app's PrometheusRegistry bean, or none. */
  private Source source = Source.REGISTRY;

  /** Other targets the agent scrapes over HTTP, each served at a path of its own. */
  private List<Target> paths = new ArrayList<>();

  /** Whether the agent registers its paths in consolidated mode, alongside other agents serving them. */
  private boolean consolidated;

  /** The port of the agent's own metrics endpoint (the agent_* metrics), which is off without one. */
  private Integer metricsPort;

  /** The agent's dashboard, which is off without a port. */
  private final Dashboard dashboard = new Dashboard();

  /**
   * Whether the app fails to start when the agent can't, or has no proxy, rather than logging why and running
   * without it.
   */
  private boolean required;

  /**
   * Any other agent setting, keyed by its path under agent, such as scrapeTimeoutSecs or http.clientTimeoutSecs. It
   * takes precedence over the properties above.
   */
  private Map<String, String> settings = new LinkedHashMap<>();

  /** What the agent serves at the app's path. */
  public enum Source {
    /** The app's {@code PrometheusRegistry} bean, which Spring Boot puts Micrometer's meters in. */
    REGISTRY,
    /** Nothing: the agent serves only {@code paths} and what a customizer adds. */
    NONE
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getProxy() {
    return proxy;
  }

  public void setProxy(String proxy) {
    this.proxy = proxy;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getTrustCertificate() {
    return trustCertificate;
  }

  public void setTrustCertificate(String trustCertificate) {
    this.trustCertificate = trustCertificate;
  }

  public String getAuthority() {
    return authority;
  }

  public void setAuthority(String authority) {
    this.authority = authority;
  }

  public String getClientCertificate() {
    return clientCertificate;
  }

  public void setClientCertificate(String clientCertificate) {
    this.clientCertificate = clientCertificate;
  }

  public String getClientKey() {
    return clientKey;
  }

  public void setClientKey(String clientKey) {
    this.clientKey = clientKey;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public Map<String, String> getLabels() {
    return labels;
  }

  public void setLabels(Map<String, String> labels) {
    this.labels = labels;
  }

  public Source getSource() {
    return source;
  }

  public void setSource(Source source) {
    this.source = source;
  }

  public List<Target> getPaths() {
    return paths;
  }

  public void setPaths(List<Target> paths) {
    this.paths = paths;
  }

  public boolean isConsolidated() {
    return consolidated;
  }

  public void setConsolidated(boolean consolidated) {
    this.consolidated = consolidated;
  }

  public Integer getMetricsPort() {
    return metricsPort;
  }

  public void setMetricsPort(Integer metricsPort) {
    this.metricsPort = metricsPort;
  }

  public Dashboard getDashboard() {
    return dashboard;
  }

  public boolean isRequired() {
    return required;
  }

  public void setRequired(boolean required) {
    this.required = required;
  }

  public Map<String, String> getSettings() {
    return settings;
  }

  public void setSettings(Map<String, String> settings) {
    this.settings = settings;
  }

  @Override
  public String toString() {
    return "PrometheusProxyAgentProperties[enabled=" + enabled + ", proxy=" + proxy + ", token=" + shown(token)
        + ", trustCertificate=" + shown(trustCertificate) + ", authority=" + authority
        + ", clientCertificate=" + shown(clientCertificate) + ", clientKey=" + shown(clientKey) + ", name=" + name
        + ", path=" + path + ", labels=" + labels + ", source=" + source + ", paths=" + paths
        + ", consolidated=" + consolidated + ", metricsPort=" + metricsPort + ", dashboard=" + dashboard
        + ", required=" + required + ", settings=" + settings.keySet() + "]";
  }

  // A secret as toString shows it
  static String shown(String secret) {
    return secret == null || secret.isBlank() ? "(none)" : "(set)";
  }

  /** The agent's dashboard: its connection, each path and why the proxy refused one, its activity and settings. */
  public static class Dashboard {
    /** The port the dashboard listens on; it's off without one. */
    private Integer port;

    /** The address the dashboard listens on, such as 127.0.0.1 to keep it local; every interface by default. */
    private String host;

    /** The username of the dashboard's login, which password turns on. */
    private String username = "admin";

    /** Requires a login to the dashboard, username and this, from its login page or as Basic Auth. */
    private String password;

    public Integer getPort() {
      return port;
    }

    public void setPort(Integer port) {
      this.port = port;
    }

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    @Override
    public String toString() {
      return "Dashboard[port=" + port + ", host=" + host + ", username=" + username + ", password="
          + shown(password) + "]";
    }
  }

  /** A target the agent scrapes over HTTP, served to Prometheus at the proxy's path. */
  public static class Target {
    /** The proxy's path the target's metrics are served at. */
    private String path;

    /** The target's metrics URL. */
    private String url;

    /** The username the agent sends the target as Basic Auth. */
    private String username;

    /** The password the agent sends the target as Basic Auth. */
    private String password;

    /** The path's labels, in the proxy's service discovery. */
    private Map<String, String> labels = new LinkedHashMap<>();

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getUsername() {
      return username;
    }

    public void setUsername(String username) {
      this.username = username;
    }

    public String getPassword() {
      return password;
    }

    public void setPassword(String password) {
      this.password = password;
    }

    public Map<String, String> getLabels() {
      return labels;
    }

    public void setLabels(Map<String, String> labels) {
      this.labels = labels;
    }

    @Override
    public String toString() {
      // A URL's own credentials masked, as the agent's logs mask them
      String shownUrl = url == null ? null : url.replaceFirst("://[^/@]*@", "://***@");
      return "Target[path=" + path + ", url=" + shownUrl + ", username=" + username + ", password="
          + shown(password) + ", labels=" + labels + "]";
    }
  }
}
