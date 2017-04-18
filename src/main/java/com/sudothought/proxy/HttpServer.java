package com.sudothought.proxy;

import com.github.kristofa.brave.sparkjava.BraveTracing;
import com.sudothought.agent.AgentContext;
import com.sudothought.grpc.ScrapeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ExceptionHandlerImpl;
import spark.Request;
import spark.Response;
import spark.Service;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.ACCEPT_ENCODING;
import static com.google.common.net.HttpHeaders.CONTENT_ENCODING;

public class HttpServer {

  private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

  private final Proxy   proxy;
  private final int     port;
  private final Service http;

  public HttpServer(final Proxy proxy, final int port) {
    this.proxy = proxy;
    this.port = port;
    this.http = Service.ignite();
    this.http.port(this.port);
  }

  public void start() {
    logger.info("Started proxy listening on {}", this.port);

    if (this.proxy.isZipkinReportingEnabled()) {
      final BraveTracing tracing = BraveTracing.create(this.proxy.getBrave());
      this.http.before(tracing.before());
      this.http.exception(Exception.class, tracing.exception(new ExceptionHandlerImpl(Exception.class) {
        @Override
        public void handle(Exception exception, Request request, Response response) {
          response.status(404);
          exception.printStackTrace();
        }
      }));
      this.http.afterAfter(tracing.afterAfter());
    }

    this.http.get("/*",
                  (req, res) -> {
                    res.header("cache-control", "no-cache");

                    final String[] vals = req.splat();
                    if (vals == null || vals.length == 0) {
                      logger.info("Request missing path");
                      res.status(404);
                      this.proxy.getMetrics().scrapeRequests.labels("missing_path").observe(1);
                      return null;
                    }

                    final String path = vals[0];
                    final AgentContext agentContext = this.proxy.getAgentContextByPath(path);

                    if (agentContext == null) {
                      logger.info("Missing path request /{}", path);
                      res.status(404);
                      this.proxy.getMetrics().scrapeRequests.labels("invalid_path").observe(1);
                      return null;
                    }

                    final ScrapeRequestWrapper scrapeRequest = new ScrapeRequestWrapper(this.proxy,
                                                                                        agentContext.getAgentId(),
                                                                                        path,
                                                                                        req.headers(ACCEPT));
                    this.proxy.addScrapeRequest(scrapeRequest);
                    agentContext.addScrapeRequest(scrapeRequest);

                    while (true) {
                      // Returns false if timed out
                      if (scrapeRequest.waitUntilComplete(1000))
                        break;

                      // Check if agent is disconnected or agent is hung
                      if (scrapeRequest.ageInSecs() >= 5 || proxy.isStopped()) {
                        res.status(503);
                        this.proxy.getMetrics().scrapeRequests.labels("time_out").observe(1);
                        return null;
                      }
                    }

                    logger.info("Results returned from {} for {}", agentContext, scrapeRequest);

                    final ScrapeResponse scrapeResponse = scrapeRequest.getScrapeResponse();
                    final int status_code = scrapeResponse.getStatusCode();
                    res.status(status_code);

                    // Do not return content on error status codes
                    if (status_code >= 400) {
                      this.proxy.getMetrics().scrapeRequests.labels("path_not_found").observe(1);
                      return null;
                    }
                    else {
                      final String accept_encoding = req.headers(ACCEPT_ENCODING);
                      if (accept_encoding != null && accept_encoding.contains("gzip"))
                        res.header(CONTENT_ENCODING, "gzip");
                      res.type(scrapeResponse.getContentType());
                      this.proxy.getMetrics().scrapeRequests.labels("success").observe(1);
                      return scrapeRequest.getScrapeResponse().getText();
                    }
                  });
  }

  public void stop() { this.http.stop(); }
}
