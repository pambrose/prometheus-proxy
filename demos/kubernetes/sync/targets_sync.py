#!/usr/bin/env python3
"""targets-sync: writes the Prometheus Proxy agent's discovery targets from annotated Kubernetes Services.

Part of the Kubernetes demo (demos/kubernetes), not of the product. It runs in the target cluster (pp-target), lists and
watches the Services in one namespace (WATCH_NAMESPACE, apps), and writes every one annotated prometheus-proxy/path
into the agent's targets ConfigMap (CONFIGMAP_NAME, agent-targets, key targets.conf), which the agent chart mounts and
the agent re-reads on its discovery interval. So annotating a Service is all it takes for Prometheus to scrape it,
through the proxy, and nothing restarts. A discovery source built into the agent that read Services itself would
replace this script.

A Service's annotations:

  prometheus-proxy/path            the path the agent registers with the proxy (required)
  prometheus-proxy/port            the Service port to scrape (default: its first port)
  prometheus-proxy/metrics-path    the target's metrics path (default: /metrics)

It talks to the Kubernetes API with its pod's service-account token, which its RBAC (k8s/target.yaml) lets list and
watch Services in WATCH_NAMESPACE and get and update the one ConfigMap, nothing more: it can't create the ConfigMap,
which `make up` creates empty. Each list, at the start and whenever a watch ends (every WATCH_SECS), writes the
ConfigMap again if it differs, so an edit to it by hand is undone. Standard library only, so it runs on the stock
python image.
"""

import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.request

SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"
API = f"https://{os.environ.get('KUBERNETES_SERVICE_HOST', 'kubernetes.default.svc')}:" \
      f"{os.environ.get('KUBERNETES_SERVICE_PORT', '443')}"
WATCH_NAMESPACE = os.environ.get("WATCH_NAMESPACE", "apps")
CONFIGMAP_NAME = os.environ.get("CONFIGMAP_NAME", "agent-targets")
TARGETS_KEY = "targets.conf"
# How long each watch lasts before the script lists the Services again and checks the ConfigMap.
WATCH_SECS = int(os.environ.get("WATCH_SECS", "60"))

PATH_ANNOTATION = "prometheus-proxy/path"
PORT_ANNOTATION = "prometheus-proxy/port"
METRICS_PATH_ANNOTATION = "prometheus-proxy/metrics-path"
# A path the proxy can serve: no whitespace, control characters, '?', '#', or '/', which it would refuse or Prometheus
# couldn't scrape.
VALID_PATH = re.compile(r"[A-Za-z0-9_.:-]+")


def log(message):
    print(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} targets-sync: {message}", flush=True)


def read_file(name):
    with open(os.path.join(SA_DIR, name), encoding="utf-8") as f:
        return f.read().strip()


# The ConfigMap is in the pod's own namespace, unless CONFIGMAP_NAMESPACE names another.
CONFIGMAP_NAMESPACE = os.environ.get("CONFIGMAP_NAMESPACE") or read_file("namespace")
CONTEXT = ssl.create_default_context(cafile=os.path.join(SA_DIR, "ca.crt"))


def call(method, path, body=None, timeout=10):
    """Calls the API server, returning the open response. The token is read for each call, since it rotates."""
    headers = {"Authorization": f"Bearer {read_file('token')}", "Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    return urllib.request.urlopen(request, context=CONTEXT, timeout=timeout)  # nosec B310: the API server, over https


def call_json(method, path, body=None):
    with call(method, path, body) as response:
        return json.load(response)


def target(service):
    """The discovery entry an annotated Service gives, or None for a Service without the annotation, or with one the
    proxy couldn't serve."""
    meta = service["metadata"]
    name, namespace = meta["name"], meta["namespace"]
    annotations = meta.get("annotations") or {}
    path = annotations.get(PATH_ANNOTATION)
    if path is None:
        return None
    if not VALID_PATH.fullmatch(path):
        log(f"skipping Service {namespace}/{name}: {PATH_ANNOTATION} '{path}' isn't a path the proxy can serve")
        return None
    ports = service.get("spec", {}).get("ports") or []
    port = annotations.get(PORT_ANNOTATION) or (str(ports[0]["port"]) if ports else "")
    if not port.isdigit():
        log(f"skipping Service {namespace}/{name}: no port to scrape")
        return None
    metrics_path = "/" + annotations.get(METRICS_PATH_ANNOTATION, "/metrics").lstrip("/")
    return {
        "name": f"{namespace}/{name}",
        "path": path,
        "url": f"http://{name}.{namespace}.svc.cluster.local:{port}{metrics_path}",
        # The agent's labels are a JSON string, not an object. Prometheus takes job from them, through the proxy's
        # service discovery.
        "labels": json.dumps({"job": name, "namespace": namespace}),
    }


def render(services):
    """The targets file for these Services: HOCON, which takes the // header and the JSON below it."""
    targets = {}
    for service in sorted(services.values(), key=lambda s: s["metadata"]["name"]):
        entry = target(service)
        if entry is None:
            continue
        if entry["path"] in targets:
            log(f"skipping {entry['name']}: {targets[entry['path']]['name']} already has path {entry['path']}")
            continue
        targets[entry["path"]] = entry
    header = (
        f"// Written by targets-sync (demos/kubernetes/sync/targets_sync.py) from the Services in the {WATCH_NAMESPACE}"
        f" namespace annotated\n// {PATH_ANNOTATION}. Edit the Services, not this: it is rewritten.\n"
    )
    return header + json.dumps({"paths": sorted(targets.values(), key=lambda t: t["path"])}, indent=2) + "\n"


def put_if_changed(path, content):
    """Reads the ConfigMap and, if it doesn't hold content already, writes it: True if it wrote, False if content was
    there, None on a conflict, someone else's write since the read."""
    configmap = call_json("GET", path)
    data = configmap.get("data") or {}
    if data.get(TARGETS_KEY) == content:
        return False
    configmap["data"] = {**data, TARGETS_KEY: content}
    try:
        call_json("PUT", path, configmap)
    except urllib.error.HTTPError as e:
        if e.code == 409:
            return None
        raise
    return True


def write(content):
    """Puts content in the ConfigMap unless it's there already, retrying a conflict on a fresh read: True if it
    wrote."""
    path = f"/api/v1/namespaces/{CONFIGMAP_NAMESPACE}/configmaps/{CONFIGMAP_NAME}"
    for _ in range(5):
        written = put_if_changed(path, content)
        if written is not None:
            return written
    raise RuntimeError(f"gave up on {CONFIGMAP_NAME} after repeated conflicts")


def sync(services):
    content = render(services)
    if write(content):
        paths = [entry["path"] for entry in json.loads(content[content.index("{"):])["paths"]]
        log(f"wrote {CONFIGMAP_NAMESPACE}/{CONFIGMAP_NAME}: {', '.join(paths) or 'no paths'}")


def run_once():
    """Lists the Services and syncs, then watches them, syncing on each change, until the watch ends."""
    services_path = f"/api/v1/namespaces/{WATCH_NAMESPACE}/services"
    listing = call_json("GET", services_path)
    services = {s["metadata"]["name"]: s for s in listing.get("items", [])}
    sync(services)
    watch = f"{services_path}?watch=1&resourceVersion={listing['metadata']['resourceVersion']}" \
            f"&timeoutSeconds={WATCH_SECS}"
    with call("GET", watch, timeout=WATCH_SECS + 15) as stream:
        for line in stream:
            event = json.loads(line)
            kind, service = event.get("type"), event.get("object", {})
            if kind == "ERROR":
                # Most often 410 Gone, a resourceVersion too old to watch from: list again.
                return
            name = service["metadata"]["name"]
            if kind == "DELETED":
                services.pop(name, None)
            elif kind in ("ADDED", "MODIFIED"):
                services[name] = service
            sync(services)


def main():
    log(f"watching Services in {WATCH_NAMESPACE} for {PATH_ANNOTATION}, writing "
        f"{CONFIGMAP_NAMESPACE}/{CONFIGMAP_NAME}")
    while True:
        try:
            run_once()
        except (OSError, ValueError, KeyError, RuntimeError, urllib.error.URLError) as e:
            log(f"{type(e).__name__}: {e}; retrying in 2s")
            time.sleep(2)


if __name__ == "__main__":
    sys.exit(main())
