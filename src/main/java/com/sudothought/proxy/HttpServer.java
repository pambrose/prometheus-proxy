package com.sudothought.proxy;

import com.sudothought.agent.AgentContext;
import com.sudothought.grpc.ScrapeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Service;

import java.util.concurrent.atomic.AtomicLong;

public class HttpServer {

  private static final Logger     logger              = LoggerFactory.getLogger(HttpServer.class);
  private static final AtomicLong SCRAPE_ID_GENERATOR = new AtomicLong(0);

  private final Proxy   proxy;
  private final int     port;
  private final Service service;

  public HttpServer(final Proxy proxy, final int port) {
    this.proxy = proxy;
    this.port = port;
    this.service = Service.ignite();
    this.service.port(this.port);
  }

  public void start() {
    logger.info("Started proxy listening on {}", this.port);

    this.service.get("/*",
                     (req, res) -> {
                       res.header("cache-control", "no-cache");

                       final String path = req.splat()[0];
                       final String agentId = this.proxy.getPathMap().get(path);

                       if (agentId == null) {
                         logger.info("Missing path request /{}", path);
                         res.status(404);
                         return null;
                       }

                       final AgentContext agentContext = proxy.getAgentContext(agentId);
                       if (agentContext == null) {
                         logger.info("Missing AgentContext /{} agent_id: {}", path, agentId);
                         res.status(404);
                         return null;
                       }

                       final long scrapeId = SCRAPE_ID_GENERATOR.getAndIncrement();
                       final ScrapeRequest scrapeRequest = ScrapeRequest.newBuilder()
                                                                        .setAgentId(agentId)
                                                                        .setScrapeId(scrapeId)
                                                                        .setPath(path)
                                                                        .build();
                       final ScrapeRequestContext scrapeRequestContext = new ScrapeRequestContext(scrapeRequest);

                       this.proxy.getScrapeRequestMap().put(scrapeId, scrapeRequestContext);
                       agentContext.getScrapeRequestQueue().add(scrapeRequestContext);

                       while (true) {
                         if (scrapeRequestContext.waitUntilComplete()) {
                           break;
                         }
                         else {
                           // Check if agent is disconnected or agent is hung
                           if (!proxy.isValidAgentId(agentId) || scrapeRequestContext.ageInSecs() >= 5 || proxy.isStopped()) {
                             res.status(503);
                             return null;
                           }
                         }
                       }

                       logger.info("Results returned from agent for scrape_id: {}", scrapeId);

                       final int status_code = scrapeRequestContext.getScrapeResponse().getStatusCode();
                       res.status(status_code);

                       if (status_code >= 400) {
                         return null;
                       }
                       else {
                         res.type("text/plain");
                         return scrapeRequestContext.getScrapeResponse().getText();
                       }

                     });
  }

  public void stop() {
    this.service.stop();
  }
}
