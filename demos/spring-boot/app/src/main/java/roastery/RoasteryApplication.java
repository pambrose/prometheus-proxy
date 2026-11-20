package roastery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// The Spring Boot demo's roastery: a Spring Boot app with a Prometheus agent embedded in it (PrometheusAgentLifecycle),
// which roasts the coffee shop's beans on a schedule (Roastery). See demos/spring-boot/README.md in the repository.
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class RoasteryApplication {
  public static void main(String[] args) {
    SpringApplication.run(RoasteryApplication.class, args);
  }
}
