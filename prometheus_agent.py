import argparse
import logging
import socket
from queue import Queue
from threading import Thread
from time import sleep

import grpc
from prometheus_client import start_http_server

from constants import LOG_LEVEL, PROXY, TARGET, PATH
from proto.proxy_service_pb2 import ProxyServiceStub, AgentInfo, ScrapeResult
from proto.proxy_service_pb2 import RegisterRequest
from utils import setup_logging, grpc_url


class PrometheusAgent(object):
    def __init__(self, hostname, target_path, target_url):
        self.grpc_url = grpc_url(hostname)
        self.target_url = target_url
        channel = grpc.insecure_channel(self.grpc_url)
        self.stub = ProxyServiceStub(channel)
        self.register_request = RegisterRequest(hostname=socket.gethostname(), target_path=target_path)
        self.request_queue = Queue()
        self.agent_id = -1
        self.proxy_url = None

    def connect(self):
        register_response = self.stub.registerAgent(self.register_request)
        self.agent_id = register_response.agent_id
        self.proxy_url = register_response.proxy_url

    def readAction(self):
        for request in self.stub.readRequests(AgentInfo(agent_id=self.agent_id)):
            print("Processing: " + request.name)
            self.request_queue.put(ScrapeResult(agent_id=self.agent_id,
                                                scrape_id=request.scrape_id,
                                                result="Query of: " + request.name))

    def readRequests(self):
        Thread(target=self.readAction).start()

    def getResults(self):
        while True:
            val = self.request_queue.get()
            self.request_queue.task_done()
            yield val

    def writeResults(self):
        self.stub.writeResponses(self.getResults())

    def __str__(self):
        return "grpc_url={0}\nproxy_url={1}\ntarget_url={2}\nagent_id={3}".format(self.grpc_url,
                                                                                  self.proxy_url,
                                                                                  self.target_url,
                                                                                  self.agent_id)


if __name__ == "__main__":
    setup_logging()

    hostname = socket.gethostname()
    default_target = "http://localhost:8000/metrics"
    parser = argparse.ArgumentParser()
    parser.add_argument("--proxy", dest=PROXY, default="localhost:50051", help="Proxy url")
    parser.add_argument("--path", dest=PATH, default=hostname, help="Path [{0}]".format(hostname))
    parser.add_argument("--target", dest=TARGET, default=default_target, help="Target url [{0}]".format(default_target))
    parser.add_argument("-v", "--verbose", dest=LOG_LEVEL, default=logging.INFO, action="store_const",
                        const=logging.DEBUG, help="Enable debugging info")
    args = vars(parser.parse_args())

    setup_logging(level=args[LOG_LEVEL])

    # Start up a server to expose the metrics.
    start_http_server(8000)

    agent = PrometheusAgent(args[PROXY], args[PATH], args[TARGET])
    agent.connect()
    print(agent)

    agent.readRequests()
    # agent.writeResults()

    while True:
        sleep(1)
