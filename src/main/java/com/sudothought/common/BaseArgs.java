package com.sudothought.common;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public class BaseArgs {

  @Parameter(names = {"-c", "--conf", "--config"}, description = "Configuration file or url")
  public  String  config = null;
  @Parameter(names = {"-h", "--help"}, help = true)
  private boolean help   = false;

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

}
