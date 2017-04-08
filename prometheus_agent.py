import argparse
import logging
import socket

import grpc

from constants import LOG_LEVEL, PROXY, PATH
from proto.proxy_service_pb2 import ProxyServiceStub
from proto.proxy_service_pb2 import RegisterRequest
from utils import setup_logging, grpc_url


class PrometheusAgent(object):
    def __init__(self, hostname):
        self.url = grpc_url(hostname)
        channel = grpc.insecure_channel(self.url)
        self.stub = ProxyServiceStub(channel)
        self.agent_info = RegisterRequest(hostname=socket.gethostname())

    def connect(self):
        register_response = self.stub.registerAgent(self.agent_info)
        print("Agent id: {0}".format(register_response.agent_id))


if __name__ == "__main__":
    setup_logging()

    hostname = socket.gethostname()
    parser = argparse.ArgumentParser()
    parser.add_argument("--proxy", dest=PROXY, default="localhost:50051", help="Proxy url")
    parser.add_argument("--path", dest=PATH, default=hostname, help="Target path [{0}]".format(hostname))
    parser.add_argument("-v", "--verbose", dest=LOG_LEVEL, default=logging.INFO, action="store_const",
                        const=logging.DEBUG, help="Enable debugging info")
    args = vars(parser.parse_args())

    setup_logging(level=args[LOG_LEVEL])

    agent = PrometheusAgent(args[PROXY])
    agent.connect()
