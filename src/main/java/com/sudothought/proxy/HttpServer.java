package com.sudothought.proxy;

import com.sudothought.agent.AgentContext;
import com.sudothought.grpc.ScrapeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    this.http.get("/*",
                  (req, res) -> {
                    res.header("cache-control", "no-cache");

                    final String path = req.splat()[0];
                    final String agentId = this.proxy.getAgentIdByPath(path);

                    if (agentId == null) {
                      logger.info("Missing path request /{}", path);
                      res.status(404);
                      this.proxy.getMetrics().scrapeRequests.labels("invalid_path").observe(1);
                      return null;
                    }

                    final AgentContext agentContext = proxy.getAgentContext(agentId);
                    if (agentContext == null) {
                      logger.info("Missing AgentContext /{} agent_id: {}", path, agentId);
                      res.status(404);
                      this.proxy.getMetrics().scrapeRequests.labels("missing_agent_id").observe(1);
                      return null;
                    }

                    final ScrapeRequestContext scrapeRequestContext = new ScrapeRequestContext(this.proxy,
                                                                                               agentId,
                                                                                               path,
                                                                                               req.headers(ACCEPT));
                    this.proxy.addScrapeRequest(scrapeRequestContext);
                    agentContext.addScrapeRequest(scrapeRequestContext);

                    while (true) {
                      // Returns false if timed out
                      if (scrapeRequestContext.waitUntilComplete(1000))
                        break;

                      // Check if agent is disconnected or agent is hung
                      if (!proxy.isValidAgentId(agentId) || scrapeRequestContext.ageInSecs() >= 5 || proxy.isStopped()) {
                        res.status(503);
                        this.proxy.getMetrics().scrapeRequests.labels("time_out").observe(1);
                        return null;
                      }
                    }

                    logger.info("Results returned from agent for scrape_id: {}", scrapeRequestContext.getScrapeId());

                    final ScrapeResponse scrapeResponse = scrapeRequestContext.getScrapeResponse();
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
                      return scrapeRequestContext.getScrapeResponse().getText();
                    }
                  });
  }

  public void stop() { this.http.stop(); }
}
