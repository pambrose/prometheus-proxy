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

package io.prometheus.proxy.dashboard

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
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * The dashboard's login page, which a page request without a session is sent to once `proxy.dashboard.password` is
 * set. Styled after Grafana's: a heading over one centered form, labels above full-width fields, one full-width
 * button, and a red alert above the form after a wrong login. Its colors are the dashboard's own tokens, light or
 * dark as the system is, and it loads nothing: no script, no font, no image.
 */
internal object LoginHtml {
  const val HEADING = "Prometheus Proxy Dashboard"
  const val FAILED = "Invalid username or password"
  const val USERNAME_FIELD = "username"
  const val PASSWORD_FIELD = "password"
  const val NEXT_FIELD = "next"

  /** The longest username or password the form takes, well inside the login route's body limit. */
  const val MAX_FIELD_LENGTH = 1_024

  /**
   * The page, posting to `<routeBase>/login`. [next] is where a successful login goes, which the route checks again;
   * [username] refills the field after a [failed] login, which also shows the alert and focuses the password.
   */
  fun HTML.renderLogin(
    routeBase: String,
    next: String,
    username: String = "",
    failed: Boolean = false,
  ) {
    attributes["lang"] = "en"
    head {
      meta(charset = "utf-8")
      title("Log in · $HEADING")
      meta(name = "viewport", content = "width=device-width, initial-scale=1")
      style { unsafe { raw(STYLE) } }
    }
    body("login") {
      main {
        h1 { +HEADING }
        form(action = "$routeBase/login", method = FormMethod.post, classes = "card") {
          if (failed) {
            div("alert") {
              attributes["role"] = "alert"
              +FAILED
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
              if (!failed) autoFocus = true
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
              if (failed) autoFocus = true
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
    @media (prefers-color-scheme: dark) { :root { --on-accent:var(--bg); } }
    * { box-sizing:border-box; margin:0; padding:0; }
    /* The space left over goes 2:3 above and below the form, so it sits 40% of the way down, not in the middle. */
    body.login { min-height:100vh; display:flex; flex-direction:column; align-items:center; padding:32px 16px;
      font-family:var(--sans); font-size:14px; line-height:1.5; color:var(--ink);
      background:radial-gradient(120% 70% at 50% 0%, var(--surface-3) 0%, var(--bg) 70%) fixed, var(--bg); }
    body.login::before { content:""; flex:2; }
    body.login::after { content:""; flex:3; }
    main { width:100%; max-width:400px; }
    h1 { font-size:28px; font-weight:500; line-height:1.25; text-align:center; margin-bottom:28px;
      letter-spacing:-0.01em; }
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
    """.trimIndent()

  // The whole stylesheet, built once. Declared after CSS, which an object initializes in order.
  private val STYLE = ProxyDashboardCss.TOKENS + "\n" + CSS
}
