/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.agent;

import com.google.common.base.MoreObjects;
import io.prometheus.grpc.ScrapeRequest;
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
      final Request.Builder builder = !isNullOrEmpty(scrapeRequest.getAccept())
                                      ? this.request.header(ACCEPT, scrapeRequest.getAccept())
                                      : this.request;
      return this.okHttpClient.newCall(builder.build()).execute();
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
