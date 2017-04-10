import argparse
import logging
import socket
import time
from concurrent import futures
from queue import Queue
from threading import Thread, Lock, Event

import grpc
import requests
from flask import Flask, Response
from prometheus_client import start_http_server, Counter
from werkzeug.exceptions import abort

from constants import GRPC_PORT_DEFAULT, PORT, LOG_LEVEL, PROXY_PORT_DEFAULT, GRPC
from proto.proxy_service_pb2 import AgentRegisterResponse, PathRegisterResponse
from proto.proxy_service_pb2 import ProxyServiceServicer, ScrapeRequest
from proto.proxy_service_pb2 import add_ProxyServiceServicer_to_server
from utils import setup_logging

logger = logging.getLogger(__name__)

REQUEST_COUNTER = Counter('getDistances_request_type_count', 'getDistances() request type count', ['target'])


class PrometheusProxy(ProxyServiceServicer):
    def __init__(self, grpc_port, http_port):
        self.hostname = "[::]:{0}".format(grpc_port if grpc_port else GRPC_PORT_DEFAULT)
        self.http_port = http_port
        self.stopped = False
        self.grpc_server = None
        self.agent_id_lock = Lock()
        self.agent_id_counter = 0
        self.path_id_lock = Lock()
        self.path_id_counter = 0
        self.scrape_id_lock = Lock()
        self.scrape_id_counter = 0
        # Map agent_id to AgentContext
        self.agent_dict = {}
        # Map path to agent_id
        self.path_dict = {}

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.stop()
        return self

    def start(self):
        Thread(target=self.run_grpc_server, daemon=True).start()

    def stop(self):
        if not self.stopped:
            logger.info("Stopping proxy")
            self.stopped = True
            self.grpc_server.stop(0)
        return self

    def run_grpc_server(self):
        logger.info("Starting gRPC service listening on %s", self.hostname)
        self.grpc_server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        add_ProxyServiceServicer_to_server(self, self.grpc_server)
        self.grpc_server.add_insecure_port(self.hostname)
        self.grpc_server.start()

    def registerAgent(self, request, context):
        with self.agent_id_lock:
            self.agent_id_counter += 1
            logger.info("Registered agent %s %s %s", request.hostname, context.peer(), self.agent_id_counter)
            self.agent_dict[self.agent_id_counter] = AgentContext(self.agent_id_counter)
            return AgentRegisterResponse(agent_id=self.agent_id_counter,
                                         proxy_url="http://{0}:{1}/".format(socket.gethostname(), self.http_port))

    def registerPath(self, request, context):
        with self.path_id_lock:
            self.path_id_counter += 1
            logger.info("Registered path %s", request.path)
            self.path_dict[request.path] = request.agent_id
            return PathRegisterResponse(path_id=self.path_id_counter)

    def readRequestsFromProxy(self, request, context):
        agent_id = request.agent_id
        agent_context = self.agent_dict[agent_id]
        req_queue = agent_context.request_queue
        while True:
            req = req_queue.get()
            req_queue.task_done()
            logger.info("Sending scrape_id:{0} {1} to agent".format(req.scrape_id, req.path))
            yield req

    def writeResponsesToProxy(self, request_iterator, context):
        for result in request_iterator:
            logger.info("Received scrape_id:{0} from agent".format(result.scrape_id))
            agent_id = result.agent_id
            agent_context = self.agent_dict[agent_id]
            req_dict = agent_context.request_dict
            request_entry = req_dict[result.scrape_id]
            request_entry.result = result
            request_entry.ready.set()

    def fetch_metrics(self, path):
        logger.info("Request for {0}".format(path))
        with self.scrape_id_lock:
            self.scrape_id_counter += 1
            req = ScrapeRequest(scrape_id=self.scrape_id_counter, path=path)
            request_entry = RequestEntry(req)
            agent_id = self.path_dict[path]
            agent_context = self.agent_dict[agent_id]
            agent_context.request_dict[self.scrape_id_counter] = request_entry
        agent_context.request_queue.put(req)
        return request_entry


class AgentContext(object):
    def __init__(self, agent_id):
        self.agent_id = agent_id
        self.request_queue = Queue()
        self.request_dict = {}


class RequestEntry(object):
    def __init__(self, request):
        self.request = request
        self.ready = Event()
        self.result = None


if __name__ == "__main__":
    setup_logging()

    parser = argparse.ArgumentParser()
    parser.add_argument("-p", "--port", dest=PORT, type=int, default=PROXY_PORT_DEFAULT, help="Proxy listening port")
    parser.add_argument("-g", "--grpc", dest=GRPC, type=int, default=GRPC_PORT_DEFAULT, help="gRPC listening port")
    parser.add_argument("-v", "--verbose", dest=LOG_LEVEL, default=logging.INFO, action="store_const",
                        const=logging.DEBUG, help="Enable debugging info")
    args = vars(parser.parse_args())

    setup_logging(level=args[LOG_LEVEL])

    with PrometheusProxy(args[GRPC], args[PORT]) as proxy:
        http = Flask(__name__)


        @http.route("/<path>")
        def target_request(path):
            if path == "_metrics":
                resp = requests.get("http://localhost:8001/metrics")
                return Response(resp.text,
                                status=resp.status_code,
                                mimetype='text/plain',
                                headers={"cache-control": "no-cache"})
            else:
                request_entry = proxy.fetch_metrics(path)
                request_entry.ready.wait()
                scrape_result = request_entry.result
                if scrape_result.valid:
                    return Response(scrape_result.text,
                                    status=scrape_result.status_code,
                                    mimetype='text/plain',
                                    headers={"cache-control": "no-cache"})
                else:
                    logger.error("Error processing %s [%s]", path, scrape_result.text)
                    abort(404)


        # Run HTTP server in a thread
        Thread(target=http.run, daemon=True, kwargs={"port": args[PORT]}).start()

        # Start up a server to expose the metrics.
        start_http_server(8001)

        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
