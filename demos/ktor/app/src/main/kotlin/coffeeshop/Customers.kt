package coffeeshop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.ln
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The shop's customers, who order over HTTP, POST /orders on the app's [port], as real clients would, so Ktor's own
 * metrics count their requests and the counter's 202s, 429s, and 503s. They arrive at random, on average
 * [baseOrdersPerSec] a second, or three times that while [shop] is in a rush.
 */
class Customers(
  private val shop: CoffeeShop,
  port: Int,
  private val baseOrdersPerSec: Double = 1.5,
) {
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
  private val orders = "http://127.0.0.1:$port/orders?drink="

  fun start(scope: CoroutineScope) {
    scope.launch {
      while (isActive) {
        val rate = baseOrdersPerSec * if (shop.isRush) 3 else 1
        delay((-ln(1 - Random.nextDouble()) / rate).seconds)
        val request =
          HttpRequest
            .newBuilder(URI(orders + Drink.random().label))
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(5))
            .build()
        // Fire and forget: a customer turned away, or one who arrives before the server is listening, just leaves.
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
      }
    }
  }
}
