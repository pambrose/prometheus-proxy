package roastery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The roastery: every few seconds it roasts a batch of green beans from one origin, and every so often ships roasted
 * beans to the coffee shop. Its meters, in the registry the agent reads in-process, count the batches by origin, time
 * the roasts, and gauge the stock.
 */
@Component
public class Roastery {
  /** Where the green beans come from. */
  public enum Origin {
    ETHIOPIA,
    COLOMBIA,
    SUMATRA,
    GUATEMALA;

    String label() {
      return name().toLowerCase();
    }
  }

  // The origins in the proportions they're roasted, so the graph's four lines stay apart
  private static final Origin[] ROTATION = {
    Origin.COLOMBIA, Origin.COLOMBIA, Origin.COLOMBIA, Origin.COLOMBIA,
    Origin.ETHIOPIA, Origin.ETHIOPIA, Origin.ETHIOPIA,
    Origin.SUMATRA, Origin.SUMATRA,
    Origin.GUATEMALA,
  };
  private static final int BATCH_KG = 15;
  // Beans lose about a sixth of their weight in the roaster
  private static final int ROASTED_KG = 12;
  private static final int RESTOCK_KG = 300;

  private final Map<Origin, Counter> batches = new EnumMap<>(Origin.class);
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
    }
    roastTime = Timer.builder("roastery_roast").description("How long a batch takes to roast").register(registry);
    shipped =
        Counter.builder("roastery_shipped_kg")
            .description("Roasted beans shipped to the coffee shop, in kg")
            .register(registry);
    registry.gauge("roastery_green_stock_kg", greenKg);
    registry.gauge("roastery_roasted_stock_kg", roastedKg);
  }

  /** Roasts a batch from the next origin, restocking green beans when they run low. */
  @Scheduled(fixedDelay = 2_000)
  public void roast() {
    if (greenKg.get() < BATCH_KG) {
      greenKg.addAndGet(RESTOCK_KG);
    }
    Origin origin = ROTATION[ThreadLocalRandom.current().nextInt(ROTATION.length)];
    // A roast takes 10 to 14 minutes; the demo records it without waiting for it
    roastTime.record(Duration.ofSeconds(ThreadLocalRandom.current().nextLong(600, 840)));
    batches.get(origin).increment();
    greenKg.addAndGet(-BATCH_KG);
    roastedKg.addAndGet(ROASTED_KG);
  }

  /** Ships some of the roasted beans to the coffee shop. */
  @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
  public void ship() {
    int kg = Math.min(roastedKg.get(), ThreadLocalRandom.current().nextInt(10, 31));
    roastedKg.addAndGet(-kg);
    shipped.increment(kg);
  }

  /** The roastery's state, for its status page. */
  public String status() {
    String roasted =
        batches.entrySet().stream()
            .map(entry -> entry.getKey().label() + " " + (long) entry.getValue().count())
            .collect(Collectors.joining(", "));
    return "Roasted:      " + roasted
        + "\nGreen stock:  " + greenKg.get() + " kg"
        + "\nRoasted:      " + roastedKg.get() + " kg"
        + "\nShipped:      " + (long) shipped.count() + " kg\n";
  }
}
