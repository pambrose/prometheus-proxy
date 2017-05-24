package io.prometheus.proxy;

import brave.Span;
import brave.Tracer;
import com.github.kristofa.brave.sparkjava.BraveTracing;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.MoreExecutors;
import io.prometheus.Proxy;
import io.prometheus.common.ConfigVals;
import io.prometheus.common.GenericServiceListener;
import io.prometheus.grpc.ScrapeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ExceptionHandlerImpl;
import spark.Request;
import spark.Response;
import spark.Service;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.ACCEPT_ENCODING;
import static com.google.common.net.HttpHeaders.CONTENT_ENCODING;

public class ProxyHttpService
    extends AbstractIdleService {

  private static final Logger logger = LoggerFactory.getLogger(ProxyHttpService.class);

  private final Proxy             proxy;
  private final int               port;
  private final Service           http;
  private final Tracer            tracer;
  private final ConfigVals.Proxy2 configVals;

  public ProxyHttpService(final Proxy proxy, final int port) {
    this.proxy = proxy;
    this.port = port;
    this.http = Service.ignite();
    this.http.port(this.port);
    this.http.threadPool(this.proxy.getConfigVals().http.maxThreads,
                         this.proxy.getConfigVals().http.minThreads,
                         this.proxy.getConfigVals().http.idleTimeoutMillis);
    this.tracer = this.proxy.isZipkinEnabled()
                  ? this.proxy.getZipkinReporterService().newTracer("proxy-http")
                  : null;
    this.configVals = this.proxy.getConfigVals();

    this.addListener(new GenericServiceListener(this), MoreExecutors.directExecutor());
  }

  @Override
  protected void startUp() {
    if (this.proxy.isZipkinEnabled()) {
      final BraveTracing tracing = BraveTracing.create(this.proxy.getBrave());
      this.http.before(tracing.before());
      this.http.exception(Exception.class,
                          tracing.exception(
                              new ExceptionHandlerImpl(Exception.class) {
                                @Override
                                public void handle(Exception e, Request request, Response response) {
                                  response.status(404);
                                  logger.error("Error in ProxyHttpService", e);
                                }
                              }));
      this.http.afterAfter(tracing.afterAfter());
    }

    this.http.get("/*",
                  (req, res) -> {
                    res.header("cache-control", "must-revalidate,no-cache,no-store");

                    final Span span = this.tracer != null ? this.tracer.newTrace()
                                                                       .name("round-trip")
                                                                       .tag("version", "1.2.3")
                                                                       .start()
                                                          : null;
                    try {
                      if (!this.proxy.isRunning()) {
                        logger.error("Proxy stopped");
                        res.status(503);
                        this.updateScrapeRequests("proxy_stopped");
                        return null;
                      }

                      final String[] vals = req.splat();
                      if (vals == null || vals.length == 0) {
                        logger.info("Request missing path");
                        res.status(404);
                        this.updateScrapeRequests("missing_path");
                        return null;
                      }

                      final String path = vals[0];

                      if (this.configVals.internal.blitz.enabled && path.equals(this.configVals.internal.blitz.path)) {
                        res.status(200);
                        res.type("text/plain");
                        return "42";
                      }

                      final AgentContext agentContext = this.proxy.getAgentContextByPath(path);

                      if (agentContext == null) {
                        logger.debug("Invalid path request /{}", path);
                        res.status(404);
                        this.updateScrapeRequests("invalid_path");
                        return null;
                      }

                      if (!agentContext.isValid()) {
                        logger.error("Invalid AgentContext");
                        res.status(404);
                        this.updateScrapeRequests("invalid_agent_context");
                        return null;
                      }

                      if (span != null)
                        span.tag("path", path);

                      return submitScrapeRequest(req, res, agentContext, path, span);

                    }
                    finally {
                      if (span != null)
                        span.finish();
                    }
                  });

  }

  @Override
  protected void shutDown() {
    this.http.stop();
  }

  private String submitScrapeRequest(final Request req, final Response res, final AgentContext agentContext,
                                     final String path, final Span span) {
    final ScrapeRequestWrapper scrapeRequest = new ScrapeRequestWrapper(this.proxy,
                                                                        agentContext,
                                                                        span,
                                                                        path,
                                                                        req.headers(ACCEPT));
    try {
      this.proxy.addToScrapeRequestMap(scrapeRequest);
      agentContext.addToScrapeRequestQueue(scrapeRequest);

      final int timeoutSecs = this.configVals.internal.scrapeRequestTimeoutSecs;
      final int checkMillis = this.configVals.internal.scrapeRequestCheckMillis;
      while (true) {
        // Returns false if timed out
        if (scrapeRequest.waitUntilCompleteMillis(checkMillis))
          break;

        // Check if agent is disconnected or agent is hung
        if (scrapeRequest.ageInSecs() >= timeoutSecs
            || !scrapeRequest.getAgentContext().isValid()
            || !this.proxy.isRunning()) {
          res.status(503);
          this.updateScrapeRequests("time_out");
          return null;
        }
      }
    }
    finally {
      final ScrapeRequestWrapper prev = this.proxy.removeFromScrapeRequestMap(scrapeRequest.getScrapeId());
      if (prev == null)
        logger.error("Scrape request {} missing in map", scrapeRequest.getScrapeId());
    }

    logger.debug("Results returned from {} for {}", agentContext, scrapeRequest);

    final ScrapeResponse scrapeResponse = scrapeRequest.getScrapeResponse();
    final int statusCode = scrapeResponse.getStatusCode();
    res.status(statusCode);

    // Do not return content on error status codes
    if (statusCode >= 400) {
      this.updateScrapeRequests("path_not_found");
      return null;
    }
    else {
      final String acceptEncoding = req.headers(ACCEPT_ENCODING);
      if (acceptEncoding != null && acceptEncoding.contains("gzip"))
        res.header(CONTENT_ENCODING, "gzip");
      res.type(scrapeResponse.getContentType());
      this.updateScrapeRequests("success");
      return scrapeRequest.getScrapeResponse().getText();
    }
  }

  private void updateScrapeRequests(final String type) {
    if (this.proxy.isMetricsEnabled())
      this.proxy.getMetrics().scrapeRequests.labels(type).inc();
  }

  public int getPort() { return this.port; }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("port", port)
                      .toString();
  }
}
