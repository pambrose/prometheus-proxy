#!/usr/bin/env python3
"""Simulated services for the Prometheus Proxy dashboard demo.

Stands in for the applications behind a firewall that the demo's agents scrape. Every service serves Prometheus text
metrics at /<service>/metrics, and the shipping service has one replica per west agent at /shipping/<replica>/metrics.
Counters advance with elapsed time on a compressed "day" (DAY_SECS), so the graphs move during a short demo.

demo.sh drives the failure scenarios and dynamic discovery through the control API:

  POST /control/<service>/<ok|error|slow>   error answers 500; slow hangs past the agent's scrape timeout
  POST /control/all/ok                      returns every service to normal
  POST /discovery/<on|off>                  lists the discoverable services in the agent's discovery file, or none
  GET  /control                             the current modes and discovery state, as JSON
  GET  /healthz                             liveness, for the compose healthcheck

Standard library only, so it runs on the stock python image.
"""

import gzip
import json
import math
import os
import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("DEMO_APP_PORT", "8000"))
DISCOVERY_FILE = os.environ.get("DISCOVERY_FILE", "/discovery/targets.json")
# How the agent reaches this app on the compose network, for the URLs written to the discovery file.
SELF_URL = os.environ.get("DEMO_APP_URL", "http://demo-app:8000")
# The catalog's item count, sized so its gzipped payload exceeds the agent's 32 KB chunk threshold.
CATALOG_ITEMS = int(os.environ.get("CATALOG_ITEMS", "4000"))
# One simulated day, in seconds: traffic peaks and dips once per DAY_SECS.
DAY_SECS = float(os.environ.get("DAY_SECS", "300"))
# How long a slow service hangs; longer than the demo agents' 3s scrapeTimeoutSecs.
SLOW_SECS = float(os.environ.get("SLOW_SECS", "10"))

STATIC_SERVICES = ["orders", "payments", "inventory", "catalog", "shipping"]
# Services the agent doesn't know about until `demo.sh discover` lists them in the discovery file.
DISCOVERABLE = {
    "search": {"name": "Search service", "team": "discovery"},
    "recommendations": {"name": "Recommendations service", "team": "discovery"},
    "notifications": {"name": "Notifications service", "team": "messaging"},
}
SERVICES = STATIC_SERVICES + list(DISCOVERABLE)
MODES = ("ok", "error", "slow")
# Each service's typical response time, in seconds, so the proxy's per-path latency differs.
BASE_LATENCY = {
    "orders": 0.03,
    "payments": 0.08,
    "inventory": 0.05,
    "catalog": 0.25,
    "shipping": 0.04,
    "search": 0.06,
    "recommendations": 0.12,
    "notifications": 0.02,
}
LATENCY_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)

CATEGORIES = ("electronics", "books", "garden", "toys", "kitchen", "sports", "apparel", "office")
WAREHOUSES = ("east-1", "east-2")
PRODUCTS = ("widgets", "gadgets", "gizmos", "doohickeys")
CARRIERS = ("ups", "fedex", "usps")
PAYMENT_METHODS = {"card": 0.7, "wallet": 0.2, "bank": 0.1}
CHANNELS = {"email": 0.5, "push": 0.35, "sms": 0.15}

START = time.time()
lock = threading.Lock()
modes = {service: "ok" for service in SERVICES}
discovery_on = False


def cycle(now):
    """The traffic multiplier at `now`: 0.5 at the simulated night, 1.5 at noon."""
    return 1.0 + 0.5 * math.sin(2 * math.pi * (now - START) / DAY_SECS)


class Histogram:
    def __init__(self, median):
        self.median = median
        self.buckets = [0] * len(LATENCY_BUCKETS)
        self.count = 0
        self.total = 0.0

    def observe_many(self, n, slowdown=1.0):
        # Cap the per-tick work; the shape matters, not the exact count.
        for _ in range(min(n, 2000)):
            value = random.lognormvariate(math.log(self.median * slowdown), 0.6)
            self.count += 1
            self.total += value
            for i, bound in enumerate(LATENCY_BUCKETS):
                if value <= bound:
                    self.buckets[i] += 1

    def lines(self, name, labels=""):
        sep = "," if labels else ""
        out = []
        for bound, count in zip(LATENCY_BUCKETS, self.buckets):
            out.append(f'{name}_bucket{{{labels}{sep}le="{bound}"}} {count}')
        out.append(f'{name}_bucket{{{labels}{sep}le="+Inf"}} {self.count}')
        out.append(f"{name}_sum{{{labels}}} {self.total:.6f}" if labels else f"{name}_sum {self.total:.6f}")
        out.append(f"{name}_count{{{labels}}} {self.count}" if labels else f"{name}_count {self.count}")
        return out


class Simulation:
    """Every service's state, advanced on each scrape by the time since the last one."""

    def __init__(self):
        self.last = time.time()
        self.orders = {"completed": 0.0, "cancelled": 0.0}
        self.revenue = 0.0
        self.payments = {(m, r): 0.0 for m in PAYMENT_METHODS for r in ("approved", "declined")}
        self.payment_latency = Histogram(0.18)
        self.stock = {(w, p): random.randint(400, 900) for w in WAREHOUSES for p in PRODUCTS}
        self.restocks = {w: 0 for w in WAREHOUSES}
        self.shipped = {}
        self.search = {"hit": 0.0, "miss": 0.0}
        self.search_latency = Histogram(0.04)
        self.recommendations = {"v1": 0.0, "v2": 0.0}
        self.notifications = {c: 0.0 for c in CHANNELS}

    def advance(self, now):
        dt = max(0.0, now - self.last)
        self.last = now
        load = cycle(now)

        def jitter():
            return max(0.0, random.gauss(1.0, 0.15))

        orders = 12.0 * load * dt * jitter()
        self.orders["completed"] += orders * 0.96
        self.orders["cancelled"] += orders * 0.04
        self.revenue += orders * 0.96 * random.uniform(38.0, 46.0)

        payments = 14.0 * load * dt * jitter()
        decline_rate = 0.25 if modes["payments"] != "ok" else 0.03
        for method, share in PAYMENT_METHODS.items():
            self.payments[(method, "approved")] += payments * share * (1 - decline_rate)
            self.payments[(method, "declined")] += payments * share * decline_rate
        self.payment_latency.observe_many(int(payments), slowdown=3.0 if modes["payments"] != "ok" else 1.0)

        for key in self.stock:
            self.stock[key] -= int(random.uniform(0.5, 2.5) * load * dt)
            if self.stock[key] < 120:
                self.stock[key] += random.randint(500, 800)
                self.restocks[key[0]] += 1

        for replica in self.shipped:
            for carrier in CARRIERS:
                self.shipped[replica][carrier] += 2.0 * load * dt * jitter()

        searches = 30.0 * load * dt * jitter()
        self.search["hit"] += searches * 0.92
        self.search["miss"] += searches * 0.08
        self.search_latency.observe_many(int(searches))

        self.recommendations["v1"] += 6.0 * load * dt * jitter()
        self.recommendations["v2"] += 9.0 * load * dt * jitter()

        sent = 20.0 * load * dt * jitter()
        for channel, share in CHANNELS.items():
            self.notifications[channel] += sent * share

    def replica(self, name):
        return self.shipped.setdefault(name, {carrier: 0.0 for carrier in CARRIERS})


sim = Simulation()


def family(name, kind, help_text, samples):
    return [f"# HELP {name} {help_text}", f"# TYPE {name} {kind}", *samples]


def render(service, replica, now):
    load = cycle(now)
    if service == "orders":
        return [
            *family(
                "shop_orders_total",
                "counter",
                "Orders placed, by final status.",
                [f'shop_orders_total{{status="{s}"}} {v:.0f}' for s, v in sim.orders.items()],
            ),
            *family("shop_revenue_dollars_total", "counter", "Revenue from completed orders.",
                    [f"shop_revenue_dollars_total {sim.revenue:.2f}"]),
            *family("shop_active_carts", "gauge", "Shopping carts with items in them.",
                    [f"shop_active_carts {150 + 100 * (load - 1) * 2 + random.randint(-8, 8):.0f}"]),
        ]
    if service == "payments":
        return [
            *family(
                "payments_transactions_total",
                "counter",
                "Payment transactions, by method and result.",
                [f'payments_transactions_total{{method="{m}",result="{r}"}} {v:.0f}'
                 for (m, r), v in sim.payments.items()],
            ),
            *family("payments_latency_seconds", "histogram", "Payment authorization latency.",
                    sim.payment_latency.lines("payments_latency_seconds")),
        ]
    if service == "inventory":
        return [
            *family(
                "inventory_stock_units",
                "gauge",
                "Units on hand, by warehouse and product.",
                [f'inventory_stock_units{{warehouse="{w}",product="{p}"}} {v}' for (w, p), v in sim.stock.items()],
            ),
            *family(
                "inventory_restocks_total",
                "counter",
                "Restock deliveries received, by warehouse.",
                [f'inventory_restocks_total{{warehouse="{w}"}} {v}' for w, v in sim.restocks.items()],
            ),
        ]
    if service == "catalog":
        # A large payload: one price and one stock level per item, with values that change on every scrape so gzip
        # can't shrink it below the agent's chunk threshold.
        prices = []
        stock = []
        for i in range(CATALOG_ITEMS):
            sku = f"SKU-{i:06d}"
            category = CATEGORIES[i % len(CATEGORIES)]
            prices.append(f'catalog_item_price_dollars{{sku="{sku}",category="{category}"}} '
                          f"{random.uniform(1, 500):.4f}")
            stock.append(f'catalog_item_stock_units{{sku="{sku}"}} {random.randint(0, 5000)}')
        return [
            *family("catalog_item_price_dollars", "gauge", "Current list price, by item.", prices),
            *family("catalog_item_stock_units", "gauge", "Units on hand, by item.", stock),
        ]
    if service == "shipping":
        shipped = sim.replica(replica)
        return [
            *family(
                "shipping_packages_shipped_total",
                "counter",
                "Packages handed to a carrier, by replica and carrier.",
                [f'shipping_packages_shipped_total{{replica="{replica}",carrier="{c}"}} {v:.0f}'
                 for c, v in shipped.items()],
            ),
            *family("shipping_queue_depth", "gauge", "Packages waiting to ship, by replica.",
                    [f'shipping_queue_depth{{replica="{replica}"}} {max(0, int(40 * load + random.gauss(0, 6)))}']),
        ]
    if service == "search":
        return [
            *family("search_queries_total", "counter", "Search queries, by whether they found results.",
                    [f'search_queries_total{{result="{r}"}} {v:.0f}' for r, v in sim.search.items()]),
            *family("search_latency_seconds", "histogram", "Search query latency.",
                    sim.search_latency.lines("search_latency_seconds")),
        ]
    if service == "recommendations":
        return [
            *family("recommendations_served_total", "counter", "Recommendations served, by model.",
                    [f'recommendations_served_total{{model="{m}"}} {v:.0f}'
                     for m, v in sim.recommendations.items()]),
            *family("recommendations_click_through_ratio", "gauge", "Share of recommendations clicked.",
                    [f"recommendations_click_through_ratio {0.08 + 0.03 * (load - 1) + random.gauss(0, 0.004):.4f}"]),
        ]
    if service == "notifications":
        return [
            *family("notifications_sent_total", "counter", "Notifications sent, by channel.",
                    [f'notifications_sent_total{{channel="{c}"}} {v:.0f}' for c, v in sim.notifications.items()]),
        ]
    return []


def write_discovery_file(enabled):
    """Lists the discoverable services in the agent's discovery file, or none, replacing the file atomically."""
    paths = []
    if enabled:
        for service, info in DISCOVERABLE.items():
            paths.append({
                "name": info["name"],
                "path": f"{service}_metrics",
                "url": f"{SELF_URL}/{service}/metrics",
                # The agent's labels are a JSON string, not an object; Prometheus takes job from them.
                "labels": json.dumps({"job": service, "team": info["team"], "source": "discovered"}),
            })
    os.makedirs(os.path.dirname(DISCOVERY_FILE), exist_ok=True)
    tmp = f"{DISCOVERY_FILE}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"paths": paths}, f, indent=2)
    os.chmod(tmp, 0o644)
    os.replace(tmp, DISCOVERY_FILE)


def parse_metrics_path(path):
    """Returns (service, replica) for a metrics path, or None."""
    parts = [p for p in path.split("/") if p]
    if len(parts) == 2 and parts[1] == "metrics" and parts[0] in SERVICES and parts[0] != "shipping":
        return parts[0], None
    if len(parts) == 3 and parts[0] == "shipping" and parts[2] == "metrics":
        return "shipping", parts[1]
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "demo-app"

    def log_message(self, fmt, *args):
        # Only control calls are worth a log line; scrapes arrive several times a second.
        if self.path.startswith(("/control", "/discovery")):
            super().log_message(fmt, *args)

    def send_text(self, status, body, content_type="text/plain; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def state(self):
        return json.dumps({"modes": modes, "discovery": "on" if discovery_on else "off"}, indent=2) + "\n"

    def do_GET(self):
        if self.path == "/healthz":
            self.send_text(200, "ok\n")
            return
        if self.path == "/control":
            with lock:
                body = self.state()
            self.send_text(200, body, "application/json")
            return
        if self.path == "/catalog/size":
            # The catalog payload's raw and gzipped sizes, for checking it is large enough to be chunked.
            with lock:
                raw = "\n".join(render("catalog", None, time.time())).encode("utf-8")
            self.send_text(200, json.dumps({"raw": len(raw), "gzipped": len(gzip.compress(raw))}) + "\n",
                           "application/json")
            return

        target = parse_metrics_path(self.path)
        if target is None:
            self.send_text(404, "not found\n")
            return
        service, replica = target
        with lock:
            mode = modes[service]
        if mode == "error":
            self.send_text(500, f"{service} is failing (demo outage)\n")
            return
        time.sleep(SLOW_SECS if mode == "slow" else BASE_LATENCY[service] * random.uniform(0.5, 1.5))
        now = time.time()
        with lock:
            sim.advance(now)
            lines = render(service, replica, now)
        self.send_text(200, "\n".join(lines) + "\n", "text/plain; version=0.0.4; charset=utf-8")

    def do_POST(self):
        global discovery_on
        parts = [p for p in self.path.split("/") if p]
        if len(parts) == 3 and parts[0] == "control" and parts[2] in MODES and (parts[1] in SERVICES or parts[1] == "all"):
            with lock:
                for service in SERVICES if parts[1] == "all" else [parts[1]]:
                    modes[service] = parts[2]
                body = self.state()
            self.send_text(200, body, "application/json")
            return
        if len(parts) == 2 and parts[0] == "discovery" and parts[1] in ("on", "off"):
            with lock:
                discovery_on = parts[1] == "on"
                write_discovery_file(discovery_on)
                body = self.state()
            self.send_text(200, body, "application/json")
            return
        self.send_text(404, "unknown control request\n")


def main():
    write_discovery_file(False)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    print(f"demo-app serving {', '.join(SERVICES)} on :{PORT}; discovery file {DISCOVERY_FILE}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
