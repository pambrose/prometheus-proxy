package com.sudothought.agent;

import com.google.common.base.MoreObjects;
import com.sudothought.grpc.ScrapeRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.net.HttpHeaders.ACCEPT;

public class PathContext {

  private static final Logger logger = LoggerFactory.getLogger(PathContext.class);

  private final OkHttpClient    okHttpClient;
  private final long            pathId;
  private final String          path;
  private final String          url;
  private final Request.Builder request;

  public PathContext(final OkHttpClient okHttpClient, long pathId, String path, String url) {
    this.okHttpClient = okHttpClient;
    this.pathId = pathId;
    this.path = path;
    this.url = url;
    this.request = new Request.Builder().url(url);
  }

  public String getUrl() {
    return this.url;
  }

  public Response fetchUrl(final ScrapeRequest scrapeRequest)
      throws IOException {
    try {
      logger.debug("Fetching {}", this);
      final Request.Builder request = !isNullOrEmpty(scrapeRequest.getAccept())
                                      ? this.request.header(ACCEPT, scrapeRequest.getAccept())
                                      : this.request;
      return this.okHttpClient.newCall(request.build()).execute();
    }
    catch (IOException e) {
      logger.info("Failed HTTP request: {} [{}: {}]", this.getUrl(), e.getClass().getSimpleName(), e.getMessage());
      throw e;
    }
  }


  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("path", "/" + path)
                      .add("url", url)
                      .toString();
  }
}
