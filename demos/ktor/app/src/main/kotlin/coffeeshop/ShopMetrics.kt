package coffeeshop

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * The shop's own metrics, in [registry], which the agent embedded in the app reads in-process, beside Ktor's HTTP and
 * JVM metrics:
 *
 * - `coffee_orders_total{drink,outcome}`: orders served, rejected at a full counter, or failed with the machine broken
 * - `coffee_brew_seconds`: how long each drink took to brew, as a histogram
 * - `coffee_queue_depth`, `coffee_baristas`, `coffee_rush_hour`, `coffee_machine_up`: the shop's state right now
 */
class ShopMetrics(
  private val registry: MeterRegistry,
) {
  // Every drink's counter for each outcome, and its brew timer, registered once, so each series exists from the start
  private val orders =
    Drink.entries.associateWith { drink ->
      Outcome.entries.associateWith { outcome ->
        Counter
          .builder("coffee.orders")
          .description("Orders, by drink and what happened to them")
          .tag("drink", drink.label)
          .tag("outcome", outcome.label)
          .register(registry)
      }
    }

  private val brews =
    Drink.entries.associateWith { drink ->
      Timer
        .builder("coffee.brew")
        .description("How long a drink took to brew")
        .tag("drink", drink.label)
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofMillis(100))
        .maximumExpectedValue(Duration.ofSeconds(10))
        .register(registry)
    }

  fun bind(shop: CoffeeShop) {
    Gauge.builder("coffee.queue.depth") { shop.queueDepth.get() }
      .description("Orders taken and waiting for a barista")
      .register(registry)
    Gauge.builder("coffee.baristas") { shop.baristas }
      .description("Baristas on shift")
      .register(registry)
    Gauge.builder("coffee.rush.hour") { if (shop.isRush) 1 else 0 }
      .description("1 during a rush, when customers order three times as often")
      .register(registry)
    Gauge.builder("coffee.machine.up") { if (shop.isMachineBroken) 0 else 1 }
      .description("1 while the espresso machine works")
      .register(registry)
  }

  fun ordered(
    drink: Drink,
    outcome: Outcome,
  ) = orders.getValue(drink).getValue(outcome).increment()

  fun brewed(
    drink: Drink,
    seconds: Double,
  ) = brews.getValue(drink).record(Duration.ofMillis((seconds * 1000).toLong()))
}
