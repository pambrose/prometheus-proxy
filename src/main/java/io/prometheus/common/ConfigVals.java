// generated by tscfg 1.2.4 on Fri Feb 14 13:29:51 PST 2025
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

    /**
     * See: https://github.com/grpc/grpc.github.io/issues/371
     * Threshold for chunking data to Proxy and buffer size
     */
    public final int chunkContentSizeKbs;
    public final boolean consolidated;
    public final Agent.Grpc grpc;
    public final Agent.Http http;
    public final Agent.Internal internal;
    public final Agent.Metrics metrics;

    /**
     * Minimum size for content to be gzipped
     */
    public final int minGzipSizeBytes;

    /**
     * Agent name used in metrics reporting
     */
    public final java.lang.String name;
    public final java.util.List<Agent.PathConfigs$Elm> pathConfigs;
    public final Agent.Proxy proxy;

    /**
     * Maximum scrape retries (0 disables scrape retries)
     */
    public final int scrapeMaxRetries;

    /**
     * Scrape timeout time in seconds
     */
    public final int scrapeTimeoutSecs;
    public final Agent.Tls tls;

    /**
     * Assign to true if using nginx as a reverse proxy
     */
    public final boolean transportFilterDisabled;
    public static class Admin {

      /**
       * Enable agent debug servlet on admin port
       */
      public final boolean debugEnabled;

      /**
       * Enable Admin servlets
       */
      public final boolean enabled;

      /**
       * HealthCheck servlet path
       */
      public final java.lang.String healthCheckPath;

      /**
       * Ping servlet path
       */
      public final java.lang.String pingPath;

      /**
       * Admin servlets port
       */
      public final int port;

      /**
       * ThreadDump servlet path
       */
      public final java.lang.String threadDumpPath;

      /**
       * Version servlet path
       */
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

    public static class Grpc {
      public final long keepAliveTimeSecs;
      public final long keepAliveTimeoutSecs;
      public final boolean keepAliveWithoutCalls;

      public Grpc(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.keepAliveTimeSecs = c.hasPathOrNull("keepAliveTimeSecs") ? c.getLong("keepAliveTimeSecs") : -1;
        this.keepAliveTimeoutSecs = c.hasPathOrNull("keepAliveTimeoutSecs") ? c.getLong("keepAliveTimeoutSecs") : -1;
        this.keepAliveWithoutCalls = c.hasPathOrNull("keepAliveWithoutCalls") && c.getBoolean("keepAliveWithoutCalls");
      }
    }

    public static class Http {

      /**
       * Enabling will disable SSL verification for agent https endpoints
       */
      public final boolean enableTrustAllX509Certificates;

      public Http(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.enableTrustAllX509Certificates = c.hasPathOrNull("enableTrustAllX509Certificates") && c.getBoolean("enableTrustAllX509Certificates");
      }
    }

    public Agent(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
      this.admin = c.hasPathOrNull("admin") ? new Agent.Admin(c.getConfig("admin"), parentPath + "admin.", $tsCfgValidator) : new Agent.Admin(com.typesafe.config.ConfigFactory.parseString("admin{}"), parentPath + "admin.", $tsCfgValidator);
      this.chunkContentSizeKbs = c.hasPathOrNull("chunkContentSizeKbs") ? c.getInt("chunkContentSizeKbs") : 32;
      this.consolidated = c.hasPathOrNull("consolidated") && c.getBoolean("consolidated");
      this.grpc = c.hasPathOrNull("grpc") ? new Agent.Grpc(c.getConfig("grpc"), parentPath + "grpc.", $tsCfgValidator) : new Agent.Grpc(com.typesafe.config.ConfigFactory.parseString("grpc{}"), parentPath + "grpc.", $tsCfgValidator);
      this.http = c.hasPathOrNull("http") ? new Agent.Http(c.getConfig("http"), parentPath + "http.", $tsCfgValidator) : new Agent.Http(com.typesafe.config.ConfigFactory.parseString("http{}"), parentPath + "http.", $tsCfgValidator);
      this.internal = c.hasPathOrNull("internal") ? new Agent.Internal(c.getConfig("internal"), parentPath + "internal.", $tsCfgValidator) : new Agent.Internal(com.typesafe.config.ConfigFactory.parseString("internal{}"), parentPath + "internal.", $tsCfgValidator);
      this.metrics = c.hasPathOrNull("metrics") ? new Agent.Metrics(c.getConfig("metrics"), parentPath + "metrics.", $tsCfgValidator) : new Agent.Metrics(com.typesafe.config.ConfigFactory.parseString("metrics{}"), parentPath + "metrics.", $tsCfgValidator);
      this.minGzipSizeBytes = c.hasPathOrNull("minGzipSizeBytes") ? c.getInt("minGzipSizeBytes") : 512;
      this.name = c.hasPathOrNull("name") ? c.getString("name") : "";
      this.pathConfigs = $_LAgent_PathConfigs$Elm(c.getList("pathConfigs"), parentPath, $tsCfgValidator);
      this.proxy = c.hasPathOrNull("proxy") ? new Agent.Proxy(c.getConfig("proxy"), parentPath + "proxy.", $tsCfgValidator) : new Agent.Proxy(com.typesafe.config.ConfigFactory.parseString("proxy{}"), parentPath + "proxy.", $tsCfgValidator);
      this.scrapeMaxRetries = c.hasPathOrNull("scrapeMaxRetries") ? c.getInt("scrapeMaxRetries") : 0;
      this.scrapeTimeoutSecs = c.hasPathOrNull("scrapeTimeoutSecs") ? c.getInt("scrapeTimeoutSecs") : 15;
      this.tls = c.hasPathOrNull("tls") ? new Agent.Tls(c.getConfig("tls"), parentPath + "tls.", $tsCfgValidator) : new Agent.Tls(com.typesafe.config.ConfigFactory.parseString("tls{}"), parentPath + "tls.", $tsCfgValidator);
      this.transportFilterDisabled = c.hasPathOrNull("transportFilterDisabled") && c.getBoolean("transportFilterDisabled");
    }

    public static class Internal {

      /**
       * CIO engine request timeout in seconds
       */
      public final int cioTimeoutSecs;

      /**
       * Pause interval when checking for inactivity
       */
      public final int heartbeatCheckPauseMillis;
      public final boolean heartbeatEnabled;

      /**
       * Max inactivity before heartbeat sent in seconds
       */
      public final int heartbeatMaxInactivitySecs;

      /**
       * Pause interval between connect attempts in seconds
       */
      public final int reconnectPauseSecs;

      /**
       * Threshold for returning an unhealthy healthcheck
       */
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
        this.cioTimeoutSecs = c.hasPathOrNull("cioTimeoutSecs") ? c.getInt("cioTimeoutSecs") : 90;
        this.heartbeatCheckPauseMillis = c.hasPathOrNull("heartbeatCheckPauseMillis") ? c.getInt("heartbeatCheckPauseMillis") : 500;
        this.heartbeatEnabled = !c.hasPathOrNull("heartbeatEnabled") || c.getBoolean("heartbeatEnabled");
        this.heartbeatMaxInactivitySecs = c.hasPathOrNull("heartbeatMaxInactivitySecs") ? c.getInt("heartbeatMaxInactivitySecs") : 5;
        this.reconnectPauseSecs = c.hasPathOrNull("reconnectPauseSecs") ? c.getInt("reconnectPauseSecs") : 3;
        this.scrapeRequestBacklogUnhealthySize = c.hasPathOrNull("scrapeRequestBacklogUnhealthySize") ? c.getInt("scrapeRequestBacklogUnhealthySize") : 25;
        this.zipkin = c.hasPathOrNull("zipkin") ? new Internal.Zipkin(c.getConfig("zipkin"), parentPath + "zipkin.", $tsCfgValidator) : new Internal.Zipkin(com.typesafe.config.ConfigFactory.parseString("zipkin{}"), parentPath + "zipkin.", $tsCfgValidator);
      }
    }

    public static class PathConfigs$Elm {

      /**
       * Endpoint labels as JSON
       */
      public final java.lang.String labels;

      /**
       * Endpoint name
       */
      public final java.lang.String name;

      /**
       * Path used by the proxy
       */
      public final java.lang.String path;

      /**
       * URL accessed by the Agent
       */
      public final java.lang.String url;

      public PathConfigs$Elm(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.labels = c.hasPathOrNull("labels") ? c.getString("labels") : "{}";
        this.name = $_reqStr(parentPath, c, "name", $tsCfgValidator);
        this.path = $_reqStr(parentPath, c, "path", $tsCfgValidator);
        this.url = $_reqStr(parentPath, c, "url", $tsCfgValidator);
      }
      private static java.lang.String $_reqStr(java.lang.String parentPath, com.typesafe.config.Config c, java.lang.String path, $TsCfgValidator $tsCfgValidator) {
        if (c == null) return null;
        try {
          return c.getString(path);
        } catch (com.typesafe.config.ConfigException e) {
          $tsCfgValidator.addBadPath(parentPath + path, e);
          return null;
        }
      }

    }

    public static class Proxy {

      /**
       * Proxy hostname
       */
      public final java.lang.String hostname;

      /**
       * Proxy port
       */
      public final int port;

      public Proxy(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.hostname = c.hasPathOrNull("hostname") ? c.getString("hostname") : "localhost";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 50051;
      }
    }

    public static class Tls {

      /**
       * Client certificate chain file path
       */
      public final java.lang.String certChainFilePath;

      /**
       * Overide authority
       */
      public final java.lang.String overrideAuthority;

      /**
       * Client private key file path
       */
      public final java.lang.String privateKeyFilePath;

      /**
       * Trust certificate collection file path
       */
      public final java.lang.String trustCertCollectionFilePath;

      public Tls(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.certChainFilePath = c.hasPathOrNull("certChainFilePath") ? c.getString("certChainFilePath") : "";
        this.overrideAuthority = c.hasPathOrNull("overrideAuthority") ? c.getString("overrideAuthority") : "";
        this.privateKeyFilePath = c.hasPathOrNull("privateKeyFilePath") ? c.getString("privateKeyFilePath") : "";
        this.trustCertCollectionFilePath = c.hasPathOrNull("trustCertCollectionFilePath") ? c.getString("trustCertCollectionFilePath") : "";
      }
    }

    public static class Metrics {

      /**
       * Include JVM class loading metrics
       */
      public final boolean classLoadingExportsEnabled;

      /**
       * Enable Agent metrics
       */
      public final boolean enabled;

      /**
       * Include JVM garbage collector metrics
       */
      public final boolean garbageCollectorExportsEnabled;
      public final Metrics.Grpc2 grpc;

      /**
       * Include JVM memory pool metrics
       */
      public final boolean memoryPoolsExportsEnabled;

      /**
       * Path for metrics endpoint
       */
      public final java.lang.String path;

      /**
       * Listen port for metrics endpoint
       */
      public final int port;

      /**
       * Include standard export metrics
       */
      public final boolean standardExportsEnabled;

      /**
       * Include JVM thread metrics
       */
      public final boolean threadExportsEnabled;

      /**
       * Include JVM version info metrics
       */
      public final boolean versionInfoExportsEnabled;

      public static class Grpc2 {

        /**
         * Include all vs just cheap metrics
         */
        public final boolean allMetricsReported;

        /**
         * Include gRPC metrics
         */
        public final boolean metricsEnabled;

        public Grpc2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
          this.allMetricsReported = c.hasPathOrNull("allMetricsReported") && c.getBoolean("allMetricsReported");
          this.metricsEnabled = c.hasPathOrNull("metricsEnabled") && c.getBoolean("metricsEnabled");
        }
      }

      public Metrics(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.classLoadingExportsEnabled = c.hasPathOrNull("classLoadingExportsEnabled") && c.getBoolean("classLoadingExportsEnabled");
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.garbageCollectorExportsEnabled = c.hasPathOrNull("garbageCollectorExportsEnabled") && c.getBoolean("garbageCollectorExportsEnabled");
        this.grpc = c.hasPathOrNull("grpc") ? new Metrics.Grpc2(c.getConfig("grpc"), parentPath + "grpc.", $tsCfgValidator) : new Metrics.Grpc2(com.typesafe.config.ConfigFactory.parseString("grpc{}"), parentPath + "grpc.", $tsCfgValidator);
        this.memoryPoolsExportsEnabled = c.hasPathOrNull("memoryPoolsExportsEnabled") && c.getBoolean("memoryPoolsExportsEnabled");
        this.path = c.hasPathOrNull("path") ? c.getString("path") : "metrics";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8083;
        this.standardExportsEnabled = c.hasPathOrNull("standardExportsEnabled") && c.getBoolean("standardExportsEnabled");
        this.threadExportsEnabled = c.hasPathOrNull("threadExportsEnabled") && c.getBoolean("threadExportsEnabled");
        this.versionInfoExportsEnabled = c.hasPathOrNull("versionInfoExportsEnabled") && c.getBoolean("versionInfoExportsEnabled");
      }
    }
    private static java.util.List<Agent.PathConfigs$Elm> $_LAgent_PathConfigs$Elm(com.typesafe.config.ConfigList cl, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
      java.util.ArrayList<Agent.PathConfigs$Elm> al = new java.util.ArrayList<>();
      for (com.typesafe.config.ConfigValue cv : cl) {
        al.add(new Agent.PathConfigs$Elm(((com.typesafe.config.ConfigObject) cv).toConfig(), parentPath, $tsCfgValidator));
      }
      return java.util.Collections.unmodifiableList(al);
    }
  }

  public static class Proxy2 {
    public final Proxy2.Admin2 admin;
    public final Proxy2.Agent2 agent;
    public final Proxy2.Grpc3 grpc;
    public final Proxy2.Http2 http;
    public final Proxy2.Internal2 internal;
    public final Proxy2.Metrics2 metrics;

    /**
     * Assign to true to disable gRPC reflection
     */
    public final boolean reflectionDisabled;
    public final Proxy2.Service service;
    public final Proxy2.Tls2 tls;

    /**
     * Assign to true if using nginx as a reverse proxy
     */
    public final boolean transportFilterDisabled;
    public static class Admin2 {

      /**
       * Enable proxy debug servlet on admin port
       */
      public final boolean debugEnabled;

      /**
       * Enable Admin servlets
       */
      public final boolean enabled;

      /**
       * HealthCheck servlet path
       */
      public final java.lang.String healthCheckPath;

      /**
       * Ping servlet path
       */
      public final java.lang.String pingPath;

      /**
       * Admin servlets port
       */
      public final int port;

      /**
       * Size of queue for recent activities on debug servlet
       */
      public final int recentRequestsQueueSize;

      /**
       * ThreadDump servlet path
       */
      public final java.lang.String threadDumpPath;

      /**
       * Version servlet path
       */
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

      /**
       * Listen port for agent connections
       */
      public final int port;

      public Agent2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 50051;
      }
    }

    public Proxy2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
      this.admin = c.hasPathOrNull("admin") ? new Proxy2.Admin2(c.getConfig("admin"), parentPath + "admin.", $tsCfgValidator) : new Proxy2.Admin2(com.typesafe.config.ConfigFactory.parseString("admin{}"), parentPath + "admin.", $tsCfgValidator);
      this.agent = c.hasPathOrNull("agent") ? new Proxy2.Agent2(c.getConfig("agent"), parentPath + "agent.", $tsCfgValidator) : new Proxy2.Agent2(com.typesafe.config.ConfigFactory.parseString("agent{}"), parentPath + "agent.", $tsCfgValidator);
      this.grpc = c.hasPathOrNull("grpc") ? new Proxy2.Grpc3(c.getConfig("grpc"), parentPath + "grpc.", $tsCfgValidator) : new Proxy2.Grpc3(com.typesafe.config.ConfigFactory.parseString("grpc{}"), parentPath + "grpc.", $tsCfgValidator);
      this.http = c.hasPathOrNull("http") ? new Proxy2.Http2(c.getConfig("http"), parentPath + "http.", $tsCfgValidator) : new Proxy2.Http2(com.typesafe.config.ConfigFactory.parseString("http{}"), parentPath + "http.", $tsCfgValidator);
      this.internal = c.hasPathOrNull("internal") ? new Proxy2.Internal2(c.getConfig("internal"), parentPath + "internal.", $tsCfgValidator) : new Proxy2.Internal2(com.typesafe.config.ConfigFactory.parseString("internal{}"), parentPath + "internal.", $tsCfgValidator);
      this.metrics = c.hasPathOrNull("metrics") ? new Proxy2.Metrics2(c.getConfig("metrics"), parentPath + "metrics.", $tsCfgValidator) : new Proxy2.Metrics2(com.typesafe.config.ConfigFactory.parseString("metrics{}"), parentPath + "metrics.", $tsCfgValidator);
      this.reflectionDisabled = c.hasPathOrNull("reflectionDisabled") && c.getBoolean("reflectionDisabled");
      this.service = c.hasPathOrNull("service") ? new Proxy2.Service(c.getConfig("service"), parentPath + "service.", $tsCfgValidator) : new Proxy2.Service(com.typesafe.config.ConfigFactory.parseString("service{}"), parentPath + "service.", $tsCfgValidator);
      this.tls = c.hasPathOrNull("tls") ? new Proxy2.Tls2(c.getConfig("tls"), parentPath + "tls.", $tsCfgValidator) : new Proxy2.Tls2(com.typesafe.config.ConfigFactory.parseString("tls{}"), parentPath + "tls.", $tsCfgValidator);
      this.transportFilterDisabled = c.hasPathOrNull("transportFilterDisabled") && c.getBoolean("transportFilterDisabled");
    }

    public static class Http2 {
      public final int idleTimeoutSecs;
      public final int maxThreads;
      public final int minThreads;

      /**
       * Listen port for proxied scrapes
       */
      public final int port;

      /**
       * Log every proxy metrics request
       */
      public final boolean requestLoggingEnabled;

      public Http2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.idleTimeoutSecs = c.hasPathOrNull("idleTimeoutSecs") ? c.getInt("idleTimeoutSecs") : 45;
        this.maxThreads = c.hasPathOrNull("maxThreads") ? c.getInt("maxThreads") : -1;
        this.minThreads = c.hasPathOrNull("minThreads") ? c.getInt("minThreads") : -1;
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8080;
        this.requestLoggingEnabled = !c.hasPathOrNull("requestLoggingEnabled") || c.getBoolean("requestLoggingEnabled");
      }
    }

    public static class Internal2 {
      public final Internal2.Blitz blitz;

      /**
       * Threshold for returning an unhealthy healthcheck
       */
      public final int chunkContextMapUnhealthySize;

      /**
       * Seconds of inactivity before agent is evicted in seconds
       */
      public final int maxAgentInactivitySecs;

      /**
       * Threshold for returning an unhealthy healthcheck
       */
      public final int scrapeRequestBacklogUnhealthySize;

      /**
       * Pause time between checks for scrape request timeout in millis
       */
      public final int scrapeRequestCheckMillis;

      /**
       * Threshold for returning an unhealthy healthcheck
       */
      public final int scrapeRequestMapUnhealthySize;

      /**
       * Timeout for scrape requests in seconds
       */
      public final int scrapeRequestTimeoutSecs;
      public final boolean staleAgentCheckEnabled;

      /**
       * Pause interval for agent cleanup in seconds
       */
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
        this.chunkContextMapUnhealthySize = c.hasPathOrNull("chunkContextMapUnhealthySize") ? c.getInt("chunkContextMapUnhealthySize") : 25;
        this.maxAgentInactivitySecs = c.hasPathOrNull("maxAgentInactivitySecs") ? c.getInt("maxAgentInactivitySecs") : 60;
        this.scrapeRequestBacklogUnhealthySize = c.hasPathOrNull("scrapeRequestBacklogUnhealthySize") ? c.getInt("scrapeRequestBacklogUnhealthySize") : 25;
        this.scrapeRequestCheckMillis = c.hasPathOrNull("scrapeRequestCheckMillis") ? c.getInt("scrapeRequestCheckMillis") : 500;
        this.scrapeRequestMapUnhealthySize = c.hasPathOrNull("scrapeRequestMapUnhealthySize") ? c.getInt("scrapeRequestMapUnhealthySize") : 25;
        this.scrapeRequestTimeoutSecs = c.hasPathOrNull("scrapeRequestTimeoutSecs") ? c.getInt("scrapeRequestTimeoutSecs") : 90;
        this.staleAgentCheckEnabled = !c.hasPathOrNull("staleAgentCheckEnabled") || c.getBoolean("staleAgentCheckEnabled");
        this.staleAgentCheckPauseSecs = c.hasPathOrNull("staleAgentCheckPauseSecs") ? c.getInt("staleAgentCheckPauseSecs") : 10;
        this.zipkin = c.hasPathOrNull("zipkin") ? new Internal2.Zipkin2(c.getConfig("zipkin"), parentPath + "zipkin.", $tsCfgValidator) : new Internal2.Zipkin2(com.typesafe.config.ConfigFactory.parseString("zipkin{}"), parentPath + "zipkin.", $tsCfgValidator);
      }
    }

    public static class Grpc3 {
      public final long handshakeTimeoutSecs;
      public final long keepAliveTimeSecs;
      public final long keepAliveTimeoutSecs;
      public final long maxConnectionAgeGraceSecs;
      public final long maxConnectionAgeSecs;
      public final long maxConnectionIdleSecs;
      public final long permitKeepAliveTimeSecs;
      public final boolean permitKeepAliveWithoutCalls;

      public Grpc3(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.handshakeTimeoutSecs = c.hasPathOrNull("handshakeTimeoutSecs") ? c.getLong("handshakeTimeoutSecs") : -1;
        this.keepAliveTimeSecs = c.hasPathOrNull("keepAliveTimeSecs") ? c.getLong("keepAliveTimeSecs") : -1;
        this.keepAliveTimeoutSecs = c.hasPathOrNull("keepAliveTimeoutSecs") ? c.getLong("keepAliveTimeoutSecs") : -1;
        this.maxConnectionAgeGraceSecs = c.hasPathOrNull("maxConnectionAgeGraceSecs") ? c.getLong("maxConnectionAgeGraceSecs") : -1;
        this.maxConnectionAgeSecs = c.hasPathOrNull("maxConnectionAgeSecs") ? c.getLong("maxConnectionAgeSecs") : -1;
        this.maxConnectionIdleSecs = c.hasPathOrNull("maxConnectionIdleSecs") ? c.getLong("maxConnectionIdleSecs") : -1;
        this.permitKeepAliveTimeSecs = c.hasPathOrNull("permitKeepAliveTimeSecs") ? c.getLong("permitKeepAliveTimeSecs") : -1;
        this.permitKeepAliveWithoutCalls = c.hasPathOrNull("permitKeepAliveWithoutCalls") && c.getBoolean("permitKeepAliveWithoutCalls");
      }
    }

    public static class Metrics2 {

      /**
       * Include JVM class loading metrics
       */
      public final boolean classLoadingExportsEnabled;

      /**
       * Enable Proxy metrics
       */
      public final boolean enabled;

      /**
       * Include JVM garbage collector metrics
       */
      public final boolean garbageCollectorExportsEnabled;
      public final Metrics2.Grpc4 grpc;

      /**
       * Include JVM memory pool metrics
       */
      public final boolean memoryPoolsExportsEnabled;

      /**
       * Path for metrics endpoint
       */
      public final java.lang.String path;

      /**
       * Listen port for metrics endpoint
       */
      public final int port;

      /**
       * Include standard export metrics
       */
      public final boolean standardExportsEnabled;

      /**
       * Include JVM thread metrics
       */
      public final boolean threadExportsEnabled;

      /**
       * Include JVM version info metrics
       */
      public final boolean versionInfoExportsEnabled;

      public static class Grpc4 {

        /**
         * Include all vs just cheap metrics
         */
        public final boolean allMetricsReported;

        /**
         * Include gRPC metrics
         */
        public final boolean metricsEnabled;

        public Grpc4(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
          this.allMetricsReported = c.hasPathOrNull("allMetricsReported") && c.getBoolean("allMetricsReported");
          this.metricsEnabled = c.hasPathOrNull("metricsEnabled") && c.getBoolean("metricsEnabled");
        }
      }

      public Metrics2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.classLoadingExportsEnabled = c.hasPathOrNull("classLoadingExportsEnabled") && c.getBoolean("classLoadingExportsEnabled");
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.garbageCollectorExportsEnabled = c.hasPathOrNull("garbageCollectorExportsEnabled") && c.getBoolean("garbageCollectorExportsEnabled");
        this.grpc = c.hasPathOrNull("grpc") ? new Metrics2.Grpc4(c.getConfig("grpc"), parentPath + "grpc.", $tsCfgValidator) : new Metrics2.Grpc4(com.typesafe.config.ConfigFactory.parseString("grpc{}"), parentPath + "grpc.", $tsCfgValidator);
        this.memoryPoolsExportsEnabled = c.hasPathOrNull("memoryPoolsExportsEnabled") && c.getBoolean("memoryPoolsExportsEnabled");
        this.path = c.hasPathOrNull("path") ? c.getString("path") : "metrics";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8082;
        this.standardExportsEnabled = c.hasPathOrNull("standardExportsEnabled") && c.getBoolean("standardExportsEnabled");
        this.threadExportsEnabled = c.hasPathOrNull("threadExportsEnabled") && c.getBoolean("threadExportsEnabled");
        this.versionInfoExportsEnabled = c.hasPathOrNull("versionInfoExportsEnabled") && c.getBoolean("versionInfoExportsEnabled");
      }
    }

    public static class Tls2 {

      /**
       * Server certificate chain file path
       */
      public final java.lang.String certChainFilePath;

      /**
       * Server private key file path
       */
      public final java.lang.String privateKeyFilePath;

      /**
       * Trust certificate collection file path
       */
      public final java.lang.String trustCertCollectionFilePath;

      public Tls2(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.certChainFilePath = c.hasPathOrNull("certChainFilePath") ? c.getString("certChainFilePath") : "";
        this.privateKeyFilePath = c.hasPathOrNull("privateKeyFilePath") ? c.getString("privateKeyFilePath") : "";
        this.trustCertCollectionFilePath = c.hasPathOrNull("trustCertCollectionFilePath") ? c.getString("trustCertCollectionFilePath") : "";
      }
    }

    public static class Service {
      public final Service.Discovery discovery;
      public static class Discovery {

        /**
         * Enable service discovery
         */
        public final boolean enabled;

        /**
         * Service discovery path
         */
        public final java.lang.String path;

        /**
         * Service discovery target prefix
         */
        public final java.lang.String targetPrefix;

        public Discovery(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
          this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
          this.path = c.hasPathOrNull("path") ? c.getString("path") : "discovery";
          this.targetPrefix = c.hasPathOrNull("targetPrefix") ? c.getString("targetPrefix") : "http://localhost:8080/";
        }
      }

      public Service(com.typesafe.config.Config c, java.lang.String parentPath, $TsCfgValidator $tsCfgValidator) {
        this.discovery = c.hasPathOrNull("discovery") ? new Service.Discovery(c.getConfig("discovery"), parentPath + "discovery.", $tsCfgValidator) : new Service.Discovery(com.typesafe.config.ConfigFactory.parseString("discovery{}"), parentPath + "discovery.", $tsCfgValidator);
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
