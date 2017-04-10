import argparse
import logging
import socket
from threading import Thread
from time import sleep

import grpc
import requests
import yaml
from prometheus_client import start_http_server

from constants import LOG_LEVEL, PROXY, CONFIG
from proto.proxy_service_pb2 import AgentRegisterRequest, PathRegisterRequest
from proto.proxy_service_pb2 import ProxyServiceStub, AgentInfo, ScrapeResponse
from utils import setup_logging, grpc_url

logger = logging.getLogger(__name__)


class PrometheusAgent(object):
    def __init__(self, hostname, config_file):
        self.grpc_url = grpc_url(hostname)
        self.stub = None
        self.agent_id = -1
        self.proxy_url = None
        self.stopped = False

        with open(config_file) as f:
            self.config = yaml.safe_load(f)

        self.path_dict = {c["path"]: c for c in self.config["agent_configs"]}

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.stop()
        return self

    def connect(self):
        while not self.stopped:
            try:
                logger.info("Connecting to proxy at %s...", self.grpc_url)
                channel = grpc.insecure_channel(self.grpc_url)
                self.stub = ProxyServiceStub(channel)
                # Register agent
                register_response = self.stub.registerAgent(AgentRegisterRequest(hostname=socket.gethostname()))
                logger.info("Connected to proxy at %s with agent_id%s", self.grpc_url, register_response.agent_id)
                self.agent_id = register_response.agent_id
                self.proxy_url = register_response.proxy_url

                # Register paths
                for path in self.path_dict.keys():
                    logger.info("Registering path %s...", path)
                    register_response = self.stub.registerPath(PathRegisterRequest(agent_id=self.agent_id, path=path))
                    logger.info("Registered path %s as path_id:%s ", path, register_response.path_id)

                # Return once agent and paths are registered
                return
            except BaseException as e:
                logger.error("Failed to connect to proxy at %s [%s]", self.grpc_url, e)
                sleep(1)

    def read_requests_from_proxy(self):
        try:
            logger.info("Starting request reader...")
            for scrape_request in self.stub.readRequestsFromProxy(AgentInfo(agent_id=self.agent_id)):
                if self.stopped:
                    break
                logger.info("Received scrape_id:%s %s for agent_id: %s",
                            scrape_request.scrape_id, scrape_request.path, scrape_request.agent_id)
                if scrape_request.path not in self.path_dict:
                    self.respond(ScrapeResponse(agent_id=self.agent_id,
                                                scrape_id=scrape_request.scrape_id,
                                                valid=False,
                                                text="Invalid path {0}".format(scrape_request.path)))
                else:
                    try:
                        url = self.path_dict[scrape_request.path]["url"]
                        logger.info("Fetching: %s %s", scrape_request.path, url)
                        resp = requests.get(url)
                        self.respond(ScrapeResponse(agent_id=self.agent_id,
                                                    scrape_id=scrape_request.scrape_id,
                                                    valid=True,
                                                    status_code=resp.status_code,
                                                    text=resp.text))
                    except BaseException as e:
                        logger.warning("Error processing %s [%s]", scrape_request.path, e)
                        self.respond(ScrapeResponse(agent_id=self.agent_id,
                                                    scrape_id=scrape_request.scrape_id,
                                                    valid=False,
                                                    text=str(e)))
        except BaseException:
            logger.error("Agent disconnected from proxy")

    def respond(self, result):
        self.stub.writeResponseToProxy(result)

    def start(self):
        Thread(target=self.reconnect_loop, daemon=True).start()

    def stop(self):
        self.stopped = True

    def reconnect_loop(self):
        while not self.stopped:
            self.connect()
            self.read_requests_from_proxy()
            sleep(1)
            logger.info("Reconnecting...")

    def __str__(self):
        return "grpc_url={0}\nproxy_url={1}\nagent_id={2}".format(self.grpc_url, self.proxy_url, self.agent_id)


if __name__ == "__main__":
    setup_logging()

    hostname = socket.gethostname()
    parser = argparse.ArgumentParser()
    parser.add_argument("--proxy", dest=PROXY, default="localhost:50051", help="Proxy url")
    parser.add_argument("--config", dest=CONFIG, required=True, help="Configuration .yml file")
    parser.add_argument("-v", "--verbose", dest=LOG_LEVEL, default=logging.INFO, action="store_const",
                        const=logging.DEBUG, help="Enable debugging info")
    args = vars(parser.parse_args())

    setup_logging(level=args[LOG_LEVEL])

    # Start up a server to expose the metrics.
    start_http_server(8000)

    with PrometheusAgent(args[PROXY], args[CONFIG]):
        while True:
            try:
                sleep(1)
            except KeyboardInterrupt:
                pass
