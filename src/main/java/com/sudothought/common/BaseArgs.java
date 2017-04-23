package com.sudothought.common;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import static com.sudothought.common.EnvVars.DISABLE_METRICS;
import static com.sudothought.common.EnvVars.METRICS_PORT;

public class BaseArgs {

  @Parameter(names = {"-m", "--metrics"}, description = "Metrics listen port")
  public  Integer metricsPort    = null;
  @Parameter(names = {"-d", "--disablemetrics"}, description = "Metrics disabled")
  public  Boolean disableMetrics = null;
  @Parameter(names = {"-c", "--conf", "--config"}, description = "Configuration file or url")
  public  String  config         = null;
  @Parameter(names = {"-h", "--help"}, help = true)
  private boolean help           = false;

  public void parseArgs(final String programName, final String[] argv) {
    try {
      final JCommander jcom = new JCommander(this);
      jcom.setProgramName(programName);
      jcom.setCaseSensitiveOptions(false);
      jcom.parse(argv);

      if (this.help) {
        jcom.usage();
        System.exit(0);
      }
    }
    catch (ParameterException e) {
      System.out.println(e.getMessage());
      System.exit(1);
    }
  }

  protected void assignDisableMetrics(final boolean configVal) {
    if (this.disableMetrics == null)
      this.disableMetrics = DISABLE_METRICS.getEnv(configVal);
  }

  protected void assignMetricsPort(final int configVal) {
    if (this.metricsPort == null)
      this.metricsPort = METRICS_PORT.getEnv(configVal);
  }
}
