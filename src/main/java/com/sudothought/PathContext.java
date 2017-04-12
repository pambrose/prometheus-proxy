package com.sudothought;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class PathContext {

  private final OkHttpClient client = new OkHttpClient();

  private final long   path_id;
  private final String path;
  private final String url;

  private Request request;

  public PathContext(long path_id, String path, String url) {
    this.path_id = path_id;
    this.path = path;
    this.url = url;
    this.request = new Request.Builder().url(url).build();
  }

  public long getPath_id() {
    return this.path_id;
  }

  public String getPath() {
    return this.path;
  }

  public String getUrl() {
    return this.url;
  }

  public Response fetchUrl()
      throws IOException {
    return this.client.newCall(this.request).execute();
  }
}
