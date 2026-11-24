package coffeeshop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** What the shop serves: how long each drink takes to brew, whether it needs the espresso machine, and how often. */
enum class Drink(
  val label: String,
  val brewSeconds: Double,
  val needsEspresso: Boolean,
  val popularity: Int,
) {
  ESPRESSO("espresso", 0.8, true, 2),
  LATTE("latte", 1.6, true, 4),
  CAPPUCCINO("cappuccino", 1.4, true, 3),
  MOCHA("mocha", 2.0, true, 1),
  TEA("tea", 0.9, false, 2),
  COLD_BREW("cold-brew", 0.5, false, 1),
  ;

  companion object {
    private val byPopularity = entries.flatMap { drink -> List(drink.popularity) { drink } }

    fun named(label: String): Drink? = entries.firstOrNull { it.label == label }

    fun random(): Drink = byPopularity.random()
  }
}

/** What happened to an order, as `coffee_orders_total`'s `outcome` label gives it. */
enum class Outcome {
  // Brewed and handed over
  SERVED,

  // Turned away at the counter: the queue was full
  REJECTED,

  // Couldn't be made: an espresso drink while the espresso machine is broken
  FAILED,
  ;

  val label: String get() = name.lowercase()
}

/** What the counter did with an order: took it, for a barista to brew, or turned it away. */
enum class OrderResult {
  QUEUED,
  REJECTED,
  FAILED,
}

/**
 * The coffee shop: a counter that takes up to [queueCapacity] orders, and baristas who brew them. Its customers
 * (Customers.kt) order over HTTP, and order more during a rush. Everything it does is reported to [metrics].
 */
class CoffeeShop(
  private val metrics: ShopMetrics,
  val queueCapacity: Int = 40,
  baristas: Int = 3,
) {
  private class Order(
    val drink: Drink,
  )

  private val queue = Channel<Order>(queueCapacity)
  private val baristaJobs = mutableListOf<Job>()
  private val rush = AtomicBoolean(false)
  private val machineBroken = AtomicBoolean(false)
  private lateinit var scope: CoroutineScope

  val queueDepth = AtomicInteger(0)
  val served = Drink.entries.associateWith { AtomicInteger(0) }
  var baristas = baristas
    private set
  val isRush get() = rush.get()
  val isMachineBroken get() = machineBroken.get()

  // The rush and the machine as the status page and /status put them
  val rushLabel get() = if (isRush) "on" else "off"
  val machineLabel get() = if (isMachineBroken) "broken" else "working"

  init {
    metrics.bind(this)
  }

  /** Opens the shop in [scope]: the baristas start brewing. */
  fun open(scope: CoroutineScope) {
    this.scope = scope
    setBaristas(baristas)
  }

  /** Takes an order for [drink] at the counter, or turns it away. A taken order counts as served once it's brewed. */
  fun placeOrder(drink: Drink): OrderResult =
    when {
      drink.needsEspresso && machineBroken.get() -> {
        metrics.ordered(drink, Outcome.FAILED)
        OrderResult.FAILED
      }

      queue.trySend(Order(drink)).isSuccess -> {
        queueDepth.incrementAndGet()
        OrderResult.QUEUED
      }

      else -> {
        metrics.ordered(drink, Outcome.REJECTED)
        OrderResult.REJECTED
      }
    }

  fun setRush(on: Boolean) = rush.set(on)

  fun setMachineBroken(broken: Boolean) = machineBroken.set(broken)

  /** Hires or sends home baristas until there are [count]. One sent home finishes the drink in hand first. */
  fun setBaristas(count: Int) =
    synchronized(baristaJobs) {
      while (baristaJobs.size < count) baristaJobs += scope.launch { barista() }
      while (baristaJobs.size > count) baristaJobs.removeLast().cancel()
      baristas = count
    }

  private suspend fun barista() {
    for (order in queue) {
      queueDepth.decrementAndGet()
      // A barista sent home mid-drink finishes it: the cancellation lands at the next order instead.
      withContext(NonCancellable) {
        // Most drinks take about their brew time; one in twenty takes two and a half times as long.
        val slow = if (Random.nextInt(20) == 0) 2.5 else 1.0
        val seconds = order.drink.brewSeconds * Random.nextDouble(0.7, 1.3) * slow
        delay((seconds * 1000).toLong().milliseconds)
        served.getValue(order.drink).incrementAndGet()
        metrics.brewed(order.drink, seconds)
        metrics.ordered(order.drink, Outcome.SERVED)
      }
    }
  }
}
