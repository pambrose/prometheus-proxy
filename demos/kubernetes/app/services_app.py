#!/usr/bin/env python3
"""Simulated services for the Prometheus Proxy Kubernetes demo.

Stands in for the applications in the target cluster (pp-target) that the demo's agent scrapes. Each pod runs one
service, named by SERVICE: orders, payments, and shipping, which `make up` deploys, and search, which `demo.sh
add-service` deploys. Every service serves Prometheus text metrics at /metrics on PORT, behind a ClusterIP Service in
the apps namespace. Counters advance with elapsed time on a compressed "day" (DAY_SECS), so the graphs move during a
short demo.

  GET /metrics    the service's metrics
  GET /healthz    liveness, for the pod's readiness probe

A trimmed copy of the network demo's app/network_app.py: one service per process, and no large payload, since this
demo is about the deployment, not the link. Standard library only, so it runs on the stock python image.
"""

import math
import os
import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SERVICE = os.environ.get("SERVICE", "orders")
PORT = int(os.environ.get("PORT", "8000"))
# One simulated day, in seconds: traffic peaks and dips once per DAY_SECS.
DAY_SECS = float(os.environ.get("DAY_SECS", "300"))

LATENCY_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
PAYMENT_METHODS = {"card": 0.7, "wallet": 0.2, "bank": 0.1}
CARRIERS = ("ups", "fedex", "usps")

START = time.time()
lock = threading.Lock()


def cycle(now):
    """The traffic multiplier at `now`: 0.5 at the simulated night, 1.5 at noon."""
    return 1.0 + 0.5 * math.sin(2 * math.pi * (now - START) / DAY_SECS)


# The simulation's randomness only makes the graphs move, so the random module's generator is the right one. Bandit's
# B311 (meant for tokens, keys, and the like) flags it, and is waived on each line that draws.
def randint(low, high):
    return random.randint(low, high)  # nosec B311


def jitter():
    """A random factor around 1, so the rates wobble like real traffic."""
    return max(0.0, random.gauss(1.0, 0.15))  # nosec B311


class Histogram:
    def __init__(self, median):
        self.median = median
        self.buckets = [0] * len(LATENCY_BUCKETS)
        self.count = 0
        self.total = 0.0

    def observe_many(self, n):
        # Cap the per-tick work; the shape matters, not the exact count.
        for _ in range(min(n, 2000)):
            value = random.lognormvariate(math.log(self.median), 0.6)  # nosec B311
            self.count += 1
            self.total += value
            for i, bound in enumerate(LATENCY_BUCKETS):
                if value <= bound:
                    self.buckets[i] += 1

    def lines(self, name):
        out = [f'{name}_bucket{{le="{bound}"}} {count}' for bound, count in zip(LATENCY_BUCKETS, self.buckets)]
        out.append(f'{name}_bucket{{le="+Inf"}} {self.count}')
        out.append(f"{name}_sum {self.total:.6f}")
        out.append(f"{name}_count {self.count}")
        return out


class Simulation:
    """The service's state, advanced on each scrape by the time since the last one."""

    def __init__(self):
        self.last = time.time()
        self.orders = {"completed": 0.0, "cancelled": 0.0}
        self.payments = {(m, r): 0.0 for m in PAYMENT_METHODS for r in ("approved", "declined")}
        self.payment_latency = Histogram(0.18)
        self.shipped = {carrier: 0.0 for carrier in CARRIERS}
        self.queries = 0.0
        self.query_latency = Histogram(0.04)

    def advance(self, now):
        work = cycle(now) * max(0.0, now - self.last)
        self.last = now
        orders = 12.0 * work * jitter()
        self.orders["completed"] += orders * 0.96
        self.orders["cancelled"] += orders * 0.04
        payments = 14.0 * work * jitter()
        for method, share in PAYMENT_METHODS.items():
            self.payments[(method, "approved")] += payments * share * 0.97
            self.payments[(method, "declined")] += payments * share * 0.03
        self.payment_latency.observe_many(int(payments))
        for carrier in CARRIERS:
            self.shipped[carrier] += 2.0 * work * jitter()
        queries = 40.0 * work * jitter()
        self.queries += queries
        self.query_latency.observe_many(int(queries))


sim = Simulation()


def family(name, kind, help_text, samples):
    return [f"# HELP {name} {help_text}", f"# TYPE {name} {kind}", *samples]


def render_orders(load):
    return [
        *family("shop_orders_total", "counter", "Orders placed, by final status.",
                [f'shop_orders_total{{status="{k}"}} {v:.0f}' for k, v in sim.orders.items()]),
        *family("shop_active_carts", "gauge", "Shopping carts with items in them.",
                [f"shop_active_carts {150 + 200 * (load - 1) + randint(-8, 8):.0f}"]),
    ]


def render_payments(_load):
    samples = [f'payments_transactions_total{{method="{m}",result="{r}"}} {v:.0f}'
               for (m, r), v in sim.payments.items()]
    return [
        *family("payments_transactions_total", "counter", "Payment transactions, by method and result.", samples),
        *family("payments_latency_seconds", "histogram", "Payment authorization latency.",
                sim.payment_latency.lines("payments_latency_seconds")),
    ]


def render_shipping(load):
    depth = max(0, int(40 * load + random.gauss(0, 6)))  # nosec B311
    return [
        *family("shipping_packages_shipped_total", "counter", "Packages handed to a carrier, by carrier.",
                [f'shipping_packages_shipped_total{{carrier="{c}"}} {v:.0f}' for c, v in sim.shipped.items()]),
        *family("shipping_queue_depth", "gauge", "Packages waiting to ship.", [f"shipping_queue_depth {depth}"]),
    ]


def render_search(load):
    return [
        *family("search_queries_total", "counter", "Search queries answered.",
                [f"search_queries_total {sim.queries:.0f}"]),
        *family("search_latency_seconds", "histogram", "Search query latency.",
                sim.query_latency.lines("search_latency_seconds")),
        *family("search_index_documents", "gauge", "Documents in the search index.",
                [f"search_index_documents {120000 + int(2000 * load)}"]),
    ]


RENDERERS = {
    "orders": render_orders,
    "payments": render_payments,
    "shipping": render_shipping,
    "search": render_search,
}


class Handler(BaseHTTPRequestHandler):
    server_version = "services-app"

    def log_request(self, code="-", size="-"):
        # Scrapes arrive every few seconds, and none is worth a log line; errors still log, through log_error.
        pass

    def send_text(self, status, body, content_type="text/plain; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/healthz":
            self.send_text(200, "ok\n")
        elif self.path == "/metrics":
            now = time.time()
            with lock:
                sim.advance(now)
                lines = RENDERERS[SERVICE](cycle(now))
            self.send_text(200, "\n".join(lines) + "\n", "text/plain; version=0.0.4; charset=utf-8")
        else:
            self.send_text(404, "not found\n")


def main():
    if SERVICE not in RENDERERS:
        raise SystemExit(f"SERVICE must be one of {', '.join(RENDERERS)}, not '{SERVICE}'")
    # All interfaces, so the agent reaches it through its Service; nothing outside the cluster can.
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)  # nosec B104
    server.daemon_threads = True
    print(f"services-app serving {SERVICE} on :{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
