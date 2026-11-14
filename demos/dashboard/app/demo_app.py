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


def jitter():
    """A random factor around 1, so the rates wobble like real traffic."""
    return max(0.0, random.gauss(1.0, 0.15))


def failing(service):
    return modes[service] != "ok"


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
        for step in (
            self.advance_orders,
            self.advance_payments,
            self.advance_inventory,
            self.advance_shipping,
            self.advance_search,
            self.advance_recommendations,
            self.advance_notifications,
        ):
            step(load * dt)

    # Each step takes `work`: the simulated load multiplied by the seconds since the last advance.

    def advance_orders(self, work):
        orders = 12.0 * work * jitter()
        self.orders["completed"] += orders * 0.96
        self.orders["cancelled"] += orders * 0.04
        self.revenue += orders * 0.96 * random.uniform(38.0, 46.0)

    def advance_payments(self, work):
        payments = 14.0 * work * jitter()
        decline_rate = 0.25 if failing("payments") else 0.03
        for method, share in PAYMENT_METHODS.items():
            self.payments[(method, "approved")] += payments * share * (1 - decline_rate)
            self.payments[(method, "declined")] += payments * share * decline_rate
        self.payment_latency.observe_many(int(payments), slowdown=3.0 if failing("payments") else 1.0)

    def advance_inventory(self, work):
        for key in self.stock:
            self.stock[key] -= int(random.uniform(0.5, 2.5) * work)
        for key in [k for k, units in self.stock.items() if units < 120]:
            self.stock[key] += random.randint(500, 800)
            self.restocks[key[0]] += 1

    def advance_shipping(self, work):
        for shipped in self.shipped.values():
            for carrier in CARRIERS:
                shipped[carrier] += 2.0 * work * jitter()

    def advance_search(self, work):
        searches = 30.0 * work * jitter()
        self.search["hit"] += searches * 0.92
        self.search["miss"] += searches * 0.08
        self.search_latency.observe_many(int(searches))

    def advance_recommendations(self, work):
        self.recommendations["v1"] += 6.0 * work * jitter()
        self.recommendations["v2"] += 9.0 * work * jitter()

    def advance_notifications(self, work):
        sent = 20.0 * work * jitter()
        for channel, share in CHANNELS.items():
            self.notifications[channel] += sent * share

    def replica(self, name):
        return self.shipped.setdefault(name, {carrier: 0.0 for carrier in CARRIERS})


sim = Simulation()


def family(name, kind, help_text, samples):
    return [f"# HELP {name} {help_text}", f"# TYPE {name} {kind}", *samples]


def counter(name, help_text, label, values):
    """A counter family with one label, whose samples are `values`' items."""
    return family(name, "counter", help_text, [f'{name}{{{label}="{k}"}} {v:.0f}' for k, v in values.items()])


# Each service's metrics, given the replica (shipping only) and the current traffic multiplier.

def render_orders(_replica, load):
    return [
        *counter("shop_orders_total", "Orders placed, by final status.", "status", sim.orders),
        *family("shop_revenue_dollars_total", "counter", "Revenue from completed orders.",
                [f"shop_revenue_dollars_total {sim.revenue:.2f}"]),
        *family("shop_active_carts", "gauge", "Shopping carts with items in them.",
                [f"shop_active_carts {150 + 100 * (load - 1) * 2 + random.randint(-8, 8):.0f}"]),
    ]


def render_payments(_replica, _load):
    samples = [f'payments_transactions_total{{method="{m}",result="{r}"}} {v:.0f}'
               for (m, r), v in sim.payments.items()]
    return [
        *family("payments_transactions_total", "counter", "Payment transactions, by method and result.", samples),
        *family("payments_latency_seconds", "histogram", "Payment authorization latency.",
                sim.payment_latency.lines("payments_latency_seconds")),
    ]


def render_inventory(_replica, _load):
    stock = [f'inventory_stock_units{{warehouse="{w}",product="{p}"}} {v}' for (w, p), v in sim.stock.items()]
    return [
        *family("inventory_stock_units", "gauge", "Units on hand, by warehouse and product.", stock),
        *counter("inventory_restocks_total", "Restock deliveries received, by warehouse.", "warehouse", sim.restocks),
    ]


def render_catalog(_replica, _load):
    # A large payload: one price and one stock level per item, with values that change on every scrape so gzip can't
    # shrink it below the agent's chunk threshold.
    skus = [(f"SKU-{i:06d}", CATEGORIES[i % len(CATEGORIES)]) for i in range(CATALOG_ITEMS)]
    prices = [f'catalog_item_price_dollars{{sku="{sku}",category="{category}"}} {random.uniform(1, 500):.4f}'
              for sku, category in skus]
    stock = [f'catalog_item_stock_units{{sku="{sku}"}} {random.randint(0, 5000)}' for sku, _ in skus]
    return [
        *family("catalog_item_price_dollars", "gauge", "Current list price, by item.", prices),
        *family("catalog_item_stock_units", "gauge", "Units on hand, by item.", stock),
    ]


def render_shipping(replica, load):
    shipped = [f'shipping_packages_shipped_total{{replica="{replica}",carrier="{c}"}} {v:.0f}'
               for c, v in sim.replica(replica).items()]
    depth = max(0, int(40 * load + random.gauss(0, 6)))
    return [
        *family("shipping_packages_shipped_total", "counter", "Packages handed to a carrier, by replica and carrier.",
                shipped),
        *family("shipping_queue_depth", "gauge", "Packages waiting to ship, by replica.",
                [f'shipping_queue_depth{{replica="{replica}"}} {depth}']),
    ]


def render_search(_replica, _load):
    return [
        *counter("search_queries_total", "Search queries, by whether they found results.", "result", sim.search),
        *family("search_latency_seconds", "histogram", "Search query latency.",
                sim.search_latency.lines("search_latency_seconds")),
    ]


def render_recommendations(_replica, load):
    ratio = 0.08 + 0.03 * (load - 1) + random.gauss(0, 0.004)
    return [
        *counter("recommendations_served_total", "Recommendations served, by model.", "model", sim.recommendations),
        *family("recommendations_click_through_ratio", "gauge", "Share of recommendations clicked.",
                [f"recommendations_click_through_ratio {ratio:.4f}"]),
    ]


def render_notifications(_replica, _load):
    return counter("notifications_sent_total", "Notifications sent, by channel.", "channel", sim.notifications)


RENDERERS = {
    "orders": render_orders,
    "payments": render_payments,
    "inventory": render_inventory,
    "catalog": render_catalog,
    "shipping": render_shipping,
    "search": render_search,
    "recommendations": render_recommendations,
    "notifications": render_notifications,
}


def render(service, replica, now):
    return RENDERERS[service](replica, cycle(now))


def write_discovery_file(enabled):
    """Lists the discoverable services in the agent's discovery file, or none, replacing the file atomically."""
    paths = [
        {
            "name": info["name"],
            "path": f"{service}_metrics",
            "url": f"{SELF_URL}/{service}/metrics",
            # The agent's labels are a JSON string, not an object; Prometheus takes job from them.
            "labels": json.dumps({"job": service, "team": info["team"], "source": "discovered"}),
        }
        for service, info in DISCOVERABLE.items()
    ] if enabled else []
    os.makedirs(os.path.dirname(DISCOVERY_FILE), exist_ok=True)
    tmp = f"{DISCOVERY_FILE}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"paths": paths}, f, indent=2)
    os.chmod(tmp, 0o644)
    os.replace(tmp, DISCOVERY_FILE)


def parse_metrics_path(path):
    """Returns (service, replica) for a metrics path, or None."""
    match [p for p in path.split("/") if p]:
        case ["shipping", replica, "metrics"]:
            return "shipping", replica
        case [service, "metrics"] if service in SERVICES and service != "shipping":
            return service, None
    return None


def parse_control_path(path):
    """Returns (services, mode) for a /control/<service|all>/<mode> path, or None."""
    match [p for p in path.split("/") if p]:
        case ["control", "all", mode] if mode in MODES:
            return SERVICES, mode
        case ["control", service, mode] if service in SERVICES and mode in MODES:
            return [service], mode
    return None


def state():
    return json.dumps({"modes": modes, "discovery": "on" if discovery_on else "off"}, indent=2) + "\n"


def catalog_size():
    """The catalog payload's raw and gzipped sizes, for checking it is large enough to be chunked."""
    raw = "\n".join(render("catalog", None, time.time())).encode("utf-8")
    return json.dumps({"raw": len(raw), "gzipped": len(gzip.compress(raw))}) + "\n"


def set_modes(services, mode):
    for service in services:
        modes[service] = mode
    return state()


def set_discovery(enabled):
    global discovery_on
    discovery_on = enabled
    write_discovery_file(enabled)
    return state()


# GET paths other than the metrics endpoints: (content type, a function returning the body, called under the lock).
GET_ROUTES = {
    "/healthz": ("text/plain; charset=utf-8", lambda: "ok\n"),
    "/control": ("application/json", state),
    "/catalog/size": ("application/json", catalog_size),
}
DISCOVERY_ROUTES = {"/discovery/on": True, "/discovery/off": False}


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

    def do_GET(self):
        if self.path in GET_ROUTES:
            content_type, body = GET_ROUTES[self.path]
            with lock:
                text = body()
            self.send_text(200, text, content_type)
            return
        target = parse_metrics_path(self.path)
        if target is None:
            self.send_text(404, "not found\n")
            return
        self.serve_metrics(*target)

    def serve_metrics(self, service, replica):
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
        control = parse_control_path(self.path)
        if control is not None:
            with lock:
                body = set_modes(*control)
        elif self.path in DISCOVERY_ROUTES:
            with lock:
                body = set_discovery(DISCOVERY_ROUTES[self.path])
        else:
            self.send_text(404, "unknown control request\n")
            return
        self.send_text(200, body, "application/json")


def main():
    write_discovery_file(False)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    print(f"demo-app serving {', '.join(SERVICES)} on :{PORT}; discovery file {DISCOVERY_FILE}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
