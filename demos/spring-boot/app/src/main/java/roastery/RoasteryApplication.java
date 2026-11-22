package roastery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// The Spring Boot demo's roastery: a Spring Boot app with a Prometheus agent embedded in it, which the project's Spring
// Boot starter runs from the settings in application.yml, and which roasts the coffee shop's beans on a schedule
// (Roastery). See demos/spring-boot/README.md in the repository.
@SpringBootApplication
@EnableScheduling
public class RoasteryApplication {
  public static void main(String[] args) {
    SpringApplication.run(RoasteryApplication.class, args);
  }
}
