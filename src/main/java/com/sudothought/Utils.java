package com.sudothought;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class Utils {

  public static String getHostName() {
    try {
      final String hostname = InetAddress.getLocalHost().getHostName();
      final String address = InetAddress.getLocalHost().getHostAddress();
      //LOGGER.debug("Hostname: {} Address: {}", hostname, address);
      return hostname;
    }
    catch (UnknownHostException e) {
      return "Unknown";
    }
  }

}
