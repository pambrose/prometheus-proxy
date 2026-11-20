package roastery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The roastery: every few seconds it roasts a batch of green beans from one origin, and every so often ships roasted
 * beans to the coffee shop. Its meters, in the registry Actuator serves at {@code /actuator/prometheus}, count the
 * batches by origin, time the roasts, and gauge the stock.
 */
@Component
public class Roastery {
  /** Where the green beans come from, and how often each is roasted. */
  public enum Origin {
    ETHIOPIA(3),
    COLOMBIA(4),
    SUMATRA(2),
    GUATEMALA(1);

    final int weight;

    Origin(int weight) {
      this.weight = weight;
    }

    String label() {
      return name().toLowerCase();
    }
  }

  private static final int BATCH_KG = 15;
  // Beans lose about a sixth of their weight in the roaster
  private static final int ROASTED_KG = 12;
  private static final int RESTOCK_KG = 300;

  private final Map<Origin, Counter> batches = new EnumMap<>(Origin.class);
  private final Map<Origin, Integer> roasted = new EnumMap<>(Origin.class);
  private final Timer roastTime;
  private final Counter shipped;
  private final AtomicInteger greenKg = new AtomicInteger(RESTOCK_KG);
  private final AtomicInteger roastedKg = new AtomicInteger();

  public Roastery(MeterRegistry registry) {
    for (Origin origin : Origin.values()) {
      batches.put(
          origin,
          Counter.builder("roastery_batches_roasted")
              .description("Batches of beans roasted, by origin")
              .tag("origin", origin.label())
              .register(registry));
      roasted.put(origin, 0);
    }
    roastTime = Timer.builder("roastery_roast").description("How long a batch takes to roast").register(registry);
    shipped =
        Counter.builder("roastery_shipped_kg")
            .description("Roasted beans shipped to the coffee shop, in kg")
            .register(registry);
    registry.gauge("roastery_green_stock_kg", greenKg);
    registry.gauge("roastery_roasted_stock_kg", roastedKg);
  }

  /** Roasts a batch from an origin picked by its weight, restocking green beans when they run low. */
  @Scheduled(fixedDelay = 2_000)
  public synchronized void roast() {
    if (greenKg.get() < BATCH_KG) {
      greenKg.addAndGet(RESTOCK_KG);
    }
    Origin origin = pick();
    // A roast takes 10 to 14 minutes; the demo records it without waiting for it
    roastTime.record(Duration.ofSeconds(ThreadLocalRandom.current().nextLong(600, 840)));
    batches.get(origin).increment();
    roasted.merge(origin, 1, Integer::sum);
    greenKg.addAndGet(-BATCH_KG);
    roastedKg.addAndGet(ROASTED_KG);
  }

  /** Ships some of the roasted beans to the coffee shop. */
  @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
  public synchronized void ship() {
    int kg = Math.min(roastedKg.get(), ThreadLocalRandom.current().nextInt(10, 31));
    roastedKg.addAndGet(-kg);
    shipped.increment(kg);
  }

  /** The roastery's state, for its status page. */
  public synchronized String status() {
    StringBuilder text = new StringBuilder();
    roasted.forEach((origin, count) -> text.append(origin.label()).append(' ').append(count).append(", "));
    return "Roasted:      " + text.substring(0, text.length() - 2)
        + "\nGreen stock:  " + greenKg.get() + " kg"
        + "\nRoasted:      " + roastedKg.get() + " kg"
        + "\nShipped:      " + (long) shipped.count() + " kg\n";
  }

  private static Origin pick() {
    int total = 0;
    for (Origin origin : Origin.values()) {
      total += origin.weight;
    }
    int roll = ThreadLocalRandom.current().nextInt(total);
    for (Origin origin : Origin.values()) {
      roll -= origin.weight;
      if (roll < 0) {
        return origin;
      }
    }
    return Origin.COLOMBIA;
  }
}
