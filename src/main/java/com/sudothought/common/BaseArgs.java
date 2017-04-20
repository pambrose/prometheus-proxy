package com.sudothought.common;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import static com.sudothought.common.EnvVars.DISABLE_METRICS;
import static com.sudothought.common.EnvVars.METRICS_PORT;
import static java.lang.System.getenv;

public class BaseArgs {

  @Parameter(names = {"-m", "--metrics"}, description = "Metrics listen port")
  public  Integer metrics_port    = null;
  @Parameter(names = {"-d", "--disablemetrics"}, description = "Metrics disabled")
  public  Boolean disable_metrics = null;
  @Parameter(names = {"-c", "--conf", "--config"}, description = "Configuration file or url")
  public  String  config          = null;
  @Parameter(names = {"-h", "--help"}, help = true)
  private boolean help            = false;

  public void parseArgs(final String programName, final String[] argv) {
    try {
      final JCommander jcom = new JCommander(this, argv);
      jcom.setProgramName(programName);
      jcom.setCaseSensitiveOptions(false);

      if (this.help) {
        jcom.usage();
        System.exit(1);
      }

    }
    catch (ParameterException e) {
      System.out.println(e.getMessage());
      System.exit(1);
    }
  }

  protected void assignDisableMetrics(final boolean configVal) {
    if (this.disable_metrics == null)
      this.disable_metrics = System.getenv(DISABLE_METRICS) != null
                             ? Boolean.parseBoolean(getenv(DISABLE_METRICS)) :
                             configVal;
  }

  protected void assignMetricsPort(final int configVal) {
    if (this.metrics_port == null)
      this.metrics_port = System.getenv(METRICS_PORT) != null
                          ? Utils.getEnvInt(METRICS_PORT, true).orElse(-1)
                          : configVal; // -1 never returned
  }
}
