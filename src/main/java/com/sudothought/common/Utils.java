package com.sudothought.common;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.String.format;

public interface Utils {

  Logger logger = LoggerFactory.getLogger(Utils.class);

  static ThreadFactory newInstrumentedThreadFactory(final String name,
                                                    final String help,
                                                    final boolean daemon) {
    final ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat(name + "-%d")
                                                                  .setDaemon(daemon)
                                                                  .build();
    return new InstrumentedThreadFactory(threadFactory, name, help);
  }

  static String getBanner(final String filename) {
    try (final InputStream in = logger.getClass().getClassLoader().getResourceAsStream(filename)) {
      final String banner = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8.name()));
      final List<String> lines = Splitter.on("\n").splitToList(banner);

      // Use Atomic values because filter requires finals
      // Trim initial and trailing blank lines, but preserve blank lines in middle;
      final AtomicInteger first = new AtomicInteger(-1);
      final AtomicInteger last = new AtomicInteger(-1);
      final AtomicInteger lineNum = new AtomicInteger(0);
      lines.forEach(
          line -> {
            if (line.trim().length() > 0) {
              if (first.get() == -1)
                first.set(lineNum.get());
              last.set(lineNum.get());
            }
            lineNum.incrementAndGet();
          });

      lineNum.set(0);
      final String noNulls =
          Joiner.on("\n")
                .skipNulls()
                .join(
                    lines.stream()
                         .filter(
                             input -> {
                               final int currLine = lineNum.getAndIncrement();
                               return currLine >= first.get() && currLine <= last.get();
                             })
                         .map(input -> format("     %s", input))
                         .collect(Collectors.toList()));
      return format("\n\n%s\n\n", noNulls);
    }
    catch (Throwable e) {
      return format("Banner %s cannot be found", filename);
    }
  }

  static Config readConfig(final String cliConfig,
                           final String envConfig,
                           final ConfigParseOptions configParseOptions)
      throws MalformedURLException {

    // Precedence of confg settings: CLI, ENV_VAR
    final String configName = cliConfig != null ? cliConfig : System.getenv(envConfig);

    if (configName == null) {
      System.err.println(String.format("A configuration file or url must be specified with --config or $%s", envConfig));
      System.exit(1);
    }

    if (configName.startsWith("http://") || configName.startsWith("https://")) {
      final ConfigSyntax configSyntax;
      if (configName.endsWith(".json") || configName.endsWith(".jsn"))
        configSyntax = ConfigSyntax.JSON;
      else if (configName.endsWith(".properties") || configName.endsWith(".props"))
        configSyntax = ConfigSyntax.PROPERTIES;
      else
        configSyntax = ConfigSyntax.CONF;

      return ConfigFactory.parseURL(new URL(configName), configParseOptions.setSyntax(configSyntax));
    }
    else {
      return ConfigFactory.parseFileAnySyntax(new File(configName), configParseOptions);
    }
  }

}
