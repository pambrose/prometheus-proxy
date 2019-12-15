// generated by tscfg 0.9.95 on Sat Dec 14 17:52:58 PST 2019
// source: etc/config/config.conf

package io.prometheus.common;

public class ConfigVals {
  public final ConfigVals.Agent agent;
  public final ConfigVals.Proxy2 proxy;
  public ConfigVals(com.typesafe.config.Config c) {
    final $TsCfgValidator $tsCfgValidator = new $TsCfgValidator();
    final java.lang.String parentPath = "";
    this.agent = c.hasPathOrNull("agent") ? new ConfigVals.Agent(c.getConfig("agent"), parentPath + "agent.", $tsCfgValidator) : new ConfigVals.Agent(com.typesafe.config.ConfigFactory.parseString("agent{}"), parentPath + "agent.", $tsCfgValidator);
    this.proxy = c.hasPathOrNull("proxy") ? new ConfigVals.Proxy2(c.getConfig("proxy"), parentPath + "proxy.", $tsCfgValidator) : new ConfigVals.Proxy2(com.typesafe.config.ConfigFactory.parseString("proxy{}"), parentPath + "proxy.", $tsCfgValidator);
    $tsCfgValidator.validate();
  }

  public static class Agent {
    public final Agent.Admin admin;
    public final Agent.Internal internal;
    public final int maxContentSizeKbs;
    public final Agent.Metrics metrics;
    public final java.lang.String name;
    public final java.util.List<Agent.PathConfigs$Elm> pathConfigs;
    public final Agent.Proxy proxy;
    public final Agent.Tls tls;
    public Agent(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
      this.admin = c.hasPathOrNull("admin") ? new Agent.Admin(c.getConfig("admin"), parentPath + "admin.", $tsCfgValidator) : new Agent.Admin(com.typesafe.config.ConfigFactory.parseString("admin{}"), parentPath + "admin.", $tsCfgValidator);
      this.internal = c.hasPathOrNull("internal") ? new Agent.Internal(c.getConfig("internal"), parentPath + "internal.", $tsCfgValidator) : new Agent.Internal(com.typesafe.config.ConfigFactory.parseString("internal{}"), parentPath + "internal.", $tsCfgValidator);
      this.maxContentSizeKbs = c.hasPathOrNull("maxContentSizeKbs") ? c.getInt("maxContentSizeKbs") : 32;
      this.metrics = c.hasPathOrNull("metrics") ? new Agent.Metrics(c.getConfig("metrics"), parentPath + "metrics.", $tsCfgValidator) : new Agent.Metrics(com.typesafe.config.ConfigFactory.parseString("metrics{}"), parentPath + "metrics.", $tsCfgValidator);
      this.name = c.hasPathOrNull("name") ? c.getString("name") : "";
      this.pathConfigs = $_LAgent_PathConfigs$Elm(c.getList("pathConfigs"), parentPath, $tsCfgValidator);
      this.proxy = c.hasPathOrNull("proxy") ? new Agent.Proxy(c.getConfig("proxy"), parentPath + "proxy.", $tsCfgValidator) : new Agent.Proxy(com.typesafe.config.ConfigFactory.parseString("proxy{}"), parentPath + "proxy.", $tsCfgValidator);
      this.tls = c.hasPathOrNull("tls") ? new Agent.Tls(c.getConfig("tls"), parentPath + "tls.", $tsCfgValidator) : new Agent.Tls(com.typesafe.config.ConfigFactory.parseString("tls{}"), parentPath + "tls.", $tsCfgValidator);
    }

    private static java.util.List<Agent.PathConfigs$Elm> $_LAgent_PathConfigs$Elm(com.typesafe.config.ConfigList cl, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
      java.util.ArrayList<Agent.PathConfigs$Elm> al = new java.util.ArrayList<>();
      for (com.typesafe.config.ConfigValue cv : cl) {
        al.add(new Agent.PathConfigs$Elm(((com.typesafe.config.ConfigObject) cv).toConfig(), parentPath, $tsCfgValidator));
      }
      return java.util.Collections.unmodifiableList(al);
    }

    public static class Admin {
      public final boolean debugEnabled;
      public final boolean enabled;
      public final java.lang.String healthCheckPath;
      public final java.lang.String pingPath;
      public final int port;
      public final java.lang.String threadDumpPath;
      public final java.lang.String versionPath;

      public Admin(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.debugEnabled = c.hasPathOrNull("debugEnabled") && c.getBoolean("debugEnabled");
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.healthCheckPath = c.hasPathOrNull("healthCheckPath") ? c.getString("healthCheckPath") : "healthcheck";
        this.pingPath = c.hasPathOrNull("pingPath") ? c.getString("pingPath") : "ping";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8093;
        this.threadDumpPath = c.hasPathOrNull("threadDumpPath") ? c.getString("threadDumpPath") : "threaddump";
        this.versionPath = c.hasPathOrNull("versionPath") ? c.getString("versionPath") : "version";
      }
    }

    public static class Internal {
      public final int heartbeatCheckPauseMillis;
      public final boolean heartbeatEnabled;
      public final int heartbeatMaxInactivitySecs;
      public final int reconnectPauseSecs;
      public final int scrapeRequestBacklogUnhealthySize;
      public final Internal.Zipkin zipkin;
      public static class Zipkin {
        public final boolean enabled;
        public final boolean grpcReportingEnabled;
        public final java.lang.String hostname;
        public final java.lang.String path;
        public final int port;
        public final java.lang.String serviceName;

        public Zipkin(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
          this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
          this.grpcReportingEnabled = c.hasPathOrNull("grpcReportingEnabled") && c.getBoolean("grpcReportingEnabled");
          this.hostname = c.hasPathOrNull("hostname") ? c.getString("hostname") : "localhost";
          this.path = c.hasPathOrNull("path") ? c.getString("path") : "api/v2/spans";
          this.port = c.hasPathOrNull("port") ? c.getInt("port") : 9411;
          this.serviceName = c.hasPathOrNull("serviceName") ? c.getString("serviceName") : "prometheus-agent";
        }
      }

      public Internal(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.heartbeatCheckPauseMillis = c.hasPathOrNull("heartbeatCheckPauseMillis") ? c.getInt("heartbeatCheckPauseMillis") : 500;
        this.heartbeatEnabled = !c.hasPathOrNull("heartbeatEnabled") || c.getBoolean("heartbeatEnabled");
        this.heartbeatMaxInactivitySecs = c.hasPathOrNull("heartbeatMaxInactivitySecs") ? c.getInt("heartbeatMaxInactivitySecs") : 5;
        this.reconnectPauseSecs = c.hasPathOrNull("reconnectPauseSecs") ? c.getInt("reconnectPauseSecs") : 3;
        this.scrapeRequestBacklogUnhealthySize = c.hasPathOrNull("scrapeRequestBacklogUnhealthySize") ? c.getInt("scrapeRequestBacklogUnhealthySize") : 25;
        this.zipkin = c.hasPathOrNull("zipkin") ? new Internal.Zipkin(c.getConfig("zipkin"), parentPath + "zipkin.", $tsCfgValidator) : new Internal.Zipkin(com.typesafe.config.ConfigFactory.parseString("zipkin{}"), parentPath + "zipkin.", $tsCfgValidator);
      }
    }

    public static class Metrics {
      public final boolean classLoadingExportsEnabled;
      public final boolean enabled;
      public final boolean garbageCollectorExportsEnabled;
      public final Metrics.Grpc grpc;
      public final boolean memoryPoolsExportsEnabled;
      public final java.lang.String path;
      public final int port;
      public final boolean standardExportsEnabled;
      public final boolean threadExportsEnabled;
      public final boolean versionInfoExportsEnabled;

      public static class Grpc {
        public final boolean allMetricsReported;
        public final boolean metricsEnabled;

        public Grpc(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
          this.allMetricsReported = c.hasPathOrNull("allMetricsReported") && c.getBoolean("allMetricsReported");
          this.metricsEnabled = c.hasPathOrNull("metricsEnabled") && c.getBoolean("metricsEnabled");
        }
      }

      public Metrics(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.classLoadingExportsEnabled = c.hasPathOrNull("classLoadingExportsEnabled") && c.getBoolean("classLoadingExportsEnabled");
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.garbageCollectorExportsEnabled = c.hasPathOrNull("garbageCollectorExportsEnabled") && c.getBoolean("garbageCollectorExportsEnabled");
        this.grpc = c.hasPathOrNull("grpc") ? new Metrics.Grpc(c.getConfig("grpc"), parentPath + "grpc.", $tsCfgValidator) : new Metrics.Grpc(com.typesafe.config.ConfigFactory.parseString("grpc{}"), parentPath + "grpc.", $tsCfgValidator);
        this.memoryPoolsExportsEnabled = c.hasPathOrNull("memoryPoolsExportsEnabled") && c.getBoolean("memoryPoolsExportsEnabled");
        this.path = c.hasPathOrNull("path") ? c.getString("path") : "metrics";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8083;
        this.standardExportsEnabled = c.hasPathOrNull("standardExportsEnabled") && c.getBoolean("standardExportsEnabled");
        this.threadExportsEnabled = c.hasPathOrNull("threadExportsEnabled") && c.getBoolean("threadExportsEnabled");
        this.versionInfoExportsEnabled = c.hasPathOrNull("versionInfoExportsEnabled") && c.getBoolean("versionInfoExportsEnabled");
      }
    }

    public static class PathConfigs$Elm {
      public final java.lang.String name;
      public final java.lang.String path;
      public final java.lang.String url;

      public PathConfigs$Elm(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.name = $_reqStr(parentPath, c, "name", $tsCfgValidator);
        this.path = $_reqStr(parentPath, c, "path", $tsCfgValidator);
        this.url = $_reqStr(parentPath, c, "url", $tsCfgValidator);
      }
      private static java.lang.String $_reqStr(java.lang.String parentPath, com.typesafe.config.Config c, java.lang.String path, $TsCfgValidator $tsCfgValidator) {
        if (c == null) return null;
        try {
          return c.getString(path);
        }
        catch (com.typesafe.config.ConfigException e) {
          $tsCfgValidator.addBadPath(parentPath + path, e);
          return null;
        }
      }

    }

    public static class Proxy {
      public final java.lang.String hostname;
      public final int port;

      public Proxy(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.hostname = c.hasPathOrNull("hostname") ? c.getString("hostname") : "localhost";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 50051;
      }
    }

    public static class Tls {
      public final java.lang.String certChainFilePath;
      public final java.lang.String overrideAuthority;
      public final java.lang.String privateKeyFilePath;
      public final java.lang.String trustCertCollectionFilePath;

      public Tls(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.certChainFilePath = c.hasPathOrNull("certChainFilePath") ? c.getString("certChainFilePath") : "";
        this.overrideAuthority = c.hasPathOrNull("overrideAuthority") ? c.getString("overrideAuthority") : "";
        this.privateKeyFilePath = c.hasPathOrNull("privateKeyFilePath") ? c.getString("privateKeyFilePath") : "";
        this.trustCertCollectionFilePath = c.hasPathOrNull("trustCertCollectionFilePath") ? c.getString("trustCertCollectionFilePath") : "";
      }
    }
  }

  public static class Proxy2 {
    public final Proxy2.Admin2 admin;
    public final Proxy2.Agent2 agent;
    public final Proxy2.Http http;
    public final Proxy2.Internal2 internal;
    public final Proxy2.Metrics2 metrics;
    public final Proxy2.Tls2 tls;
    public Proxy2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
      this.admin = c.hasPathOrNull("admin") ? new Proxy2.Admin2(c.getConfig("admin"), parentPath + "admin.", $tsCfgValidator) : new Proxy2.Admin2(com.typesafe.config.ConfigFactory.parseString("admin{}"), parentPath + "admin.", $tsCfgValidator);
      this.agent = c.hasPathOrNull("agent") ? new Proxy2.Agent2(c.getConfig("agent"), parentPath + "agent.", $tsCfgValidator) : new Proxy2.Agent2(com.typesafe.config.ConfigFactory.parseString("agent{}"), parentPath + "agent.", $tsCfgValidator);
      this.http = c.hasPathOrNull("http") ? new Proxy2.Http(c.getConfig("http"), parentPath + "http.", $tsCfgValidator) : new Proxy2.Http(com.typesafe.config.ConfigFactory.parseString("http{}"), parentPath + "http.", $tsCfgValidator);
      this.internal = c.hasPathOrNull("internal") ? new Proxy2.Internal2(c.getConfig("internal"), parentPath + "internal.", $tsCfgValidator) : new Proxy2.Internal2(com.typesafe.config.ConfigFactory.parseString("internal{}"), parentPath + "internal.", $tsCfgValidator);
      this.metrics = c.hasPathOrNull("metrics") ? new Proxy2.Metrics2(c.getConfig("metrics"), parentPath + "metrics.", $tsCfgValidator) : new Proxy2.Metrics2(com.typesafe.config.ConfigFactory.parseString("metrics{}"), parentPath + "metrics.", $tsCfgValidator);
      this.tls = c.hasPathOrNull("tls") ? new Proxy2.Tls2(c.getConfig("tls"), parentPath + "tls.", $tsCfgValidator) : new Proxy2.Tls2(com.typesafe.config.ConfigFactory.parseString("tls{}"), parentPath + "tls.", $tsCfgValidator);
    }

    public static class Admin2 {
      public final boolean debugEnabled;
      public final boolean enabled;
      public final java.lang.String healthCheckPath;
      public final java.lang.String pingPath;
      public final int port;
      public final int recentRequestsQueueSize;
      public final java.lang.String threadDumpPath;
      public final java.lang.String versionPath;

      public Admin2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.debugEnabled = c.hasPathOrNull("debugEnabled") && c.getBoolean("debugEnabled");
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.healthCheckPath = c.hasPathOrNull("healthCheckPath") ? c.getString("healthCheckPath") : "healthcheck";
        this.pingPath = c.hasPathOrNull("pingPath") ? c.getString("pingPath") : "ping";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8092;
        this.recentRequestsQueueSize = c.hasPathOrNull("recentRequestsQueueSize") ? c.getInt("recentRequestsQueueSize") : 50;
        this.threadDumpPath = c.hasPathOrNull("threadDumpPath") ? c.getString("threadDumpPath") : "threaddump";
        this.versionPath = c.hasPathOrNull("versionPath") ? c.getString("versionPath") : "version";
      }
    }

    public static class Agent2 {
      public final int port;

      public Agent2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 50051;
      }
    }

    public static class Http {
      public final int idleTimeoutSecs;
      public final int maxThreads;
      public final int minThreads;
      public final int port;

      public Http(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.idleTimeoutSecs = c.hasPathOrNull("idleTimeoutSecs") ? c.getInt("idleTimeoutSecs") : 45;
        this.maxThreads = c.hasPathOrNull("maxThreads") ? c.getInt("maxThreads") : -1;
        this.minThreads = c.hasPathOrNull("minThreads") ? c.getInt("minThreads") : -1;
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8080;
      }
    }

    public static class Internal2 {
      public final Internal2.Blitz blitz;
      public final int maxAgentInactivitySecs;
      public final int scrapeRequestBacklogUnhealthySize;
      public final int scrapeRequestCheckMillis;
      public final int scrapeRequestMapUnhealthySize;
      public final int scrapeRequestTimeoutSecs;
      public final boolean staleAgentCheckEnabled;
      public final int staleAgentCheckPauseSecs;
      public final Internal2.Zipkin2 zipkin;
      public static class Blitz {
        public final boolean enabled;
        public final java.lang.String path;

        public Blitz(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
          this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
          this.path = c.hasPathOrNull("path") ? c.getString("path") : "mu-dd4bffbb-ff2e9926-5a80952c-1c6cb64d.txt";
        }
      }

      public static class Zipkin2 {
        public final boolean enabled;
        public final boolean grpcReportingEnabled;
        public final java.lang.String hostname;
        public final java.lang.String path;
        public final int port;
        public final java.lang.String serviceName;

        public Zipkin2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
          this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
          this.grpcReportingEnabled = c.hasPathOrNull("grpcReportingEnabled") && c.getBoolean("grpcReportingEnabled");
          this.hostname = c.hasPathOrNull("hostname") ? c.getString("hostname") : "localhost";
          this.path = c.hasPathOrNull("path") ? c.getString("path") : "api/v2/spans";
          this.port = c.hasPathOrNull("port") ? c.getInt("port") : 9411;
          this.serviceName = c.hasPathOrNull("serviceName") ? c.getString("serviceName") : "prometheus-proxy";
        }
      }

      public Internal2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.blitz = c.hasPathOrNull("blitz") ? new Internal2.Blitz(c.getConfig("blitz"), parentPath + "blitz.", $tsCfgValidator) : new Internal2.Blitz(com.typesafe.config.ConfigFactory.parseString("blitz{}"), parentPath + "blitz.", $tsCfgValidator);
        this.maxAgentInactivitySecs = c.hasPathOrNull("maxAgentInactivitySecs") ? c.getInt("maxAgentInactivitySecs") : 15;
        this.scrapeRequestBacklogUnhealthySize = c.hasPathOrNull("scrapeRequestBacklogUnhealthySize") ? c.getInt("scrapeRequestBacklogUnhealthySize") : 25;
        this.scrapeRequestCheckMillis = c.hasPathOrNull("scrapeRequestCheckMillis") ? c.getInt("scrapeRequestCheckMillis") : 500;
        this.scrapeRequestMapUnhealthySize = c.hasPathOrNull("scrapeRequestMapUnhealthySize") ? c.getInt("scrapeRequestMapUnhealthySize") : 25;
        this.scrapeRequestTimeoutSecs = c.hasPathOrNull("scrapeRequestTimeoutSecs") ? c.getInt("scrapeRequestTimeoutSecs") : 5;
        this.staleAgentCheckEnabled = !c.hasPathOrNull("staleAgentCheckEnabled") || c.getBoolean("staleAgentCheckEnabled");
        this.staleAgentCheckPauseSecs = c.hasPathOrNull("staleAgentCheckPauseSecs") ? c.getInt("staleAgentCheckPauseSecs") : 10;
        this.zipkin = c.hasPathOrNull("zipkin") ? new Internal2.Zipkin2(c.getConfig("zipkin"), parentPath + "zipkin.", $tsCfgValidator) : new Internal2.Zipkin2(com.typesafe.config.ConfigFactory.parseString("zipkin{}"), parentPath + "zipkin.", $tsCfgValidator);
      }
    }

    public static class Metrics2 {
      public final boolean classLoadingExportsEnabled;
      public final boolean enabled;
      public final boolean garbageCollectorExportsEnabled;
      public final Metrics2.Grpc2 grpc;
      public final boolean memoryPoolsExportsEnabled;
      public final java.lang.String path;
      public final int port;
      public final boolean standardExportsEnabled;
      public final boolean threadExportsEnabled;
      public final boolean versionInfoExportsEnabled;

      public static class Grpc2 {
        public final boolean allMetricsReported;
        public final boolean metricsEnabled;

        public Grpc2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
          this.allMetricsReported = c.hasPathOrNull("allMetricsReported") && c.getBoolean("allMetricsReported");
          this.metricsEnabled = c.hasPathOrNull("metricsEnabled") && c.getBoolean("metricsEnabled");
        }
      }

      public Metrics2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.classLoadingExportsEnabled = c.hasPathOrNull("classLoadingExportsEnabled") && c.getBoolean("classLoadingExportsEnabled");
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.garbageCollectorExportsEnabled = c.hasPathOrNull("garbageCollectorExportsEnabled") && c.getBoolean("garbageCollectorExportsEnabled");
        this.grpc = c.hasPathOrNull("grpc") ? new Metrics2.Grpc2(c.getConfig("grpc"), parentPath + "grpc.", $tsCfgValidator) : new Metrics2.Grpc2(com.typesafe.config.ConfigFactory.parseString("grpc{}"), parentPath + "grpc.", $tsCfgValidator);
        this.memoryPoolsExportsEnabled = c.hasPathOrNull("memoryPoolsExportsEnabled") && c.getBoolean("memoryPoolsExportsEnabled");
        this.path = c.hasPathOrNull("path") ? c.getString("path") : "metrics";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8082;
        this.standardExportsEnabled = c.hasPathOrNull("standardExportsEnabled") && c.getBoolean("standardExportsEnabled");
        this.threadExportsEnabled = c.hasPathOrNull("threadExportsEnabled") && c.getBoolean("threadExportsEnabled");
        this.versionInfoExportsEnabled = c.hasPathOrNull("versionInfoExportsEnabled") && c.getBoolean("versionInfoExportsEnabled");
      }
    }

    public static class Tls2 {
      public final java.lang.String certChainFilePath;
      public final java.lang.String privateKeyFilePath;
      public final java.lang.String trustCertCollectionFilePath;

      public Tls2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.certChainFilePath = c.hasPathOrNull("certChainFilePath") ? c.getString("certChainFilePath") : "";
        this.privateKeyFilePath = c.hasPathOrNull("privateKeyFilePath") ? c.getString("privateKeyFilePath") : "";
        this.trustCertCollectionFilePath = c.hasPathOrNull("trustCertCollectionFilePath") ? c.getString("trustCertCollectionFilePath") : "";
      }
    }
  }

  private static final class $TsCfgValidator {
    private final java.util.List<java.lang.String> badPaths = new java.util.ArrayList<>();

    void addBadPath(java.lang.String path, com.typesafe.config.ConfigException e) {
      badPaths.add("'" + path + "': " + e.getClass().getName() + "(" + e.getMessage() + ")");
    }

    void validate() {
      if (!badPaths.isEmpty()) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder("Invalid configuration:");
        for (java.lang.String path : badPaths) {
          sb.append("\n    ").append(path);
        }
        throw new com.typesafe.config.ConfigException(sb.toString()) {
        };
      }
    }
  }
}
      
