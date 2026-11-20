package roastery;

import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The roastery's status page, as text, for curl and {@code demo.sh status}. */
@RestController
public class RoasteryController {
  private final Roastery roastery;
  private final BuildProperties build;

  public RoasteryController(Roastery roastery, BuildProperties build) {
    this.roastery = roastery;
    this.build = build;
  }

  @GetMapping(value = "/", produces = "text/plain")
  public String status() {
    return "Version:      " + build.getVersion() + "\n" + roastery.status();
  }
}
