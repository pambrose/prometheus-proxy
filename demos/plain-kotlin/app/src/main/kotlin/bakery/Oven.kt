package bakery

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.exp
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What the bakery bakes, and how long each takes on average, in seconds. An oven bakes one order at a time, about two
 * a second, so one keeps up with the counter's steady trickle of one and a half a second, and a rush needs more.
 */
enum class MenuItem(
  val meanSecs: Double,
) {
  COOKIE(0.25),
  MUFFIN(0.35),
  CROISSANT(0.45),
  BAGUETTE(0.6),
  SOURDOUGH(0.85),
  ;

  // The name an order gives, as the counter writes it
  val label = name.lowercase()

  companion object {
    fun named(label: String) = entries.firstOrNull { it.label == label }
  }
}

/**
 * An oven: it takes orders from [queue] and bakes them one at a time, until [stop], recording what it baked in
 * [registry], the Prometheus Java client's. It has no HTTP server: the agent embedded beside it reads the registry
 * in-process and sends the metrics to the proxy.
 */
class Oven(
  private val queue: OrderQueue,
  registry: PrometheusRegistry,
) {
  @Volatile
  private var running = true

  private val baked =
    Counter
      .builder()
      .name("bakery_orders_baked")
      .help("Orders this oven has baked, by item")
      .labelNames("item")
      .register(registry)

  private val bakeTime =
    Histogram
      .builder()
      .name("bakery_bake_seconds")
      .help("How long this oven took to bake each order, by item")
      .labelNames("item")
      .classicOnly()
      .classicUpperBounds(0.1, 0.2, 0.3, 0.5, 0.75, 1.0, 1.5, 2.5)
      .register(registry)

  private val waiting =
    Gauge
      .builder()
      .name("bakery_queue_depth")
      .help("Orders waiting in the bakery's shared queue, as this oven last saw it")
      .register(registry)

  // How many orders this oven has baked, and how many since it last said so
  private var total = 0
  private var sinceSummary = 0
  private var lastSummary = TimeSource.Monotonic.markNow()

  // Whether the queue was out of reach when last tried, so that's logged once, not every second
  private var queueDown = false

  init {
    // Each item's series start at zero, so rate() has them from the first scrape
    MenuItem.entries.forEach { item ->
      baked.labelValues(item.label)
      bakeTime.labelValues(item.label)
    }
  }

  /**
   * Takes orders and bakes them, one at a time, until [stop]: an order taken is always baked, so none is lost. Returns
   * once the order in the oven is done.
   */
  fun runUntilStopped() {
    logger.info { "The oven is on, taking orders from ${queue.address}" }
    while (running) {
      takeOrder()?.also(::bake)
      summarize()
    }
    queue.close()
    logger.info { "The oven is off, after baking $total orders" }
  }

  /** Has [runUntilStopped] return once the order in the oven is done, taking no more. */
  fun stop() {
    logger.info { "Stopping: the oven finishes the order it's baking, and takes no more" }
    running = false
  }

  // The next order, or null after a second without one, or when the queue can't be reached, which waits a second. An
  // order taken is returned even if reading the queue's length then fails, so it's baked, not lost.
  @Suppress("TooGenericExceptionCaught")
  private fun takeOrder(): String? =
    try {
      queue.take().also {
        if (queueDown) logger.info { "The queue is back" }
        queueDown = false
        runCatching { waiting.set(queue.size().toDouble()) }
      }
    } catch (e: Exception) {
      if (!queueDown) logger.warn { "Can't reach the queue at ${queue.address}, retrying every second: ${e.message}" }
      queueDown = true
      Thread.sleep(RETRY_PAUSE.inWholeMilliseconds)
      null
    }

  private fun bake(order: String) {
    val item = MenuItem.named(order)
    if (item == null) {
      logger.warn { "No $order on the menu; the order is thrown away" }
      return
    }
    val secs = bakeSecs(item)
    Thread.sleep(secs.seconds.inWholeMilliseconds)
    baked.labelValues(item.label).inc()
    bakeTime.labelValues(item.label).observe(secs)
    total += 1
    sinceSummary += 1
    if (total == 1) logger.info { "Baked the first order, a ${item.label}, in ${"%.2f".format(secs)}s" }
  }

  // An item's bake time: its mean, give or take, from a log-normal distribution with that mean
  private fun bakeSecs(item: MenuItem) =
    item.meanSecs * exp(JITTER * ThreadLocalRandom.current().nextGaussian() - JITTER * JITTER / 2)

  // Every 30 seconds, what the oven baked since the last time, so its log shows it at work.
  private fun summarize() {
    if (lastSummary.elapsedNow() < SUMMARY_INTERVAL) return
    logger.info { "Baked $sinceSummary orders in the last $SUMMARY_INTERVAL; ${waiting.get().toLong()} waiting" }
    sinceSummary = 0
    lastSummary = TimeSource.Monotonic.markNow()
  }

  private companion object {
    private val logger = logger {}
    private val SUMMARY_INTERVAL = 30.seconds
    private val RETRY_PAUSE = 1.seconds
    private const val JITTER = 0.3
  }
}
