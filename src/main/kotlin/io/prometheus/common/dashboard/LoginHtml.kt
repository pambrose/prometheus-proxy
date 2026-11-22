/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prometheus.common.dashboard

import io.prometheus.common.dashboard.DashboardLogo.favicon
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * A dashboard's login page, which a page request without a session is sent to once the dashboard's password is set
 * (see [DashboardServer]). Styled after Grafana's: the logo and the product's name, the dashboard's name under them as
 * the page's heading, then one centered form, labels above full-width fields, one full-width button, and a red alert
 * above the form after a wrong login. Its colors are the dashboard's own tokens, light or dark as the system is, and it
 * loads nothing: its one image, the logo, and its one script are inline, and it uses no font of its own.
 */
internal object LoginHtml {
  const val FAILED = "Invalid username or password"
  const val LIMITED = "Too many wrong logins. Try again in a minute."
  const val USERNAME_FIELD = "username"
  const val PASSWORD_FIELD = "password"
  const val NEXT_FIELD = "next"

  /** The longest username or password the form takes, well inside the login route's body limit. */
  const val MAX_FIELD_LENGTH = 1_024

  /** The product's name, beside the logo over the heading on both dashboards' login pages. */
  const val PRODUCT = "Prometheus Proxy"

  /**
   * What a login page shows.
   *
   * @param heading the dashboard's name, the page's heading, under the logo and the [PRODUCT]'s name
   * @param routeBase the dashboard's base, under which the form posts to `login`
   * @param next where a successful login goes, which the route checks again
   * @param username what refills the field after a refused login
   * @param alert why the last login was refused, [FAILED] or [LIMITED], which the page shows, focusing the password
   */
  class Form(
    val heading: String,
    val routeBase: String,
    val next: String,
    val username: String = "",
    val alert: String? = null,
  )

  /** The page [page] describes. */
  fun HTML.renderLogin(page: Form) {
    val heading = page.heading
    val routeBase = page.routeBase
    val next = page.next
    val username = page.username
    val alert = page.alert
    attributes["lang"] = "en"
    head {
      meta(charset = "utf-8")
      title("Log in · $heading · $PRODUCT")
      meta(name = "viewport", content = "width=device-width, initial-scale=1")
      favicon()
      // The theme the dashboard's button left, so the login page doesn't show the other one.
      script { unsafe { raw(DashboardPage.THEME_SCRIPT) } }
      style { unsafe { raw(STYLE) } }
    }
    body("login") {
      main {
        header("brand") {
          // The logo is decoration beside the product's name, so it has no text of its own to read.
          p("product") {
            img(src = DashboardLogo.LOGO_URL, alt = "") {
              width = "$LOGO_SIZE"
              height = "$LOGO_SIZE"
            }
            +PRODUCT
          }
          h1 { +heading }
        }
        form(action = "$routeBase/login", method = FormMethod.post, classes = "card") {
          if (alert != null) {
            div("alert") {
              attributes["role"] = "alert"
              +alert
            }
          }
          div("field") {
            label {
              htmlFor = USERNAME_FIELD
              +"Username"
            }
            input(type = InputType.text, name = USERNAME_FIELD) {
              attributes["id"] = USERNAME_FIELD
              value = username
              required = true
              maxLength = "$MAX_FIELD_LENGTH"
              autoComplete = "username"
              attributes["autocapitalize"] = "none"
              attributes["spellcheck"] = "false"
              if (alert == null) autoFocus = true
            }
          }
          div("field") {
            label {
              htmlFor = PASSWORD_FIELD
              +"Password"
            }
            input(type = InputType.password, name = PASSWORD_FIELD) {
              attributes["id"] = PASSWORD_FIELD
              required = true
              maxLength = "$MAX_FIELD_LENGTH"
              autoComplete = "current-password"
              if (alert != null) autoFocus = true
            }
          }
          input(type = InputType.hidden, name = NEXT_FIELD) { value = next }
          button(type = ButtonType.submit) { +"Log in" }
        }
      }
    }
  }

  // The page's own rules, after the dashboard's tokens. Text on the button is --on-accent: the light theme's surface,
  // white, clears 4.5:1 on its accent (4.83:1), and the dark theme's background on its accent (6.94:1).
  private val CSS =
    """
    :root { --on-accent:var(--surface);
      --sans:Inter,system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif; }
    * { box-sizing:border-box; margin:0; padding:0; }
    /* The space left over goes 2:3 above and below the form, so it sits 40% of the way down, not in the middle. */
    body.login { min-height:100vh; display:flex; flex-direction:column; align-items:center; padding:32px 16px;
      font-family:var(--sans); font-size:14px; line-height:1.5; color:var(--ink);
      background:radial-gradient(120% 70% at 50% 0%, var(--surface-3) 0%, var(--bg) 70%) fixed, var(--bg); }
    body.login::before { content:""; flex:2; }
    body.login::after { content:""; flex:3; }
    main { width:100%; max-width:400px; }
    /* The logo and the product's name on a line, centered, and the dashboard's name, the heading, under them, midway
       between that line and the form as the eye reads it: the space above it runs from the product's name, whose
       baseline the logo's legs hang below, to its capitals (27px), and the space below from its baseline to the form's
       edge (28.5px), which its descenders take 3.5px of. */
    .brand { text-align:center; margin-bottom:22px; }
    .product { display:flex; align-items:center; justify-content:center; gap:12px; font-size:28px; font-weight:600;
      line-height:1.25; letter-spacing:-0.01em; }
    .product img { flex:none; }
    h1 { margin-top:11px; font-size:17px; font-weight:500; line-height:1.4; color:var(--ink-2); }
    .card { background:var(--surface); border:1px solid var(--line); border-radius:8px; padding:28px; }
    .alert { margin-bottom:20px; padding:10px 12px; border-left:3px solid var(--crit); border-radius:4px;
      background:var(--crit-soft); color:var(--crit); font-weight:500; }
    .field { display:flex; flex-direction:column; gap:6px; margin-bottom:18px; }
    label { font-size:13px; font-weight:500; color:var(--ink-2); }
    input { height:40px; padding:0 12px; font:inherit; color:var(--ink); background:var(--surface-2);
      border:1px solid var(--line); border-radius:4px; }
    input:focus { outline:2px solid var(--accent); outline-offset:1px; border-color:var(--accent); }
    button { width:100%; height:40px; margin-top:6px; border:0; border-radius:4px; font:inherit; font-weight:600;
      color:var(--on-accent); background:var(--accent); cursor:pointer; }
    button:hover { filter:brightness(1.08); }
    button:focus-visible { outline:2px solid var(--ink); outline-offset:2px; }
    @media (forced-colors: active) {
      .card, input, button { border:1px solid CanvasText; }
      .alert { border-left-color:Mark; }
    }
    """.trimIndent() + "\n" + DashboardCss.darkRule(":root", "--on-accent:var(--bg);")

  // The logo's width and height, in CSS pixels: half again the product name's 28px, as the logo's lockup has it.
  private const val LOGO_SIZE = 42

  // The whole stylesheet, built once. Declared after CSS, which an object initializes in order.
  private val STYLE = DashboardCss.TOKENS + "\n" + CSS
}
