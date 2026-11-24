package coffeeshop

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe

// The demo's coffee shop: a Ktor app with a Prometheus agent embedded in it (PrometheusAgent.kt), whose customers
// order over HTTP on their own (Customers.kt). See demos/ktor/README.md in the repository.
fun main() {
  val port = env("PORT").toIntOrNull() ?: 8080
  // start(wait = true) stops the server on SIGTERM, and the agent with it.
  embeddedServer(CIO, port = port) { coffeeShop(port) }.start(wait = true)
}

// The website's Ktor Demo page shows the version and this function, from here to the end marker.
// --8<-- [start:app-module]

// The app's version, which its page, /status, and its agent's name show, so a deploy can be told apart from the last.
private val version = env("APP_VERSION").ifEmpty { "dev" }

fun Application.coffeeShop(port: Int) {
  // Ktor's HTTP metrics (ktor_http_server_requests_seconds) and the JVM's, then the shop's, in one registry. The plugin
  // goes first: it configures the registry, which Micrometer wants done before any meter is registered.
  val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  install(MicrometerMetrics) { this.registry = registry }

  // The agent reads the registry in-process for each scrape, so the app serves no /metrics (PrometheusAgent.kt)
  installPrometheusAgent(registry, version)

  val shop = CoffeeShop(ShopMetrics(registry))

  routing {
    shopRoutes(shop)
    adminRoutes(shop)
  }

  shop.open(this)
  Customers(shop, port).start(this)
}
// --8<-- [end:app-module]

// The shop's page, its health check, its state as text, and its counter.
private fun Route.shopRoutes(shop: CoffeeShop) {
  // ?refresh=false leaves out the page's two-second refresh, for a screenshot (bin/screenshots.sh).
  get("/") {
    val refresh = call.parameters["refresh"] != "false"
    call.respondHtml { statusPage(shop, refresh) }
  }

  get("/healthz") { call.respondText("ok") }

  // The shop's state as text, for `demo.sh status`.
  get("/status") { call.respondText(statusText(shop)) }

  // An order at the counter: POST /orders?drink=latte
  post("/orders") { call.placeOrder(shop) }
}

private fun statusText(shop: CoffeeShop): String {
  val served = Drink.entries.joinToString { "${it.label} ${shop.served.getValue(it).get()}" }
  return """
    |Version:          $version
    |Queue:            ${shop.queueDepth.get()} of ${shop.queueCapacity}
    |Baristas:         ${shop.baristas}
    |Rush hour:        ${shop.rushLabel}
    |Espresso machine: ${shop.machineLabel}
    |Served:           $served
    |
    """.trimMargin()
}

private suspend fun RoutingCall.placeOrder(shop: CoffeeShop) {
  val drink = Drink.named(parameters["drink"].orEmpty())
  if (drink == null) {
    val menu = Drink.entries.joinToString { it.label }
    respondText("No such drink; the menu is $menu\n", status = HttpStatusCode.BadRequest)
    return
  }
  when (shop.placeOrder(drink)) {
    OrderResult.QUEUED -> {
      respondText("A ${drink.label} is on its way\n", status = HttpStatusCode.Accepted)
    }

    OrderResult.REJECTED -> {
      respondText("The queue is full; try again soon\n", status = HttpStatusCode.TooManyRequests)
    }

    OrderResult.FAILED -> {
      respondText("The espresso machine is broken\n", status = HttpStatusCode.ServiceUnavailable)
    }
  }
}

// The knobs the demo's tour turns. Unauthenticated: the demo publishes the app on 127.0.0.1 only.
private fun Route.adminRoutes(shop: CoffeeShop) {
  post("/admin/rush") {
    val on = call.booleanParameter("on") ?: return@post
    shop.setRush(on)
    call.respondText(if (on) "Rush hour: customers order three times as often\n" else "The rush is over\n")
  }

  post("/admin/machine") {
    val broken = call.booleanParameter("broken") ?: return@post
    shop.setMachineBroken(broken)
    call.respondText(if (broken) "The espresso machine is broken\n" else "The espresso machine is fixed\n")
  }

  post("/admin/baristas") {
    val count = call.parameters["count"]?.toIntOrNull()
    if (count == null || count !in 1..12) {
      call.respondText("count must be from 1 to 12\n", status = HttpStatusCode.BadRequest)
      return@post
    }
    shop.setBaristas(count)
    call.respondText("$count baristas on shift\n")
  }
}

// The value of the query parameter [name], true or false; anything else is answered with a 400, and null returned.
private suspend fun RoutingCall.booleanParameter(name: String): Boolean? =
  parameters[name]?.toBooleanStrictOrNull().also { value ->
    if (value == null) respondText("${request.uri}: $name must be true or false\n", status = HttpStatusCode.BadRequest)
  }

private fun HTML.statusPage(
  shop: CoffeeShop,
  refresh: Boolean,
) {
  head {
    title("Coffee shop")
    if (refresh) {
      meta {
        attributes["http-equiv"] = "refresh"
        attributes["content"] = "2"
      }
    }
    style {
      unsafe {
        +"""
        body { font-family: system-ui, sans-serif; margin: 2rem; background: #faf7f2; color: #2b2118; }
        h1 { margin-bottom: 0.2rem; } .sub { color: #7a6a5a; margin-top: 0; }
        .cards { display: flex; gap: 1rem; margin: 1.5rem 0; flex-wrap: wrap; }
        .card { background: #fff; border-radius: 10px; padding: 1rem 1.4rem; box-shadow: 0 1px 3px #0002; min-width: 9rem; }
        .label { color: #7a6a5a; font-size: 0.85rem; } .value { font-size: 1.8rem; font-weight: 600; }
        .bad { color: #b3261e; } table { border-collapse: collapse; background: #fff; border-radius: 10px; }
        th, td { padding: 0.5rem 1.2rem; text-align: left; border-bottom: 1px solid #eee5da; }
        @media (prefers-color-scheme: dark) {
          body { background: #1c1712; color: #f1e9df; } .sub, .label { color: #b3a291; }
          .card, table { background: #2a221b; box-shadow: none; } th, td { border-bottom-color: #3a3027; }
          .bad { color: #f2867d; }
        }
        """.trimIndent()
      }
    }
  }
  body {
    h1 { +"Coffee shop" }
    p("sub") {
      +"Version $version, with a Prometheus agent embedded."
      if (refresh) +" This page refreshes every two seconds."
    }
    div("cards") {
      card("Queue", "${shop.queueDepth.get()}")
      card("Baristas", "${shop.baristas}")
      card("Rush hour", shop.rushLabel)
      card("Espresso machine", shop.machineLabel, bad = shop.isMachineBroken)
    }
    table {
      tr {
        th { +"Drink" }
        th { +"Served" }
      }
      Drink.entries.forEach { drink ->
        tr {
          td { +drink.label }
          td { +"${shop.served.getValue(drink).get()}" }
        }
      }
    }
  }
}

private fun kotlinx.html.FlowContent.card(
  label: String,
  value: String,
  bad: Boolean = false,
) = div("card") {
  div("label") { +label }
  span(if (bad) "value bad" else "value") { +value }
}

// An environment variable's value, trimmed, or empty when it isn't set.
internal fun env(name: String) = System.getenv(name).orEmpty().trim()
