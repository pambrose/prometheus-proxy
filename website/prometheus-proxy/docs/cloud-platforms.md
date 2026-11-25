---
icon: lucide/cloud
---

# Cloud Platforms

An app on a platform such as ECS, Cloud Run, or Azure Container Apps can't be scraped by a Prometheus outside the
platform without opening its metrics to the internet. An [embedded agent](embedded-agent.md) reverses the direction: it
connects to a proxy beside Prometheus, and answers each scrape by reading the app's own `/metrics`. This page sets
that up for a JVM app, with the proxy on AWS, GCP, Azure, DigitalOcean, or Railway. The
[Ktor Demo](ktor-demo.md) runs the same setup on one machine.

```text
App platform (ECS, Cloud Run, ...)               Proxy host (AWS, GCP, Azure, DigitalOcean, Railway)
  app: /metrics on 127.0.0.1                       prometheus-proxy  :50051  agents, over TLS with a token
   └ embedded agent ── gRPC, TLS + token ─────►                      :8080   scrapes, private
                                                   Prometheus ── scrapes :8080/my_app_metrics
```

## How It Fits Together

- The agent runs inside the app and connects out to the proxy's agent port, so the app opens no port, and nothing
  outside reaches into the platform.
- It answers each scrape by reading the app's own `/metrics` on `127.0.0.1`. If that endpoint asks for Basic Auth, the
  agent sends the credentials with each scrape and leaves them out of its logs.
- Prometheus scrapes the proxy, on a private network, at the path the agent registered. Keep the proxy's scrape port
  (8080) off the internet: anyone who reaches it scrapes through the agent. Only the agent port (50051) is public,
  behind TLS and a token.
- Leave the proxy's [dashboard](web-dashboard.md) off, as it is by default, or set its [login](web-dashboard.md#login)
  and keep it on the private network: without a login, it has no authentication.

## In the App

### Starting the Agent

Build the agent's settings in code from the app's own, with
[`embeddedAgent { }` or `EmbeddedAgentConfig.builder()`](embedded-agent.md#configuring-in-code), so no file holds the
token or the password, and start it:

=== "Kotlin"

    ```kotlin
    --8<-- "EmbeddedAgentConfigKotlinExample.kt:embedded-kotlin-config"
    ```

=== "Java"

    ```java
    --8<-- "EmbeddedAgentConfigJavaExample.java:embedded-java-config"
    ```

- The app's settings are `PROMETHEUS_PROXY` (the proxy's agent port, as `host:port`), `PROMETHEUS_AGENT_TOKEN`, and
  `PROMETHEUS_PROXY_CERT` (the proxy's certificate, as PEM or its base64), which
  [`fromEnvironment()`](embedded-agent.md#settings-from-the-environment) reads, with the certificate checked against
  `prometheus-proxy`, the name [Securing the Link](#securing-the-link) issues it to (`PROMETHEUS_PROXY_AUTHORITY` for
  another), and `METRICS_USER` and `METRICS_PASSWORD` for its own `/metrics`. Without `PROMETHEUS_PROXY`, as on a
  developer's machine, `startIfConfigured()` leaves the agent off.
- The agent reads its own environment variables too, such as `AGENT_TOKEN` and `PROXY_HOSTNAME`, and they take
  precedence over the config, so keep those names out of the app's environment unless you mean the agent to use them.
- The app's version in `agent.name` tells the old and new revisions apart in the proxy's logs during a deploy.
- The agent's admin and metrics servers stay off, as they are unless asked for, since the app has no port to spare for
  them. [`agentMetricsAt`](embedded-agent.md#the-agents-own-metrics) serves the agent's own metrics through the proxy
  instead, with no port.
- `scrapeSelf` percent-encodes the credentials into the scrape URL, so a password with any character works. An app with
  a metrics registry needn't serve it at all: `source(path, registry)` has the agent
  [read it in-process](embedded-agent.md#metrics-served-in-process).
- A failure to start is logged, and the app carries on without the agent: its metrics aren't worth an outage. To hear
  of one somewhere someone will see it, such as a chat channel, add a handler with the builder's
  [`onFailure`](embedded-agent.md#starting-where-theres-a-proxy), which is given why, as the log line has it.

### Starting and Stopping It with the App

Start the agent once the app serves `/metrics`, and stop it when the app stops. In a Ktor module:

```kotlin
--8<-- "src/test/kotlin/hosts/KtorHostExample.kt:ktor-host"
```

In Spring Boot, the [Spring Boot Starter](spring-boot-starter.md) starts and stops it with the app, as the
[Spring Boot Demo](spring-boot-demo.md) shows; [In Spring Boot](embedded-agent.md#in-spring-boot) covers doing it with
a bean of your own.

The platform stops the app with `SIGTERM`, then kills it once its shutdown window is up: 10 seconds on Cloud Run. Set
the builder's [`stopTimeout`](embedded-agent.md#embeddedagentinfo) inside that window, as the example above does, so the
rest of the app's shutdown isn't left waiting on the agent: once it's up, `shutdown()` returns, with a warning, and the agent goes on stopping on
its own. The [Ktor Plugin](ktor-plugin.md#a-failed-start-and-the-stop) and the Spring Boot Starter take it as their
`stop-timeout`, 30 seconds by default, longer than Cloud Run's window.

Keep the agent out of the health check the platform restarts the app on. The app serves without its agent, which
reconnects by itself once the proxy is back, so a failed check would only restart a working app. To see whether the
agent is connected, report [`EmbeddedAgentInfo`](embedded-agent.md#embeddedagentinfo)'s `isConnected` and
`proxyEndpoint` somewhere that doesn't fail the check, such as a detail of the app's status page; the Spring Boot Demo
reports them in Actuator's health as UNKNOWN, not DOWN.

Give the app's logging configuration the level the standalone agent sets for gRPC's internal logging; see
[Logging](embedded-agent.md#logging).

### Packaging the App

The agent is a library inside the app, so the app's image is an ordinary JVM image. It adds nothing for the agent: no
port, since the agent connects out to the proxy, and no file, since the token and the certificate come in as
environment variables. A two-stage Dockerfile, for a Gradle app named `my-app` that uses the `application` plugin:

```dockerfile
--8<-- "EmbeddedAgentExamples.txt:embedded-app-dockerfile"
```

- It builds with the app's own Gradle wrapper on a JDK, and runs the result on a JRE as a user that isn't root, named by
  number, so a platform that checks for one, such as Kubernetes with `runAsNonRoot`, can. Add a `.dockerignore` that
  leaves out `build/`, `.gradle/`, `.kotlin/`, `.git/`, and `.idea/`, so `COPY . .` sends only the sources, and a commit
  or an IDE doesn't make the next build start over.
- The start script `installDist` writes reads `JAVA_OPTS`. Besides sizing the heap from the container's memory, it
  carries two flags for Netty, which the agent's gRPC runs on, on Java 24 and later. The stock images run with the
  second; their jars ask for native access in their manifest instead of the first, which only `java -jar` reads:
    - `--enable-native-access=ALL-UNNAMED` lets Netty load its native TLS library without the warning Java prints,
      which says a later release will block the call.
    - `--sun-misc-unsafe-memory-access=allow` lets Netty use `sun.misc.Unsafe` for its buffers, which it otherwise
      does without. Java 22 and earlier don't know it and won't start with it, so leave it out there.
- An app built as a fat JAR starts with `java -jar`, which doesn't read `JAVA_OPTS`: give the options in
  `JDK_JAVA_OPTIONS`, which the `java` launcher reads, or on its command line.
- Any JDK 17 or later runs the agent; the stock images run 25.

The [Ktor Demo](ktor-demo.md)'s coffee shop is built the same way.

### What the App Platform Must Give It

- **CPU between requests, and an instance that stays up.** The agent holds its connection to the proxy open between
  scrapes, so a platform that throttles CPU outside requests, or scales the app to zero, stalls it.
    - On AWS, run the app as an ECS service on Fargate, whose tasks keep their CPU whether or not they handle
      requests. App Runner gives an instance CPU only while it handles requests, and bills an idle one for its memory
      alone, so the agent stalls there.
    - On GCP, Cloud Run needs instance-based billing with a minimum instance:

        ```bash
        gcloud run services update my-app --no-cpu-throttling --min-instances=1
        ```

    - On Azure, Container Apps scales an app to zero by default, so keep a replica running:

        ```bash
        az containerapp update --name my-app --resource-group my-group --min-replicas 1
        ```

        On App Service, turn on Always On, which keeps the app loaded when it has no requests.

- **One instance per path.** Each agent registers its path as its own, and the last one to register serves it, so a
  second instance's agent takes the path from the first. Run one instance (a desired count of 1 for an ECS service,
  `--max-instances=1` on Cloud Run, `--max-replicas 1` on Container Apps), or make the agents
  [consolidated](advanced.md#consolidated-mode) and give each instance's series a label of its own, such as a common
  tag with the instance's ID: the proxy merges the instances' answers into one scrape, and without the label it would
  repeat each series once per instance.
- **Deploys need nothing more.** While the old and new revisions overlap, the new revision's agent takes the path
  over, and the old one stops with its app.

## Securing the Link

The agent port faces the internet, so the agents connect over TLS and present a token.

Make a certificate for the proxy, issued to a fixed name rather than to whatever host name the platform gives it, and
a token:

```bash
openssl req -x509 -newkey rsa:3072 -nodes -days 3650 -subj "/CN=prometheus-proxy" \
  -addext "subjectAltName=DNS:prometheus-proxy" -keyout server.key -out server.crt
openssl rand -hex 32 > agent-token
```

- The proxy takes `server.crt` and `server.key` as `CERT_CHAIN_PEM` and `PRIVATE_KEY_PEM`, and the token as
  `AGENT_TOKEN`.
- The agent trusts `server.crt` (`agent.tls.trustCertCollectionPem`), checks it against the fixed name
  (`agent.tls.overrideAuthority = "prometheus-proxy"`), and presents the token (`agent.agentToken`): `fromEnvironment()`
  sets all three from `PROMETHEUS_PROXY_CERT` and `PROMETHEUS_AGENT_TOKEN`, with `prometheus-proxy` as the name.
- A variable field that takes one line takes the certificate and key as base64: `base64 < server.crt | tr -d '\n'`.
  See [Certificates as Environment Variables](security/tls.md#certificates-as-environment-variables) for the forms a
  PEM setting takes.
- Keep `server.key` and the token out of version control. To limit which paths each app may register, give each its
  own identity instead of one shared token; see
  [Per-agent identities](security/index.md#per-agent-identities-and-path-authorization).

## Where to Run the Proxy

The proxy and Prometheus run together, on a host whose agent port the apps can reach:

| Platform     | Agents reach the proxy through                        | Prometheus runs                            |
|:-------------|:------------------------------------------------------|:-------------------------------------------|
| AWS          | An EC2 instance's address, with a security group rule | On the instance, beside the proxy          |
| GCP          | A Compute Engine VM's address, with a firewall rule   | On the VM, beside the proxy                |
| Azure        | An Azure VM's address, with a network security rule   | On the VM, beside the proxy                |
| DigitalOcean | A Droplet's address, with a firewall open on 50051    | On the Droplet, beside the proxy           |
| Railway      | A TCP Proxy on the proxy service's port 50051         | As another service, on the private network |

[`examples/cloud-proxy`](https://github.com/pambrose/prometheus-proxy/tree/master/examples/cloud-proxy) holds the
proxy's config and a Prometheus config for each of them, and a Compose file that runs both on a VM:

=== "docker-compose.yml"

    ```yaml
    --8<-- "examples/cloud-proxy/docker-compose.yml"
    ```

=== "proxy.conf"

    ```hocon
    --8<-- "examples/cloud-proxy/proxy.conf"
    ```

=== "prometheus.yml"

    ```yaml
    --8<-- "examples/cloud-proxy/prometheus.yml"
    ```

### AWS

- On an EC2 instance with Docker, copy `examples/cloud-proxy` over, put the certificate, key, and token in its `.env`,
  and run `docker compose up --detach`. Open the agent port in the instance's security group:

    ```bash
    aws ec2 authorize-security-group-ingress --group-id sg-0123456789abcdef0 --protocol tcp --port 50051 \
      --cidr 0.0.0.0/0
    ```

- An app on ECS in the same VPC can reach the instance on its private address instead: admit the app's security group
  (`--source-group` in place of `--cidr`), so the agent port needn't face the internet at all. Keep the token and TLS
  anyway.
- To run the proxy on ECS on Fargate, as a service with one task, put a
  [Network Load Balancer](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/load-balancer-listeners.html)
  in front of it with a TCP listener on 50051. A TCP listener forwards the connection as it is, so the proxy does its
  own TLS, and the agent's heartbeat, which it sends whenever the connection has been idle for a few
  seconds, keeps the connection inside the listener's idle timeout. Run Prometheus as another service that reaches
  the proxy's scrape port inside the VPC.
- On EKS, see [Kubernetes](kubernetes.md).

### GCP

- On a Compute Engine VM with Docker, copy `examples/cloud-proxy` over, put the certificate, key, and token in its
  `.env`, and run `docker compose up --detach`. Open the agent port with a firewall rule that applies to the VM's
  network tag:

    ```bash
    gcloud compute firewall-rules create prometheus-proxy-agents --allow=tcp:50051 --target-tags=prometheus-proxy
    gcloud compute instances add-tags my-proxy-vm --tags=prometheus-proxy --zone=us-central1-a
    ```

- An app on Cloud Run in the same project can reach the VM on its internal address instead, through
  [Direct VPC egress](https://docs.cloud.google.com/run/docs/configuring/vpc-direct-vpc), so the agent port needn't face
  the internet at all. Keep the token and TLS anyway.
- On GKE, see [Kubernetes](kubernetes.md).
- The proxy can run on [Cloud Run](https://docs.cloud.google.com/run/docs/triggering/grpc) too, with constraints. A
  Cloud Run service receives traffic on one port, so that port is the agent port (`--port=50051`), served over
  end-to-end HTTP/2 (`--use-http2`) with TLS from Google's front end, and Prometheus runs as a sidecar that scrapes the
  proxy on `localhost:8080`. The front end is a reverse proxy, so turn the
  [transport filter](advanced.md#nginx-reverse-proxy) off on both sides. Its certificate comes from a public CA, so
  the agent dials the service's own host on port 443 and trusts a CA bundle in place of `server.crt`, with no authority
  override, as with [TLS at nginx](advanced.md#tls-at-nginx). The agent's stream ends at the service's
  request timeout, at most 60 minutes, and the agent reconnects. The proxy needs the same single, always-on instance
  as the app. A VM is simpler.

### Azure

- On an Azure VM with Docker, copy `examples/cloud-proxy` over, put the certificate, key, and token in its `.env`, and
  run `docker compose up --detach`. Open the agent port in the VM's network security group with
  [`az vm open-port`](https://learn.microsoft.com/en-us/cli/azure/vm?view=azure-cli-latest), which admits it from any
  source; for a narrower one, add the rule with `az network nsg rule create` instead:

    ```bash
    az vm open-port --resource-group my-group --name my-proxy-vm --port 50051
    ```

- An app on Container Apps, in an environment with a virtual network that reaches the VM's, can reach the VM on its
  private address instead, so the agent port needn't face the internet at all. Keep the token and TLS anyway.
- On AKS, see [Kubernetes](kubernetes.md).
- The proxy can run on [Container Apps](https://learn.microsoft.com/en-us/azure/container-apps/ingress-overview) too, as
  an app with one replica. External TCP ingress on port 50051 forwards the agent's connection as it is, so the proxy
  does its own TLS; Container Apps offers it only in an environment with a virtual network. Run Prometheus as another
  app in the environment, and give it the proxy's scrape port as an additional, internal TCP port.

### DigitalOcean

- On a Droplet with Docker, copy `examples/cloud-proxy` over, put the certificate, key, and token in its `.env`, and
  run `docker compose up --detach`. Give the Droplet a
  [Cloud Firewall](https://docs.digitalocean.com/products/networking/firewalls/) that admits only port 50051, and SSH
  from your own address.
- App Platform is a poorer fit. DigitalOcean documents
  [HTTP/2 for its services](https://docs.digitalocean.com/products/app-platform/how-to/configure-http2/), which gRPC
  needs, but its [limits page](https://docs.digitalocean.com/products/app-platform/details/limits/) says it doesn't
  support gRPC. Its edge is also a reverse proxy, which needs the
  [transport filter](advanced.md#nginx-reverse-proxy) off on both sides and, as on Cloud Run, a CA bundle for the agent
  to trust. Try it with your own app before relying on it.
- On DigitalOcean Kubernetes, see [Kubernetes](kubernetes.md).

### Railway

- Railway's HTTPS domains don't carry the agent's gRPC through to the container, so give the proxy service a
  [TCP Proxy](https://docs.railway.com/networking/tcp-proxy) on port 50051, and no HTTPS domain. Railway gives it a
  host and port, such as `shuttle.proxy.rlwy.net:15140`, which is the agent's `PROMETHEUS_PROXY`.
- The TCP Proxy forwards the connection as it is and adds no TLS, so the proxy does its own: deploy the
  `pambrose/prometheus-proxy` image, 4.6.0 or later, and set `CERT_CHAIN_PEM`, `PRIVATE_KEY_PEM`, and `AGENT_TOKEN` as
  the service's variables, with `METRICS_ENABLED=true` for the proxy's own metrics. The image needs no config file for
  this.
- Name the service `prometheus-proxy`, so Prometheus reaches it at `prometheus-proxy.railway.internal:8080` on the
  [private network](https://docs.railway.com/networking/private-networking). In an older environment, whose private
  network carries IPv6 only, set the proxy's `proxy.http.host` to `"::"`, in a config it reads through
  `PROXY_CONFIG`, so Prometheus can reach the scrape port.
- Run Prometheus as another service, built from a Dockerfile that copies in a `prometheus.yml` like the example's,
  with its targets on `prometheus-proxy.railway.internal`, and a volume for its data.

## On the Prometheus Side

The example's `prometheus.yml` scrapes the app's metrics at the path its agent registered, `/my_app_metrics`, and
sets `instance` to the app's name rather than the proxy's address. Prometheus sends its scrape timeout along, and the
agent stops the app's scrape at it.

It scrapes the proxy's own metrics too. When the app stops, or its agent can't connect, the proxy no longer serves the
path, and the app's job goes down: `up{job="my-app"} == 0` is the alert to set. `proxy_agent_map_size` counts the
agents connected. See [Grafana & Alerting](grafana.md) for dashboards and rules built on the proxy's metrics.
