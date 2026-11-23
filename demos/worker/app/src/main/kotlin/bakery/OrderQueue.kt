package bakery

import redis.clients.jedis.RedisClient
import java.net.URI

/**
 * The bakery's orders: a Redis list that the counter pushes each order onto, as an item's name, and that every oven
 * takes from, so a backlog is shared, and an oven added drains it.
 */
class OrderQueue(
  url: String,
) : AutoCloseable {
  // Without REDIS_URL, as on a developer's machine, a Redis on this one
  val address = url.ifEmpty { "redis://localhost:6379" }
  private val redis = RedisClient.create(URI(address))

  /** The next order, waiting up to a second for one, or null when none came. */
  fun take(): String? = redis.blpop(TAKE_TIMEOUT_SECS, KEY)?.value

  /** How many orders are waiting. */
  fun size(): Long = redis.llen(KEY)

  override fun close() = redis.close()

  private companion object {
    // The list's key, which the counter pushes onto too (../../../../../counter/counter.sh)
    const val KEY = "orders"
    const val TAKE_TIMEOUT_SECS = 1.0
  }
}
