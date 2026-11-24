package roastery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The roastery's status page, as text, for curl and {@code demo.sh status}. */
@RestController
public class RoasteryController {
  private final Roastery roastery;
  private final String version;

  // The version from the build info Spring Boot's build-info goal writes, or "unknown" where it didn't run, as in an
  // IDE's build, so the roastery starts either way
  public RoasteryController(Roastery roastery, ObjectProvider<BuildProperties> build) {
    this.roastery = roastery;
    BuildProperties properties = build.getIfAvailable();
    this.version = properties == null || properties.getVersion() == null ? "unknown" : properties.getVersion();
  }

  @GetMapping(value = "/", produces = "text/plain")
  public String status() {
    return "Version:       " + version + "\n" + roastery.status();
  }
}
