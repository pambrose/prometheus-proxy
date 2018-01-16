/*
 * Copyright © 2018 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// generated by tscfg 0.8.3 on Mon Dec 25 22:13:18 PST 2017
// source: etc/config/config.conf

package io.prometheus.common;

public class ConfigVals {
  public final ConfigVals.Agent  agent;
  public final ConfigVals.Proxy2 proxy;
  public ConfigVals(com.typesafe.config.Config c) {
    this.agent = new ConfigVals.Agent(c.getConfig("agent"));
    this.proxy = new ConfigVals.Proxy2(c.getConfig("proxy"));
  }

  public static class Agent {
    public final Agent.Admin                           admin;
    public final Agent.Internal                        internal;
    public final Agent.Metrics                         metrics;
    public final java.lang.String                      name;
    public final java.util.List<Agent.PathConfigs$Elm> pathConfigs;
    public final Agent.Proxy                           proxy;
    public Agent(com.typesafe.config.Config c) {
      this.admin = new Agent.Admin(c.getConfig("admin"));
      this.internal = new Agent.Internal(c.getConfig("internal"));
      this.metrics = new Agent.Metrics(c.getConfig("metrics"));
      this.name = c.hasPathOrNull("name") ? c.getString("name") : "";
      this.pathConfigs = $_LAgent_PathConfigs$Elm(c.getList("pathConfigs"));
      this.proxy = new Agent.Proxy(c.getConfig("proxy"));
    }

    private static java.util.List<Agent.PathConfigs$Elm> $_LAgent_PathConfigs$Elm(com.typesafe.config.ConfigList cl) {
      java.util.ArrayList<Agent.PathConfigs$Elm> al = new java.util.ArrayList<>();
      for (com.typesafe.config.ConfigValue cv : cl) {
        al.add(new Agent.PathConfigs$Elm(((com.typesafe.config.ConfigObject) cv).toConfig()));
      }
      return java.util.Collections.unmodifiableList(al);
    }

    public static class Admin {
      public final boolean          enabled;
      public final java.lang.String healthCheckPath;
      public final java.lang.String pingPath;
      public final int              port;
      public final java.lang.String threadDumpPath;
      public final java.lang.String versionPath;

      public Admin(com.typesafe.config.Config c) {
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.healthCheckPath = c.hasPathOrNull("healthCheckPath") ? c.getString("healthCheckPath") : "healthcheck";
        this.pingPath = c.hasPathOrNull("pingPath") ? c.getString("pingPath") : "ping";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8093;
        this.threadDumpPath = c.hasPathOrNull("threadDumpPath") ? c.getString("threadDumpPath") : "threaddump";
        this.versionPath = c.hasPathOrNull("versionPath") ? c.getString("versionPath") : "version";
      }
    }

    public static class Internal {
      public final int             heartbeatCheckPauseMillis;
      public final boolean         heartbeatEnabled;
      public final int             heartbeatMaxInactivitySecs;
      public final int             reconectPauseSecs;
      public final int             scrapeResponseQueueCheckMillis;
      public final int             scrapeResponseQueueSize;
      public final int             scrapeResponseQueueUnhealthySize;
      public final Internal.Zipkin zipkin;
      public Internal(com.typesafe.config.Config c) {
        this.heartbeatCheckPauseMillis = c.hasPathOrNull("heartbeatCheckPauseMillis") ? c.getInt("heartbeatCheckPauseMillis") : 500;
        this.heartbeatEnabled = !c.hasPathOrNull("heartbeatEnabled") || c.getBoolean("heartbeatEnabled");
        this.heartbeatMaxInactivitySecs = c.hasPathOrNull("heartbeatMaxInactivitySecs") ? c.getInt("heartbeatMaxInactivitySecs") : 5;
        this.reconectPauseSecs = c.hasPathOrNull("reconectPauseSecs") ? c.getInt("reconectPauseSecs") : 3;
        this.scrapeResponseQueueCheckMillis = c.hasPathOrNull("scrapeResponseQueueCheckMillis") ? c.getInt("scrapeResponseQueueCheckMillis") : 500;
        this.scrapeResponseQueueSize = c.hasPathOrNull("scrapeResponseQueueSize") ? c.getInt("scrapeResponseQueueSize") : 128;
        this.scrapeResponseQueueUnhealthySize = c.hasPathOrNull("scrapeResponseQueueUnhealthySize") ? c.getInt("scrapeResponseQueueUnhealthySize") : 25;
        this.zipkin = new Internal.Zipkin(c.getConfig("zipkin"));
      }

      public static class Zipkin {
        public final boolean          enabled;
        public final boolean          grpcReportingEnabled;
        public final java.lang.String hostname;
        public final java.lang.String path;
        public final int              port;
        public final java.lang.String serviceName;

        public Zipkin(com.typesafe.config.Config c) {
          this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
          this.grpcReportingEnabled = c.hasPathOrNull("grpcReportingEnabled") && c.getBoolean("grpcReportingEnabled");
          this.hostname = c.hasPathOrNull("hostname") ? c.getString("hostname") : "localhost";
          this.path = c.hasPathOrNull("path") ? c.getString("path") : "api/v2/spans";
          this.port = c.hasPathOrNull("port") ? c.getInt("port") : 9411;
          this.serviceName = c.hasPathOrNull("serviceName") ? c.getString("serviceName") : "prometheus-agent";
        }
      }
    }

    public static class Metrics {
      public final boolean          classLoadingExportsEnabled;
      public final boolean          enabled;
      public final boolean          garbageCollectorExportsEnabled;
      public final Metrics.Grpc     grpc;
      public final boolean          memoryPoolsExportsEnabled;
      public final java.lang.String path;
      public final int              port;
      public final boolean          standardExportsEnabled;
      public final boolean          threadExportsEnabled;
      public final boolean          versionInfoExportsEnabled;
      public static class Grpc {
        public final boolean allMetricsReported;
        public final boolean metricsEnabled;

        public Grpc(com.typesafe.config.Config c) {
          this.allMetricsReported = c.hasPathOrNull("allMetricsReported") && c.getBoolean("allMetricsReported");
          this.metricsEnabled = c.hasPathOrNull("metricsEnabled") && c.getBoolean("metricsEnabled");
        }
      }

      public Metrics(com.typesafe.config.Config c) {
        this.classLoadingExportsEnabled = c.hasPathOrNull("classLoadingExportsEnabled") && c.getBoolean("classLoadingExportsEnabled");
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.garbageCollectorExportsEnabled = c.hasPathOrNull("garbageCollectorExportsEnabled") && c.getBoolean("garbageCollectorExportsEnabled");
        this.grpc = new Metrics.Grpc(c.getConfig("grpc"));
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

      public PathConfigs$Elm(com.typesafe.config.Config c) {
        this.name = c.getString("name");
        this.path = c.getString("path");
        this.url = c.getString("url");
      }
    }

    public static class Proxy {
      public final java.lang.String hostname;
      public final int              port;

      public Proxy(com.typesafe.config.Config c) {
        this.hostname = c.hasPathOrNull("hostname") ? c.getString("hostname") : "localhost";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 50051;
      }
    }
  }

  public static class Proxy2 {
    public final Proxy2.Admin2    admin;
    public final Proxy2.Agent2    agent;
    public final Proxy2.Http      http;
    public final Proxy2.Internal2 internal;
    public final Proxy2.Metrics2  metrics;
    public Proxy2(com.typesafe.config.Config c) {
      this.admin = new Proxy2.Admin2(c.getConfig("admin"));
      this.agent = new Proxy2.Agent2(c.getConfig("agent"));
      this.http = new Proxy2.Http(c.getConfig("http"));
      this.internal = new Proxy2.Internal2(c.getConfig("internal"));
      this.metrics = new Proxy2.Metrics2(c.getConfig("metrics"));
    }

    public static class Admin2 {
      public final boolean          enabled;
      public final java.lang.String healthCheckPath;
      public final java.lang.String pingPath;
      public final int              port;
      public final java.lang.String threadDumpPath;
      public final java.lang.String versionPath;

      public Admin2(com.typesafe.config.Config c) {
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.healthCheckPath = c.hasPathOrNull("healthCheckPath") ? c.getString("healthCheckPath") : "healthcheck";
        this.pingPath = c.hasPathOrNull("pingPath") ? c.getString("pingPath") : "ping";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8092;
        this.threadDumpPath = c.hasPathOrNull("threadDumpPath") ? c.getString("threadDumpPath") : "threaddump";
        this.versionPath = c.hasPathOrNull("versionPath") ? c.getString("versionPath") : "version";
      }
    }

    public static class Agent2 {
      public final int port;

      public Agent2(com.typesafe.config.Config c) {
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 50051;
      }
    }

    public static class Http {
      public final int idleTimeoutMillis;
      public final int maxThreads;
      public final int minThreads;
      public final int port;

      public Http(com.typesafe.config.Config c) {
        this.idleTimeoutMillis = c.hasPathOrNull("idleTimeoutMillis") ? c.getInt("idleTimeoutMillis") : -1;
        this.maxThreads = c.hasPathOrNull("maxThreads") ? c.getInt("maxThreads") : -1;
        this.minThreads = c.hasPathOrNull("minThreads") ? c.getInt("minThreads") : -1;
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8080;
      }
    }

    public static class Internal2 {
      public final Internal2.Blitz   blitz;
      public final int               maxAgentInactivitySecs;
      public final int               scrapeRequestCheckMillis;
      public final int               scrapeRequestMapUnhealthySize;
      public final int               scrapeRequestQueueCheckMillis;
      public final int               scrapeRequestQueueSize;
      public final int               scrapeRequestQueueUnhealthySize;
      public final int               scrapeRequestTimeoutSecs;
      public final boolean           staleAgentCheckEnabled;
      public final int               staleAgentCheckPauseSecs;
      public final Internal2.Zipkin2 zipkin;
      public Internal2(com.typesafe.config.Config c) {
        this.blitz = new Internal2.Blitz(c.getConfig("blitz"));
        this.maxAgentInactivitySecs = c.hasPathOrNull("maxAgentInactivitySecs") ? c.getInt("maxAgentInactivitySecs") : 15;
        this.scrapeRequestCheckMillis = c.hasPathOrNull("scrapeRequestCheckMillis") ? c.getInt("scrapeRequestCheckMillis") : 500;
        this.scrapeRequestMapUnhealthySize = c.hasPathOrNull("scrapeRequestMapUnhealthySize") ? c.getInt("scrapeRequestMapUnhealthySize") : 25;
        this.scrapeRequestQueueCheckMillis = c.hasPathOrNull("scrapeRequestQueueCheckMillis") ? c.getInt("scrapeRequestQueueCheckMillis") : 500;
        this.scrapeRequestQueueSize = c.hasPathOrNull("scrapeRequestQueueSize") ? c.getInt("scrapeRequestQueueSize") : 128;
        this.scrapeRequestQueueUnhealthySize = c.hasPathOrNull("scrapeRequestQueueUnhealthySize") ? c.getInt("scrapeRequestQueueUnhealthySize") : 25;
        this.scrapeRequestTimeoutSecs = c.hasPathOrNull("scrapeRequestTimeoutSecs") ? c.getInt("scrapeRequestTimeoutSecs") : 5;
        this.staleAgentCheckEnabled = !c.hasPathOrNull("staleAgentCheckEnabled") || c.getBoolean("staleAgentCheckEnabled");
        this.staleAgentCheckPauseSecs = c.hasPathOrNull("staleAgentCheckPauseSecs") ? c.getInt("staleAgentCheckPauseSecs") : 10;
        this.zipkin = new Internal2.Zipkin2(c.getConfig("zipkin"));
      }

      public static class Blitz {
        public final boolean          enabled;
        public final java.lang.String path;

        public Blitz(com.typesafe.config.Config c) {
          this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
          this.path = c.hasPathOrNull("path") ? c.getString("path") : "mu-dd4bffbb-ff2e9926-5a80952c-1c6cb64d.txt";
        }
      }

      public static class Zipkin2 {
        public final boolean          enabled;
        public final boolean          grpcReportingEnabled;
        public final java.lang.String hostname;
        public final java.lang.String path;
        public final int              port;
        public final java.lang.String serviceName;

        public Zipkin2(com.typesafe.config.Config c) {
          this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
          this.grpcReportingEnabled = c.hasPathOrNull("grpcReportingEnabled") && c.getBoolean("grpcReportingEnabled");
          this.hostname = c.hasPathOrNull("hostname") ? c.getString("hostname") : "localhost";
          this.path = c.hasPathOrNull("path") ? c.getString("path") : "api/v2/spans";
          this.port = c.hasPathOrNull("port") ? c.getInt("port") : 9411;
          this.serviceName = c.hasPathOrNull("serviceName") ? c.getString("serviceName") : "prometheus-proxy";
        }
      }
    }

    public static class Metrics2 {
      public final boolean          classLoadingExportsEnabled;
      public final boolean          enabled;
      public final boolean          garbageCollectorExportsEnabled;
      public final Metrics2.Grpc2   grpc;
      public final boolean          memoryPoolsExportsEnabled;
      public final java.lang.String path;
      public final int              port;
      public final boolean          standardExportsEnabled;
      public final boolean          threadExportsEnabled;
      public final boolean          versionInfoExportsEnabled;
      public static class Grpc2 {
        public final boolean allMetricsReported;
        public final boolean metricsEnabled;

        public Grpc2(com.typesafe.config.Config c) {
          this.allMetricsReported = c.hasPathOrNull("allMetricsReported") && c.getBoolean("allMetricsReported");
          this.metricsEnabled = c.hasPathOrNull("metricsEnabled") && c.getBoolean("metricsEnabled");
        }
      }

      public Metrics2(com.typesafe.config.Config c) {
        this.classLoadingExportsEnabled = c.hasPathOrNull("classLoadingExportsEnabled") && c.getBoolean("classLoadingExportsEnabled");
        this.enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled");
        this.garbageCollectorExportsEnabled = c.hasPathOrNull("garbageCollectorExportsEnabled") && c.getBoolean("garbageCollectorExportsEnabled");
        this.grpc = new Metrics2.Grpc2(c.getConfig("grpc"));
        this.memoryPoolsExportsEnabled = c.hasPathOrNull("memoryPoolsExportsEnabled") && c.getBoolean("memoryPoolsExportsEnabled");
        this.path = c.hasPathOrNull("path") ? c.getString("path") : "metrics";
        this.port = c.hasPathOrNull("port") ? c.getInt("port") : 8082;
        this.standardExportsEnabled = c.hasPathOrNull("standardExportsEnabled") && c.getBoolean("standardExportsEnabled");
        this.threadExportsEnabled = c.hasPathOrNull("threadExportsEnabled") && c.getBoolean("threadExportsEnabled");
        this.versionInfoExportsEnabled = c.hasPathOrNull("versionInfoExportsEnabled") && c.getBoolean("versionInfoExportsEnabled");
      }
    }
  }
}
      
