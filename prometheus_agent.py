import argparse
import logging
import socket
from queue import Queue
from threading import Thread, Event
from time import sleep

import grpc
import requests
import yaml
from prometheus_client import start_http_server

from constants import LOG_LEVEL, PROXY, PATH, CONFIG
from proto.proxy_service_pb2 import ProxyServiceStub, AgentInfo, ScrapeResult
from proto.proxy_service_pb2 import RegisterRequest
from utils import setup_logging, grpc_url

logger = logging.getLogger(__name__)


class PrometheusAgent(object):
    def __init__(self, hostname, target_path, config_file):
        self.grpc_url = grpc_url(hostname)
        channel = grpc.insecure_channel(self.grpc_url)
        self.stub = ProxyServiceStub(channel)
        self.register_request = RegisterRequest(hostname=socket.gethostname(), target_path=target_path)
        self.response_queue = Queue()
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
                register_response = self.stub.registerAgent(self.register_request)
                logger.info("Connected to proxy at %s", self.grpc_url)
                self.agent_id = register_response.agent_id
                self.proxy_url = register_response.proxy_url
                return
            except BaseException as e:
                logger.error("Failed to connect to proxy at %s [%s]", self.grpc_url, e)
                sleep(1)

    def read_scrape_requests_from_proxy(self, complete):
        try:
            logger.info("Starting request reader")
            for scrape_request in self.stub.readRequestsFromProxy(AgentInfo(agent_id=self.agent_id)):
                if self.stopped:
                    return
                if scrape_request.path not in self.path_dict:
                    self.respond(ScrapeResult(agent_id=self.agent_id,
                                              scrape_id=scrape_request.scrape_id,
                                              valid=False,
                                              text="Invalid path {0}".format(scrape_request.path)))
                else:
                    try:
                        url = self.path_dict[scrape_request.path]["url"]
                        logger.info("Processing: {0} {1}".format(scrape_request.path, url))
                        resp = requests.get(url)
                        self.respond(ScrapeResult(agent_id=self.agent_id,
                                                  scrape_id=scrape_request.scrape_id,
                                                  valid=True,
                                                  status_code=resp.status_code,
                                                  text=resp.text))
                    except BaseException as e:
                        logger.warning("Error processing %s [%s]", scrape_request.path, e)
                        self.respond(ScrapeResult(agent_id=self.agent_id,
                                                  scrape_id=scrape_request.scrape_id,
                                                  valid=False,
                                                  text=str(e)))
        except BaseException:
            logger.error("Request reader disconnected from proxy")
            complete.set()

    def respond(self, result):
        self.response_queue.put(result)

    def read_results_queue(self):
        while not self.stopped:
            result = self.response_queue.get()
            self.response_queue.task_done()
            yield result

    def write_responses_to_proxy(self, complete):
        try:
            logger.info("Starting response writer")
            self.stub.writeResponsesToProxy(self.read_results_queue())
        except BaseException:
            logger.error("Response writer disconnected from proxy")
            complete.set()

    def start(self):
        Thread(target=self.reconnect_loop, daemon=True).start()

    def stop(self):
        self.stopped = True

    def reconnect_loop(self):
        while not self.stopped:
            self.connect()

            request_complete = Event()
            response_complete = Event()

            Thread(target=self.write_responses_to_proxy, args=(response_complete,), daemon=True).start()
            Thread(target=self.read_scrape_requests_from_proxy, args=(request_complete,), daemon=True).start()

            request_complete.wait()
            response_complete.wait()
            sleep(1)
            logger.info("Reconnecting...")

    def __str__(self):
        return "grpc_url={0}\nproxy_url={1}\nagent_id={2}".format(self.grpc_url,
                                                                  self.proxy_url,
                                                                  self.agent_id)


if __name__ == "__main__":
    setup_logging()

    hostname = socket.gethostname()
    parser = argparse.ArgumentParser()
    parser.add_argument("--proxy", dest=PROXY, default="localhost:50051", help="Proxy url")
    parser.add_argument("--path", dest=PATH, default=hostname, help="Path [{0}]".format(hostname))
    parser.add_argument("--config", dest=CONFIG, required=True, help="Configuration .yml file")
    parser.add_argument("-v", "--verbose", dest=LOG_LEVEL, default=logging.INFO, action="store_const",
                        const=logging.DEBUG, help="Enable debugging info")
    args = vars(parser.parse_args())

    setup_logging(level=args[LOG_LEVEL])

    # Start up a server to expose the metrics.
    start_http_server(8000)

    with PrometheusAgent(args[PROXY], args[PATH], args[CONFIG]):
        while True:
            try:
                sleep(1)
            except KeyboardInterrupt:
                pass
