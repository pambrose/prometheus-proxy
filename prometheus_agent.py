import argparse
import logging
import socket
from queue import Queue
from time import sleep

import grpc
import requests
import yaml
from prometheus_client import start_http_server

from constants import LOG_LEVEL, PROXY, PATH, CONFIG
from proto.proxy_service_pb2 import ProxyServiceStub, AgentInfo, ScrapeResult
from proto.proxy_service_pb2 import RegisterRequest
from utils import setup_logging, grpc_url, run

logger = logging.getLogger(__name__)


class PrometheusAgent(object):
    def __init__(self, hostname, target_path, config_file):
        self.grpc_url = grpc_url(hostname)
        channel = grpc.insecure_channel(self.grpc_url)
        self.stub = ProxyServiceStub(channel)
        self.register_request = RegisterRequest(hostname=socket.gethostname(), target_path=target_path)
        self.result_queue = Queue()
        self.agent_id = -1
        self.proxy_url = None

        with open(config_file) as f:
            self.config = yaml.safe_load(f)
        print(self.config)
        self.path_dict = {c["path"]: c for c in self.config["agent_configs"]}

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        # self.stop()
        return self

    def connect(self):
        register_response = self.stub.registerAgent(self.register_request)
        self.agent_id = register_response.agent_id
        self.proxy_url = register_response.proxy_url

    def readRequestsFromProxy(self):
        for request in self.stub.readRequestsFromProxy(AgentInfo(agent_id=self.agent_id)):
            c = self.path_dict[request.name]
            url = c["url"]
            try:
                logger.info("Processing: {0} {1}".format(request.name, url))
                f = requests.get(url)
                self.result_queue.put(ScrapeResult(agent_id=self.agent_id,
                                                   scrape_id=request.scrape_id,
                                                   status_code=f.status_code,
                                                   reason=f.reason,
                                                   text=f.text))
            except BaseException as e:
                logger.error("Error reading %s", url, exc_info=True)

    def getResults(self):
        while True:
            result = self.result_queue.get()
            self.result_queue.task_done()
            yield result

    def writeResponsesToProxy(self):
        self.stub.writeResponsesToProxy(self.getResults())

    def start(self):
        self.connect()
        run(target=self.readRequestsFromProxy)
        run(target=self.writeResponsesToProxy)

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
            sleep(1)
