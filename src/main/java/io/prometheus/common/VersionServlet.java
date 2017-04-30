package io.prometheus.common;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class VersionServlet
    extends HttpServlet {

  private static final long serialVersionUID = -9115048679370256251L;

  @Override
  protected void doGet(HttpServletRequest req,
                       HttpServletResponse resp)
      throws ServletException, IOException {
    resp.setStatus(HttpServletResponse.SC_OK);
    resp.setHeader("Cache-Control", "must-revalidate,no-cache,no-store");
    resp.setContentType("text/plain");
    try (final PrintWriter writer = resp.getWriter()) {
      writer.println(Utils.getVersionDesc());
    }
  }
}
