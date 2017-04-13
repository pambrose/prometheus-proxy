package com.sudothought.proxy;

import com.sudothought.agent.AgentContext;
import com.sudothought.grpc.ScrapeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Service;

import java.util.concurrent.atomic.AtomicLong;

public class HttpServer {

  private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

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
                       final String agent_id = this.proxy.getPathMap().get(path);

                       if (agent_id == null) {
                         logger.info("Missing path request /{}", path);
                         res.status(404);
                         return null;
                       }

                       final AgentContext agentContext = proxy.getAgentContextMap().get(agent_id);
                       if (agentContext == null) {
                         this.proxy.getAgentContextMap().remove(agent_id);
                         logger.info("Missing AgentContext /{} agent_id: {}", path, agent_id);
                         res.status(404);
                         return null;
                       }

                       final long scrape_id = SCRAPE_ID_GENERATOR.getAndIncrement();
                       final ScrapeRequest scrapeRequest = ScrapeRequest.newBuilder()
                                                                        .setAgentId(agent_id)
                                                                        .setScrapeId(scrape_id)
                                                                        .setPath(path)
                                                                        .build();
                       final ScrapeRequestContext scrapeRequestContext = new ScrapeRequestContext(scrapeRequest);

                       this.proxy.getScrapeRequestMap().put(scrape_id, scrapeRequestContext);
                       agentContext.getScrapeRequestQueue().add(scrapeRequestContext);

                       while (true) {
                         if (scrapeRequestContext.waitUntilComplete()) {
                           break;
                         }
                         else {
                           // Check if agent is disconnected or agent is hung
                           if (!proxy.isValidAgentId(agent_id) || scrapeRequestContext.ageInSecs() >= 5 || proxy.isStopped()) {
                             res.status(503);
                             return null;
                           }
                         }
                       }

                       logger.info("Results returned from agent for scrape_id: {}", scrape_id);

                       final int status_code = scrapeRequestContext.getScrapeResponse().get().getStatusCode();
                       res.status(status_code);

                       if (status_code >= 400) {
                         return null;
                       }
                       else {
                         res.type("text/plain");
                         return scrapeRequestContext.getScrapeResponse().get().getText();
                       }

                     });
  }

  public void stop() {
    this.service.stop();
  }
}
