#!/usr/bin/env python3
"""Simulated services for the Prometheus Proxy nginx demo.

Stands in for the applications behind the firewall that the demo's two agents scrape: a web storefront, which
agent-current serves, and a warehouse, which agent-legacy serves. Each serves Prometheus text metrics at
/<service>/metrics, and its counters advance with elapsed time.

The metrics are small on purpose: a result under 32 KB gzipped crosses in one message, so an agent's stream for results
sent in chunks carries nothing, scraped or not. This demo is about the connection between the agents and the proxy,
through nginx, not about what crosses it.

  GET /<service>/metrics    a service's metrics
  GET /healthz              liveness, for the compose healthcheck

Standard library only, so it runs on the stock python image.
"""

import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("NGINX_APP_PORT", "8000"))

# Each service, and how many units of work it finishes a second: requests for the storefront, parcels for the
# warehouse.
SERVICES = {"storefront": 12.0, "warehouse": 3.0}

START = time.time()


def metrics(service, rate):
    elapsed = time.time() - START
    return (
        f"# HELP demo_work_done_total Units of work the service has finished.\n"
        f"# TYPE demo_work_done_total counter\n"
        f'demo_work_done_total{{service="{service}"}} {int(elapsed * rate)}\n'
        f"# HELP demo_uptime_seconds Seconds since the service started.\n"
        f"# TYPE demo_uptime_seconds gauge\n"
        f'demo_uptime_seconds{{service="{service}"}} {elapsed:.0f}\n'
    )


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parts = self.path.strip("/").split("/")
        if self.path == "/healthz":
            self.reply(200, "ok\n")
        elif len(parts) == 2 and parts[1] == "metrics" and parts[0] in SERVICES:
            self.reply(200, metrics(parts[0], SERVICES[parts[0]]), "text/plain; version=0.0.4; charset=utf-8")
        else:
            self.reply(404, "not found\n")

    def reply(self, status, text, content_type="text/plain; charset=utf-8"):
        body = text.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
