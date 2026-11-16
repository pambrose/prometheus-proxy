#!/usr/bin/env python3
"""Simulated services for the Prometheus Proxy network demo.

Stands in for the applications behind the firewall that the demo's two agents scrape: orders and payments at
headquarters, and inventory and shipping at a branch office. Every service serves Prometheus text metrics at
/<service>/metrics. Counters advance with elapsed time on a compressed "day" (DAY_SECS), so the graphs move during a
short demo.

The inventory payload is the large one: one price and one stock level per item, about 480,000 characters, so the
branch's agent sends it to the proxy in chunks, and a thin link can't carry it within Prometheus's scrape timeout. Its
per-item price list is most of it, and no dashboard uses it, which is what `demo.sh filter` drops at the agent.

  GET /<service>/metrics    a service's metrics
  GET /inventory/size       the inventory payload's raw and gzipped sizes, as JSON
  GET /healthz              liveness, for the compose healthcheck

Unlike the dashboard demo's app, it has no control API: this demo breaks the network, not the services. Standard
library only, so it runs on the stock python image.
"""

import gzip
import json
import math
import os
import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("NETWORK_APP_PORT", "8000"))
# The inventory's item count, sized so its payload is about 480,000 characters and its gzipped size exceeds the
# agent's 32 KB chunk threshold.
INVENTORY_ITEMS = int(os.environ.get("INVENTORY_ITEMS", "4000"))
# One simulated day, in seconds: traffic peaks and dips once per DAY_SECS.
DAY_SECS = float(os.environ.get("DAY_SECS", "300"))

SERVICES = ("orders", "payments", "inventory", "shipping")
# Each service's typical response time, in seconds. They're short, so what a scrape's time shows is the network.
BASE_LATENCY = {"orders": 0.02, "payments": 0.04, "inventory": 0.05, "shipping": 0.02}
LATENCY_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)

CATEGORIES = ("electronics", "books", "garden", "toys", "kitchen", "sports", "apparel", "office")
PAYMENT_METHODS = {"card": 0.7, "wallet": 0.2, "bank": 0.1}
CARRIERS = ("ups", "fedex", "usps")

START = time.time()
lock = threading.Lock()


def cycle(now):
    """The traffic multiplier at `now`: 0.5 at the simulated night, 1.5 at noon."""
    return 1.0 + 0.5 * math.sin(2 * math.pi * (now - START) / DAY_SECS)


# The simulation's randomness only makes the graphs move, so the random module's generator is the right one. Bandit's
# B311 (meant for tokens, keys, and the like) flags it, and is waived on each line that draws.
def uniform(low, high):
    return random.uniform(low, high)  # nosec B311


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
    """Every service's state, advanced on each scrape by the time since the last one."""

    def __init__(self):
        self.last = time.time()
        self.orders = {"completed": 0.0, "cancelled": 0.0}
        self.payments = {(m, r): 0.0 for m in PAYMENT_METHODS for r in ("approved", "declined")}
        self.payment_latency = Histogram(0.18)
        self.shipped = {carrier: 0.0 for carrier in CARRIERS}

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


def render_inventory(_load):
    # A large payload: one price and one stock level per item, with values that change on every scrape so gzip can't
    # shrink it below the agent's chunk threshold. The price list is most of it.
    skus = [(f"SKU-{i:06d}", CATEGORIES[i % len(CATEGORIES)]) for i in range(INVENTORY_ITEMS)]
    prices = [f'inventory_item_price_dollars{{sku="{sku}",category="{category}"}} {uniform(1, 500):.4f}'
              for sku, category in skus]
    stock = [f'inventory_item_stock_units{{sku="{sku}"}} {randint(0, 5000)}' for sku, _ in skus]
    return [
        *family("inventory_item_price_dollars", "gauge", "Current list price, by item.", prices),
        *family("inventory_item_stock_units", "gauge", "Units on hand, by item.", stock),
    ]


def render_shipping(load):
    depth = max(0, int(40 * load + random.gauss(0, 6)))  # nosec B311
    return [
        *family("shipping_packages_shipped_total", "counter", "Packages handed to a carrier, by carrier.",
                [f'shipping_packages_shipped_total{{carrier="{c}"}} {v:.0f}' for c, v in sim.shipped.items()]),
        *family("shipping_queue_depth", "gauge", "Packages waiting to ship.", [f"shipping_queue_depth {depth}"]),
    ]


RENDERERS = {
    "orders": render_orders,
    "payments": render_payments,
    "inventory": render_inventory,
    "shipping": render_shipping,
}


def render(service, now):
    return RENDERERS[service](cycle(now))


def inventory_size():
    """The inventory payload's raw and gzipped sizes, for checking it is large enough to be chunked."""
    text = "\n".join(render("inventory", time.time())) + "\n"
    raw = text.encode("utf-8")
    filtered = "\n".join(line for line in text.splitlines() if "inventory_item_price_dollars" not in line) + "\n"
    return json.dumps({
        "raw": len(raw),
        "gzipped": len(gzip.compress(raw)),
        "filtered_raw": len(filtered.encode("utf-8")),
        "filtered_gzipped": len(gzip.compress(filtered.encode("utf-8"))),
    }) + "\n"


def parse_metrics_path(path):
    """Returns the service a /<service>/metrics path names, or None."""
    match [p for p in path.split("/") if p]:
        case [service, "metrics"] if service in SERVICES:
            return service
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "network-app"

    def log_request(self, code="-", size="-"):
        # Scrapes arrive several times a second, and none is worth a log line; errors still log, through log_error.
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
            return
        if self.path == "/inventory/size":
            with lock:
                body = inventory_size()
            self.send_text(200, body, "application/json")
            return
        service = parse_metrics_path(self.path)
        if service is None:
            self.send_text(404, "not found\n")
            return
        time.sleep(BASE_LATENCY[service] * uniform(0.5, 1.5))
        now = time.time()
        with lock:
            sim.advance(now)
            lines = render(service, now)
        self.send_text(200, "\n".join(lines) + "\n", "text/plain; version=0.0.4; charset=utf-8")


def main():
    # All interfaces, so the agents reach it over the compose network; docker/compose.yml publishes none of its ports.
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)  # nosec B104
    server.daemon_threads = True
    print(f"network-app serving {', '.join(SERVICES)} on :{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
