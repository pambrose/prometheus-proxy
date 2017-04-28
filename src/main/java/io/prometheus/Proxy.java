package io.prometheus;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.ServiceManager;
import io.grpc.Attributes;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.GenericService;
import io.prometheus.common.GenericServiceListener;
import io.prometheus.common.MetricsConfig;
import io.prometheus.common.Utils;
import io.prometheus.common.ZipkinConfig;
import io.prometheus.grpc.UnregisterPathResponse;
import io.prometheus.proxy.AgentContext;
import io.prometheus.proxy.AgentContextCleanupService;
import io.prometheus.proxy.ProxyGrpcService;
import io.prometheus.proxy.ProxyHttpService;
import io.prometheus.proxy.ProxyMetrics;
import io.prometheus.proxy.ProxyOptions;
import io.prometheus.proxy.ScrapeRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;

public class Proxy
    extends GenericService {

  private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

  public static final String                          AGENT_ID         = "agent-id";
  public static final Attributes.Key<String>          ATTRIB_AGENT_ID  = Attributes.Key.of(AGENT_ID);
  private final       Map<String, AgentContext>       agentContextMap  = Maps.newConcurrentMap(); // Map agent_id to AgentContext
  private final       Map<String, AgentContext>       pathMap          = Maps.newConcurrentMap(); // Map path to AgentContext
  private final       Map<Long, ScrapeRequestWrapper> scrapeRequestMap = Maps.newConcurrentMap(); // Map scrape_id to agent_id

  private final ProxyMetrics               metrics;
  private final ProxyGrpcService           grpcService;
  private final ProxyHttpService           httpService;
  private final AgentContextCleanupService agentCleanupService;
  private final ServiceManager             serviceManager;

  public Proxy(final ProxyOptions options,
               final MetricsConfig metricsConfig,
               final ZipkinConfig zipkinConfig,
               final int proxyPort,
               final String inProcessServerName,
               final boolean testMode)
      throws IOException {
    super(options.getConfigVals(), metricsConfig, zipkinConfig, testMode);

    this.metrics = this.isMetricsEnabled() ? new ProxyMetrics(this) : null;
    this.grpcService = isNullOrEmpty(inProcessServerName) ? ProxyGrpcService.create(this, options.getAgentPort())
                                                          : ProxyGrpcService.create(this, inProcessServerName);
    this.httpService = new ProxyHttpService(this, proxyPort);
    this.agentCleanupService = new AgentContextCleanupService(this);

    final List<Service> serviceList = Lists.newArrayList(this, this.httpService, this.agentCleanupService);
    if (this.isMetricsEnabled())
      serviceList.add(this.getMetricsService());
    if (this.isZipkinEnabled())
      serviceList.add(this.getZipkinReporterService());
    this.serviceManager = new ServiceManager(serviceList);
    this.serviceManager.addListener(new ServiceManager.Listener() {
      @Override
      public void healthy() {
        logger.info("All Proxy services healthy");
      }

      @Override
      public void stopped() {
        logger.info("All Proxy services stopped");
      }

      @Override
      public void failure(final Service service) {
        logger.info("Proxy service failed: {}", service);
      }
    });

    logger.info("Created {}", this);
  }

  public static void main(final String[] argv)
      throws IOException, InterruptedException {
    final ProxyOptions options = new ProxyOptions(argv);
    final MetricsConfig metricsConfig = MetricsConfig.create(options.getMetricsEnabled(),
                                                             options.getMetricsPort(),
                                                             options.getConfigVals().proxy.metrics);
    final ZipkinConfig zipkinConfig = ZipkinConfig.create(options.getConfigVals().proxy.internal.zipkin);

    logger.info(Utils.getBanner("banners/proxy.txt"));
    logger.info(Utils.getVersionDesc());

    final Proxy proxy = new Proxy(options,
                                  metricsConfig,
                                  zipkinConfig,
                                  options.getProxyPort(),
                                  null,
                                  false);
    proxy.addListener(new GenericServiceListener(proxy), MoreExecutors.directExecutor());
    proxy.startAsync();
  }

  @Override
  protected void startUp()
      throws Exception {
    super.startUp();
    this.grpcService.startAsync();
    this.httpService.startAsync();
    this.agentCleanupService.startAsync();
  }

  @Override
  protected void shutDown()
      throws Exception {
    this.grpcService.stopAsync();
    this.httpService.stopAsync();
    this.agentCleanupService.stopAsync();
    super.shutDown();
  }

  @Override
  protected void run() {
    while (this.isRunning()) {
      Utils.sleepForMillis(500);
    }
  }

  public void addAgentContext(final AgentContext agentContext) {
    this.agentContextMap.put(agentContext.getAgentId(), agentContext);
  }

  public AgentContext getAgentContext(String agentId) { return this.agentContextMap.get(agentId); }

  public AgentContext removeAgentContext(String agentId) {
    final AgentContext agentContext = this.agentContextMap.remove(agentId);
    if (agentContext != null) {
      logger.info("Removed {}", agentContext);
      agentContext.markInvalid();
    }
    else
      logger.error("Missing AgentContext for agentId: {}", agentId);

    return agentContext;
  }

  public void addToScrapeRequestMap(final ScrapeRequestWrapper scrapeRequest) {
    this.scrapeRequestMap.put(scrapeRequest.getScrapeId(), scrapeRequest);
  }

  public ScrapeRequestWrapper getFromScrapeRequestMap(long scrapeId) {
    return this.scrapeRequestMap.get(scrapeId);
  }

  public ScrapeRequestWrapper removeFromScrapeRequestMap(long scrapeId) {
    return this.scrapeRequestMap.remove(scrapeId);
  }

  public AgentContext getAgentContextByPath(final String path) { return this.pathMap.get(path); }

  public boolean containsPath(final String path) { return this.pathMap.containsKey(path);}

  public void addPath(final String path, final AgentContext agentContext) {
    synchronized (this.pathMap) {
      this.pathMap.put(path, agentContext);
      if (!this.isTestMode())
        logger.info("Added path /{} for {}", path, agentContext);
    }
  }

  public void removePath(final String path, final String agentId,
                         final UnregisterPathResponse.Builder responseBuilder) {
    synchronized (this.pathMap) {
      final AgentContext agentContext = this.pathMap.get(path);
      if (agentContext == null) {
        final String msg = format("Unable to remove path /%s - path not found", path);
        logger.info(msg);
        responseBuilder.setValid(false).setReason(msg);
      }
      else if (!agentContext.getAgentId().equals(agentId)) {
        final String msg = format("Unable to remove path /%s - invalid agentId: %s (owner is %s)",
                                  path, agentId, agentContext.getAgentId());
        logger.info(msg);
        responseBuilder.setValid(false).setReason(msg);
      }
      else {
        this.pathMap.remove(path);
        if (!this.isTestMode())
          logger.info("Removed path /{} for {}", path, agentContext);
        responseBuilder.setValid(true).setReason("");
      }
    }
  }

  public void removePathByAgentId(final String agentId) {
    synchronized (this.pathMap) {
      for (Map.Entry<String, AgentContext> elem : this.pathMap.entrySet()) {
        if (elem.getValue().getAgentId().equals(agentId)) {
          final AgentContext agentContext = this.pathMap.remove(elem.getKey());
          if (agentContext != null)
            logger.info("Removed path /{} for {}", elem.getKey(), agentContext);
          else
            logger.error("Missing path /{} for agentId: {}", elem.getKey(), agentId);
        }
      }
    }
  }

  public int pathMapSize() { return this.pathMap.size(); }

  public int getAgentContextSize() { return this.agentContextMap.size(); }

  public int getPathMapSize() { return this.pathMap.size(); }

  public int getScrapeMapSize() { return this.scrapeRequestMap.size(); }

  public ProxyMetrics getMetrics() { return this.metrics; }

  public ConfigVals.Proxy2 getConfigVals() { return this.getGenericConfigVals().proxy; }

  public Map<String, AgentContext> getAgentContextMap() { return this.agentContextMap; }

  public int getTotalAgentRequestQueueSize() {
    return this.agentContextMap.values()
                               .stream()
                               .mapToInt(AgentContext::scrapeRequestQueueSize)
                               .sum();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("metricsPort",
                           this.isMetricsEnabled() ? this.getMetricsService().getPort() : "Disabled")
                      .add("metricsPath",
                           this.isMetricsEnabled() ? "/" + this.getMetricsService().getPath() : "Disabled")
                      .add("proxyPort", this.httpService.getPort())
                      .toString();
  }
}
