package io.prometheus.common;

import com.beust.jcommander.DynamicParameter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigSyntax;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.prometheus.common.EnvVars.METRICS_ENABLED;
import static io.prometheus.common.EnvVars.METRICS_PORT;
import static java.lang.String.format;

public abstract class BaseOptions {

  private static final Logger             logger     = LoggerFactory.getLogger(BaseOptions.class);
  private static final ConfigParseOptions PROPS      = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES);
  private static final String[]           EMPTY_ARGV = {};

  private final AtomicReference<Config> configRef = new AtomicReference<>();

  private final String     programName;
  private final ConfigVals configVals;

  @Parameter(names = {"-c", "--conf", "--config"}, description = "Configuration file or url")
  private String              configName    = null;
  @Parameter(names = {"-m", "--metrics_port"}, description = "Metrics listen port")
  private Integer             metricsPort   = null;
  @Parameter(names = {"-e", "--metrics"}, description = "Metrics enabled")
  private Boolean             enableMetrics = null;
  @Parameter(names = {"-v", "--version"}, description = "Print version info and exit", validateWith = Utils.VersionValidator.class)
  private boolean             version       = false;
  @Parameter(names = {"-u", "--usage"}, help = true)
  private boolean             usage         = false;
  @DynamicParameter(names = "-D", description = "Dynamic property assignment")
  private Map<String, String> dynamicParams = new HashMap<>();

  protected BaseOptions(final String programName, final String[] argv, final String envConfig,
                        final boolean exitOnMissingConfig) {
    this.programName = programName;
    this.parseArgs(argv);
    this.readConfig(envConfig, exitOnMissingConfig);

    this.configVals = new ConfigVals(this.configRef.get());
  }

  public ConfigVals getConfigVals() { return this.configVals; }

  protected abstract void assignConfigVals(final ConfigVals configVals);

  private void parseArgs(final String[] argv) {
    try {
      final JCommander jcom = new JCommander(this);
      jcom.setProgramName(this.programName);
      jcom.setCaseSensitiveOptions(false);
      jcom.parse(argv == null ? EMPTY_ARGV : argv);

      if (this.usage) {
        jcom.usage();
        System.exit(0);
      }
    }
    catch (ParameterException e) {
      logger.error(e.getMessage(), e);
      System.exit(1);
    }
  }

  protected void assignMetricsPort(final int configVal) {
    if (this.metricsPort == null)
      this.metricsPort = METRICS_PORT.getEnv(configVal);
  }

  protected void assignEnableMetrics(final boolean configVal) {
    if (this.enableMetrics == null)
      this.enableMetrics = METRICS_ENABLED.getEnv(configVal);
  }

  private void readConfig(final String envConfig, final boolean exitOnMissingConfig) {
    final Config config = Utils.readConfig(this.configName,
                                           envConfig,
                                           ConfigParseOptions.defaults().setAllowMissing(false),
                                           ConfigFactory.load().resolve(),
                                           exitOnMissingConfig)
                               .resolve(ConfigResolveOptions.defaults());
    this.configRef.set(config);

    this.dynamicParams.forEach(
        (key, value) -> {
          // Strip quotes
          final String prop = format("%s=%s", key, value.startsWith("\"") && value.endsWith("\"")
                                                   ? value.substring(1, value.length() - 1)
                                                   : value);
          System.setProperty(key, prop);
          final Config newConfig = ConfigFactory.parseString(prop, PROPS);
          configRef.set(newConfig.withFallback(this.configRef.get()).resolve());
        });
  }


  public int getMetricsPort() { return this.metricsPort; }

  public boolean getEnableMetrics() {
    return this.enableMetrics;
  }

  public Map<String, String> getDynamicParams() {
    return this.dynamicParams;
  }
}
