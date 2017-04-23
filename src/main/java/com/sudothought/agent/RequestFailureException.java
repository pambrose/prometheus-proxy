package com.sudothought.agent;

public class RequestFailureException
    extends Exception {

  private static final long serialVersionUID = 8748724180953791199L;

  public RequestFailureException(String message) {
    super(message);
  }
}
