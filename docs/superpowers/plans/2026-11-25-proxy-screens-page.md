# The Proxy's Screens Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/screens` on the proxy's dashboard port: one page holding the proxy's dashboard and every screen
`proxy.dashboard.screens` lists, with tabs, One/Split/All layouts, links into it, and the basics demo moved onto it.

**Architecture:**
- **The page.** `ProxyDashboardService` registers `GET /screens` while screens are listed. It renders a full page with
  kotlinx.html (`ScreensHtml`), styled by `ScreensCss` on top of `DashboardCss`, and driven by an inline
  `ScreensScript`.
- **`DashboardServer`** gains four things:
  - a three-way `Framing` (any site, its own origin, none) in place of the `framingEnabled` boolean;
  - pages outside its base path that a login may return to;
  - a session cookie at `Path=/`;
  - a login page that carries the link's `#…`.
- **The screens** are parsed and checked at startup like `proxy.dashboard.links` (`Screen.parseAll`, modeled on
  `PathLink.parseAll`).

**Tech Stack:** Kotlin, Ktor (CIO), kotlinx.html, Typesafe Config with tscfg `ConfigVals`, Kotest `StringSpec`,
Docker Compose and Bash for the demo.

**Spec:** `docs/superpowers/specs/2026-11-25-proxy-screens-page-design.md`

## Global Constraints

- **The page and the setting.** The path is `/screens`, on the dashboard's port. The setting is
  `proxy.dashboard.screens`: `name` required, `url` and `start` optional, both empty by default. It's a list, so it's
  set in a config file only, with values from the environment through `${?NAME}`.
- **Ids.**
  - A screen's id is its name lowercased, each run of other characters turned into `-`, and `-` trimmed from both
    ends.
  - The page reserves `proxy` (its own tab) and `all` (the grid).
- **Limits:** at most 32 screens; a name of at most 64 characters.
- **Framing.**
  - `framingEnabled = true` (the default): no framing headers.
  - `false` with screens listed: `Content-Security-Policy: frame-ancestors 'self'` and `X-Frame-Options: SAMEORIGIN`.
  - `false` with none listed: `frame-ancestors 'none'` and `DENY`, as in #576.
  - The agent's dashboard has no screens, so it never sends `'self'`.
- **The session cookie** goes out with `Path=/`. Sign-in and sign-out also expire the cookie at the old
  `Path=<basePath>` when the base path isn't `/`.
- **The page follows the dashboards as of #575.** Its tabs reuse `DashboardPage`'s `.nav` markup and
  `DashboardCss`'s rules, each screen's 7px status dot taking the icon's place. Its bar has the Theme Button and,
  with a login, Log out. Every dark rule goes through `DashboardCss.darkRule`.
- **No commits, pushes, or PRs** until the user asks. Work on branch `proxy-screens-page` in the main checkout.
- **Writing.**
  - Changelog entries go in the `[4.7.1]` and `4.7.1` sections, concise.
  - Reader-facing headings follow their file's case.
  - Tour copy follows the demo copy style: explain or give an action, plain titles, links unpunctuated.
- **Before reporting done:**
  `./gradlew detekt lintKotlinMain lintKotlinTest && ./gradlew build -x test`, then
  `./gradlew check unitTest --rerun harnessTest --rerun`, then
  `./gradlew koverVerify koverVerifyPerClass -x unitTest -x harnessTest`.

## Review Focus

1. **An upgraded browser still holding a `Path=/dashboard` session cookie.** It must not loop on the login page: sign-in
   expires the old cookie (Task 2 test).
2. **A dashboard mounted at the root** (`proxy.dashboard.path = "/"`, so `routeBase` is empty). The proxy's tab URL is
   `/`, not `//`, and a login may return to `/screens` (Task 2 and Task 4 tests).
3. **Names with HTML or quote characters.** A screen named `<b>Ops "A"</b>` is escaped in its tab, its pane, its frame's
   `title`, and its `data-` attributes (Task 4 test).
4. **Two names that differ only in case or punctuation**, `Grafana` and `grafana!`. Startup refuses them, naming both
   (Task 1 test).
5. **A link to an id the page doesn't have** (`#nope`, or `#nope+grafana`). It falls back to the proxy's tab without a
   script error. A string check pins the fallback branch, and the final review reads it (Task 4 test).

---

### Task 1: The Setting, Its Checks, and Its Settings Rows

**Files:**
- Modify: `config/config.conf` (proxy dashboard section, after `links`)
- Regenerate: `src/main/java/io/prometheus/common/ConfigVals.java` (`make tsconfig`)
- Create: `src/main/kotlin/io/prometheus/proxy/dashboard/Screen.kt`
- Modify: `src/main/kotlin/io/prometheus/proxy/ProxyOptions.kt:143-146` (field), `:298-327` (`assignDashboardOptions`)
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ProxySettings.kt:230-256`
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/ScreenTest.kt` (new),
  `src/test/kotlin/io/prometheus/proxy/ProxyDashboardOptionsTest.kt`,
  `src/test/kotlin/io/prometheus/proxy/dashboard/ProxySettingsTest.kt`

**Interfaces:**
- Produces: `ConfigVals.Proxy2.Dashboard2.screens: List<Dashboard2.Screens$Elm>` (`name`, `url`, `start`).
- Produces: `internal data class Screen(val id: String, val name: String, val url: String, val start: String)`, with a
  companion that has:
  - `data class Entry(val name: String, val url: String = "", val start: String = "")`;
  - `fun parseAll(entries: List<Entry>): List<Screen>`;
  - `fun idOf(name: String): String`;
  - `const val PROXY_ID = "proxy"`, `const val ALL_ID = "all"`, `const val MAX_SCREENS = 32`, and
    `const val MAX_NAME_LENGTH = 64`. No non-const companion vals, which Kover can't cover.
- Produces: `ProxyOptions.dashboardScreens: List<Screen>` (`internal var`, declared above `init` like
  `dashboardLinks`).

- [ ] **Step 1: Write the failing tests**

`ScreenTest` (`StringSpec`), one case per rule, with these messages exactly:

```kotlin
"a screen's id should be its name in lowercase, each run of other characters a dash" {
  Screen.idOf("agent-east") shouldBe "agent-east"
  Screen.idOf("Prometheus") shouldBe "prometheus"
  Screen.idOf("  Ops / On-Call!  ") shouldBe "ops-on-call"
}
"start should default to empty, and a listed screen keep its order" {
  Screen.parseAll(listOf(Screen.Entry("Grafana", "http://g:3000", "/d/x?kiosk"), Screen.Entry("agent-east", "http://a:8095/dashboard")))
    .map { it.id to it.start } shouldBe listOf("grafana" to "/d/x?kiosk", "agent-east" to "")
}
// refusals, each IllegalArgumentException with this message:
//  blank name                -> "proxy.dashboard.screens entry 2 has a blank name"
//  name with no letter/digit -> "proxy.dashboard.screens screen '!!' has no letter or digit for its id"
//  65-character name         -> "proxy.dashboard.screens screen '<name>' has a name longer than 64 characters"
//  "Grafana" then "grafana!" -> "proxy.dashboard.screens screen 'grafana!' has the id 'grafana', as 'Grafana' does"
//  "Proxy" / "All"           -> "proxy.dashboard.screens screen 'Proxy' has the id 'proxy', which the page keeps for its own tab"
//                               (and "... 'all', which the page keeps for its grid")
//  empty url                 -> "proxy.dashboard.screens screen 'Grafana' has no url; is the variable it reads set?"
//  "ftp://x", "localhost:3000", "javascript:alert(1)"
//                            -> "proxy.dashboard.screens screen 'Grafana' has a url that isn't an absolute http or https URL: <url>"
//  "http://u:p@g:3000"       -> "proxy.dashboard.screens screen 'Grafana' has a username or password in its url, which the page would show to anyone who can read the dashboard"
//  start "d/x"               -> "proxy.dashboard.screens screen 'Grafana' has a start that doesn't begin with / or ?: d/x"
//  33 entries                -> "proxy.dashboard.screens lists 33 screens; at most 32"
```

In `ProxyDashboardOptionsTest`, beside the links cases (:162-212):
- The screens parse and are logged as
  `dashboard.screens: Grafana (http://g:3000/d/x?kiosk), agent-east (http://a:8095/dashboard)`, one entry per screen.
  The proxy's own tab isn't listed. With none, the line reads `dashboard.screens: (none)`.
- Embedded, an invalid entry throws `ConfigLoadException` with the message prefixed `Invalid configuration:`.
- An entry whose `url = ${?SCREENS_TEST_UNSET}` names the entry.
- The screens aren't checked while the dashboard is off.

In `ProxySettingsTest`:
- `Screen Grafana` → `http://g:3000 (opens /d/x?kiosk)`, and `Screen agent-east` → `http://a:8095/dashboard`.
- With none listed: `Screens` → `none`.
- A URL with a query shows through `Utils.sanitizeUrl`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.ScreenTest' --tests 'io.prometheus.proxy.ProxyDashboardOptionsTest' --tests 'io.prometheus.proxy.dashboard.ProxySettingsTest'`
Expected: compilation fails on `Screen`.

- [ ] **Step 3: Add the schema and regenerate**

After `links` in the proxy dashboard section of `config.conf`:

```hocon
    screens: [                                    // Screens /screens shows beside this dashboard, each in a tab: agents' dashboards, Grafana, Prometheus, ...
      {
        name: String                              // The tab's title; its id in links is the name in lowercase, each run of other characters a dash (required)
        url = ""                                  // The screen's address, which links into it resolve against (required; empty here so an unset ${?NAME} is named)
        start = ""                                // The path it opens on, appended to url, such as /alerts
      }
    ]
```

Run `make tsconfig`.
Expected: `git diff --stat` shows `config/config.conf`, and `ConfigVals.java` with a `Screens$Elm` class and a
`screens` field. Nothing else changes.

- [ ] **Step 4: Implement `Screen`, the options wiring, and the Settings rows**

- **`Screen.parseAll`** checks in the order the tests list. It reads the URL with `java.net.URI` and refuses
  `userInfo != null`.
- **`ProxyOptions.assignDashboardOptions`** sets `dashboardScreens = Screen.parseAll(dashboard.screens.map { Screen.Entry(it.name, it.url, it.start) })`
  next to `dashboardLinks`, inside `failOnInvalidConfig` as links are, and logs the line from Step 1.
- **`ProxySettings.dashboard`** adds the rows after the links rows.

- [ ] **Step 5: Run them to verify they pass**

Run: the Step 2 command.
Expected: PASS.

---

### Task 2: The Server: Three-Way Framing, Pages Outside the Base, the Cookie, and the Login's Link

**Files:**
- Create: `src/main/kotlin/io/prometheus/common/dashboard/Framing.kt`
- Modify: `src/main/kotlin/io/prometheus/common/dashboard/DashboardServer.kt`. Touch:
  - `DashboardSettings`, `:111-124`;
  - `DashboardSite`, `:156-182`;
  - `noFraming`, `:504-513`, and its install, `:531`;
  - `sessionCookieHeader`, `:454-461`, and its callers;
  - `safeNext` and `isDashboardPage`, `:945-971`;
  - the login route that calls `safeNext`, `:435` and `:593`.
- Modify: `src/main/kotlin/io/prometheus/common/dashboard/LoginHtml.kt` (an inline script after the form)
- Modify: `ProxyDashboardService.kt:164-180`, `AgentDashboardService.kt` (the `DashboardSettings(...)` call), both
  sites' `outsidePages`
- Test: `src/test/kotlin/io/prometheus/common/dashboard/DashboardResponsesTest.kt`, `DashboardPushTest.kt` (its site
  and settings), the spec that covers `safeNext` today, `LoginHtmlTest.kt`,
  `src/test/kotlin/io/prometheus/common/TestPorts.kt`

**Interfaces:**
- Produces: `internal enum class Framing(val frameAncestors: String?, val xFrameOptions: String?)`:
  - entries `ANY(null, null)`, `SAME_ORIGIN("frame-ancestors 'self'", "SAMEORIGIN")`, and
    `NONE("frame-ancestors 'none'", "DENY")`;
  - `companion object { fun of(framingEnabled: Boolean, hasScreens: Boolean): Framing }`.
- Produces: `DashboardSettings.framing: Framing`, which replaces `framingEnabled: Boolean` in the same position, so the
  constructor stays at 12 parameters, detekt's limit.
- Produces: `DashboardSite.outsidePages: Set<String>`, abstract, with no default (a default member would emit an
  uncoverable class). The agent's site is `emptySet()`. The proxy's is `setOf(ProxyDashboardService.SCREENS_PATH)`
  when screens are listed.
- Produces: `safeNext(next: String?, routeBase: String, outsidePages: Set<String> = emptySet()): String`.
- Produces: `ProxyDashboardService.SCREENS_PATH = "/screens"` (`const val` in its companion).

- [ ] **Step 1: Write the failing tests**

- **`DashboardResponsesTest`**: `withServer` takes `framing: Framing = Framing.NONE` in place of `framingEnabled`, and
  `PlainSite` gets `outsidePages = setOf("/outside")`. Add
  `const val DASHBOARD_RESPONSES_FRAMING_SELF_PORT = 11434` to `TestPorts`. The new case: with `Framing.SAME_ORIGIN`,
  every kind of response the first case lists carries `frame-ancestors 'self'` once and `X-Frame-Options: SAMEORIGIN`
  once, and a brand mark keeps its `sandbox` policy beside it. The existing cases pass `Framing.NONE` and
  `Framing.ANY` where they passed `false` and `true`.
- **`safeNext`**:
  - `safeNext("/outside?x=1", "/dashboard", setOf("/outside")) shouldBe "/outside?x=1"`.
  - `safeNext("/outside/../x", "/dashboard", setOf("/outside")) shouldBe "/dashboard"`.
  - `safeNext("/elsewhere", "/dashboard", setOf("/outside")) shouldBe "/dashboard"`.
  - At a root mount: `safeNext("/outside", "", setOf("/outside")) shouldBe "/outside"`.
- **The cookie**, in a `DashboardResponsesTest` case with a login, against the stand-in site:
  - Signing in sets `Path=/` on the session cookie.
  - The same response carries a second `Set-Cookie` for the same name with `Max-Age=0; Path=/dashboard`.
  - Signing out does the same.
  - With `path = "/"`, there's no second header.
- **`LoginHtmlTest`**: the login page has a script that appends `location.hash` to the form's `action`, so the hash
  survives the 303 back. The hidden `next` field is unchanged.
- **A login returning outside the base**, against the stand-in site: `GET /outside` with `Accept: text/html` and no
  session is a 303 to `/dashboard/login?next=%2Foutside`. A sign-in posting `next=/outside` answers 303 to `/outside`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew unitTest --tests 'io.prometheus.common.dashboard.*'`
Expected: compilation fails on `Framing` and `outsidePages`.

- [ ] **Step 3: Implement**

- **`noFraming`** reads `settings.framing`:
  - It adds `frameAncestors` unless some policy already has `frame-ancestors`, and `xFrameOptions` if it's missing, as
    today.
  - It's installed when `settings.framing != Framing.ANY`.
- **`sessionCookieHeader`** writes `Path=/`. Sign-in and sign-out add the expiring header for `basePath` when it isn't
  `/`.
- **`safeNext`** also accepts a path in `outsidePages` (the part before `?`), under the same `isPlainPath` and no-`.`
  rules. The login routes pass `site.outsidePages`.
- **Both services** pass `framing = Framing.of(dashboardConfig.framingEnabled, hasScreens)`. The agent's `hasScreens`
  is always `false`.
- **The comments** on `noFraming` and `DashboardSettings` say what each `Framing` sends.

- [ ] **Step 4: Run them to verify they pass**

Run: the Step 2 command, then
`./gradlew harnessTest --tests 'io.prometheus.harness.ProxyWebDashboardLoginTest' --tests 'io.prometheus.harness.AgentWebDashboardTest' --tests 'io.prometheus.harness.ProxyWebDashboardTest'`.
Expected: PASS once `ProxyWebDashboardLoginTest`'s cookie assertion (`:255`) reads `Path=/`. That's the only harness
edit this task makes.

---

### Task 3: Saying What Framing Does

**Files:**
- Modify: `src/main/kotlin/io/prometheus/common/dashboard/CommonSettings.kt` (`DashboardValues.framingEnabled` →
  `framing: Framing`; the Framing row at `:179`)
- Modify: `ProxySettings.kt`, `AgentSettings.kt` (pass `Framing.of(...)`)
- Modify: `src/main/kotlin/io/prometheus/proxy/ProxyOptions.kt` (a startup line after the screens parse)
- Test: `CommonSettingsTest.kt`, `ProxyDashboardOptionsTest.kt`

**Interfaces:**
- Consumes: `Framing` and `Framing.of` (Task 2), and `ProxyOptions.dashboardScreens` (Task 1).

- [ ] **Step 1: Write the failing tests**

- **`CommonSettingsTest`**: the Framing row is:
  - `allowed, by any site` for `Framing.ANY`;
  - `refused, but for this proxy's /screens` for `Framing.SAME_ORIGIN`;
  - `refused` for `Framing.NONE`.
- **`ProxyDashboardOptionsTest`**: with `framingEnabled = false` and a screen listed, startup logs exactly
  `dashboard framing: refused, but for /screens, which shows the dashboard from its own origin (proxy.dashboard.framingEnabled = false)`.
  With no screens it logs no framing line. With framing on, the existing line is unchanged.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew unitTest --tests 'io.prometheus.common.dashboard.CommonSettingsTest' --tests 'io.prometheus.proxy.ProxyDashboardOptionsTest'`
Expected: compilation fails on `framing`.

- [ ] **Step 3: Implement**

Change the row and the call sites. `ProxyOptions` logs the line when `!framingEnabled && dashboardScreens.isNotEmpty()`.

- [ ] **Step 4: Run them to verify they pass**

Run: the Step 2 command.
Expected: PASS.

---

### Task 4: The Page

**Files:**
- Create: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensHtml.kt`, `ScreensCss.kt`, `ScreensScript.kt`
- Modify: `src/main/kotlin/io/prometheus/common/dashboard/DashboardScript.kt` (move the Theme Button's handler, `:356`
  on, into something `ScreensScript` includes too; the dashboards' behavior is unchanged)
- Modify: `ProxyDashboardService.kt` (`pageRoutes`: `GET SCREENS_PATH` while screens are listed; the snapshot's
  `dashboardName` for the proxy's tab)
- Modify: `ProxyDashboardHtml.kt` (`renderPage`'s bar: a `Screens` link before `barEnd` when screens are listed),
  `ProxyDashboardCss.kt` (its style, the same quiet weight as Log out)
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/ScreensHtmlTest.kt`, `ScreensScriptTest.kt` (new);
  `ProxyDashboardHtmlTest.kt`; `DashboardScriptTest.kt` (still passes);
  `src/test/kotlin/io/prometheus/harness/ProxyScreensTest.kt` (new); `TestPorts.kt` (its ports)

**Interfaces:**
- Consumes: `Screen` (Task 1), `SCREENS_PATH` and `outsidePages` (Task 2).
- Produces: `internal object ScreensHtml`, with:
  - `data class Page(val proxyName: String, val routeBase: String, val screens: List<Screen>, val loginEnabled: Boolean)`;
  - `fun HTML.renderScreens(page: Page)`.
- Produces: `internal object ScreensCss { val CSS: String }` and `internal object ScreensScript { val JS: String }`.
- **Markup the script reads, which the tests pin:**
  - `nav#screen-nav.nav` holds `a.screen[href="#<id>"][data-screen=<id>]`. The current one has `on` and
    `aria-current="page"`.
  - A state class, `up` or `down`, on each tab gives its dot (`.nav a.screen::before`, 7px).
  - Each dot has a visually hidden text equivalent.
  - `button[data-layout=one|split|all]` with `aria-pressed`.
  - `main#stage.stage.one` holds a `section.pane[data-screen=<id>]` per screen, the proxy's first.
  - Each pane has `.pane-bar` (the name, a `select` of every screen, and `a.open[target=_blank]` reading
    `Open in a tab ↗`), and `.view` holding `iframe[title][data-url][data-start]` (no `src` until shown) and
    `div.down`.
  - The proxy's pane: `data-url` is `routeBase.ifEmpty { "/" }` and `data-start` is empty.

- [ ] **Step 1: Write the failing tests**

`ScreensHtmlTest`:
- **Order.** The tabs and panes come in the config's order, after the proxy's.
- **Tab markup.** `href="#agent-east"`, `data-screen="agent-east"`, and the dot's text equivalent.
- **Escaping.** A name of `<b>Ops "A"</b>` is escaped everywhere it appears (Review Focus 3).
- **Root mount.** With `routeBase = ""`, the proxy pane's `data-url` is `/`, not `//` (Review Focus 2).
- **The bar.** The Theme Button is present. Log out appears only with `loginEnabled`.
- **The head.** It comes from `dashboardHead`, so `THEME_SCRIPT` and `DashboardCss.CSS` are in it.
- **Dark rules.** `ScreensCss.CSS` has no bare `prefers-color-scheme` block, every dark rule coming through `darkRule`.

`ScreensScriptTest` (string checks, as `DashboardScriptTest` does):
- The script uses `BroadcastChannel("prometheus-proxy-screens")`.
- It probes with `mode: "no-cors"`, pausing on `visibilitychange`.
- It updates the hash with `history.replaceState`.
- **Links.** `parseLink` handles `all`, `<id>`, `<id>/<path>`, and `<a>+<b>`.
- **The load rule.** A path reloads the frame; a bare id never does. Both branches are present.
- **Unknown ids.** They fall back to `proxy` (Review Focus 5).
- **No `.src =` without the load rule.** A frame is never moved in the page; layouts only set classes and sizes.

`ProxyDashboardHtmlTest`: the bar has `<a href="/screens"` only when screens are listed.

`ProxyScreensTest` (harness; new `TestPorts` entries following the harness specs' pattern; `TestPortsTest` checks
them):
- With no screens, `GET /screens` is 404 and the dashboard has no Screens link.
- With two screens, `GET /screens` is 200, has three tabs (the proxy's first), and needs no htmx swap.
- With `-Dproxy.dashboard.password=…`, `GET /screens` (`Accept: text/html`) is a 303 to `/dashboard/login?next=%2Fscreens`.
  Signing in with `next=/screens` answers 303 to `/screens`, with the session cookie's `Path=/`.
- With `-Dproxy.dashboard.framingEnabled=false` and screens, `/dashboard` and `/screens` both send
  `frame-ancestors 'self'`. Without screens, `'none'`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.Screens*' --tests 'io.prometheus.proxy.dashboard.ProxyDashboardHtmlTest'`
Expected: compilation fails on `ScreensHtml`.

- [ ] **Step 3: Implement**

The prototype's behavior is the reference, rewritten into these objects with the dashboards' tokens.
`.superpowers/prototypes/screens-prototype.html` has it (git-ignored; it reached the demo through an nginx that
stripped the framing headers, which the real page doesn't need). The rules:

- **The tabs** are links in `#screen-nav`. Left and Right step through them, as the dashboards' `#layout-nav` does.
- **Each frame** gets `src` the first time its pane shows, and keeps it. A link with a path sets `src` again. A bare id
  doesn't.
- **Split:** the selected screen on the left and a second on the right. Each pane's `select` swaps it, and
  shift-clicking a tab fills the right pane.
- **All:** each `.view` lays its frame out at 1280×800 and scales it to the tile's width
  (`transform: scale(...)`, measured with a `ResizeObserver`). Clicking a pane's bar opens it in One.
- **The handoff.** On load with a hash, the page posts the link on the channel and waits up to 300 ms for an answer.
  - If another copy answers, it follows the link, and this copy calls `window.close()`. If the browser refuses, this
    copy shows a notice, `This link opened in your other Screens tab.`, with a `Stay here instead` button that makes
    it the page.
  - Without a hash, there's no handoff.
- **The dots.** Every 5 seconds, while the page is visible, the page probes each screen's `data-url` with `no-cors`. A
  resolved request is up and a rejected one is down. A screen going from down to up reloads its frame if it's shown.

- [ ] **Step 4: Run them to verify they pass**

Run: the Step 2 command, then `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.DashboardScriptTest'` and
`./gradlew harnessTest --tests 'io.prometheus.harness.ProxyScreensTest'`.
Expected: PASS.

- [ ] **Step 5: See it**

Run a proxy built from the branch with two screens, on ports in the 18xxx range. Open `/screens` in Chrome at 1440×900
and at 390px, in both themes. Check One, Split, All, a link, the handoff (open a second link from the terminal), and
the dots, by stopping one screen.
Expected: it behaves as the prototype did, in the dashboards' style. Screenshots go in the scratchpad.

---

### Task 5: The Basics Demo on the Page

**Files:**
- Modify: `demos/basics/config/proxy.conf` (a `screens` list after `links`)
- Modify: `demos/basics/docker/compose.yml`:
  - the proxy service's `environment`: `DEMO_*_URL` values built from the port variables, with the same defaults;
  - Grafana's `environment`: `GF_SECURITY_ALLOW_EMBEDDING: "true"`;
  - the header comment.
- Modify: `demos/basics/demo.sh`:
  - `NEEDS_RELEASE_AFTER` (`:64`);
  - the URL variables (`:77-86`) and `SCREENS_URL`;
  - `start_demo`'s waits;
  - `print_urls` (`:169-185`) and `cmd_open` (`:402-416`);
  - `usage`'s `open` line;
  - the tour lines `640-762`, and the scenario `point` lines `947`, `970`, `1004`, `1008`, and `1036`.
- Modify: `demos/basics/Makefile` (`open`'s help), `demos/basics/README.md`, `website/prometheus-proxy/docs/basics-demo.md`
- Create: `src/test/kotlin/io/prometheus/misc/DemoScreensLinksTest.kt`

**Interfaces:**
- Consumes: `Screen.idOf`, `Screen.PROXY_ID`, and `Screen.ALL_ID` (Task 1). The link grammar is from the spec's
  Links section.

- [ ] **Step 1: Write the failing test**

`DemoScreensLinksTest`:
- It reads the screens' names from `demos/basics/config/proxy.conf`, through Typesafe Config with the system off. That
  works because every value is `${?…}` with a default.
- Every `/screens#<link>` in `demos/basics/demo.sh` (as `${SCREENS_URL}#…` or `$SCREENS_URL#…`), in the README, and
  in `basics-demo.md` (as `http://localhost:8094/screens#…`) parses.
- Each link names only `proxy`, `all`, or a listed screen's id.
- At least one link of each form appears: `<id>`, `<id>/<path>`, and `<a>+<b>`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew unitTest --rerun --tests 'io.prometheus.misc.DemoScreensLinksTest'`
Expected: FAIL. The demo has no screens or links yet.

- [ ] **Step 3: Implement the demo**

- **`proxy.conf`.** Each screen sets its URL twice, the default first and then the environment's, which wins when set
  (the `examples/simple.conf` idiom), for example
  `{ name = "Grafana", url = "http://localhost:3000", url = ${?DEMO_GRAFANA_URL}, start = "/d/prometheus-proxy-demo?kiosk" }`.
  - The list: agent-east, agent-west-1, agent-west-2, agent-rogue, agent-east-copy (each `…/dashboard`), Prometheus
    (`start = "/alerts"`), and Grafana.
- **compose.** It passes `DEMO_GRAFANA_URL: http://localhost:${DEMO_GRAFANA_PORT:-3000}` and the like for each screen.
- **`demo.sh`.**
  - `NEEDS_RELEASE_AFTER=4.7.0`, with the comment saying the screens page and framing need 4.7.1.
  - `SCREENS_URL="http://localhost:${DEMO_DASHBOARD_PORT:-8094}/screens"`, which `start_demo` waits for.
  - `print_urls` prints the page as `Every screen:`, the discovery URL, and the docs link, then the scenarios lines as
    today.
  - `cmd_open` opens `$SCREENS_URL`.
- **The tour.**
  - Part 1 gains a last `instruct`, reading `Open the demo's screens: $SCREENS_URL` and then a sentence saying each tab
    is one of the diagram's boxes, and that the steps from here link into it.
  - Parts 2–10 open `$SCREENS_URL#…` as the spec's Basics Demo section lists. Part 9's proxy-down step says the page
    stays open through it.
  - Part 11's step has the reader sign in on the page's proxy tab, with no link.
  - The scenario `point`s link into the page the same way.
- **The README and the docs page.**
  - Quick Start leads with the page.
  - The URL table stays, introduced as each screen's own address and the variable that moves it, with a row for the
    page.
  - The `make open` line describes the page.
  - The Guided Tour's links match `demo.sh`.

- [ ] **Step 4: Run the demo specs**

Run: `rm -rf build/test-results/unitTest && ./gradlew unitTest --rerun --tests 'io.prometheus.misc.*' --tests 'io.prometheus.common.TestPortsTest'`
Expected: PASS, `DemoTourTablesTest`, `DemoReleaseGateTest`, `ConfigFileKeysTest`, `CiHygieneTest`, and
`DemoScreensLinksTest` among them.

- [ ] **Step 5: See it in the demo**

The basics demo is shared across checkouts, so ask the user first. Then run `make -C demos/basics up-local` and walk the
tour's parts 1, 2, 4, and 9 through the page in Chrome.
Expected: every tab loads, including Grafana in kiosk mode. A link from the terminal lands in the open page, and part
9's stopped proxy turns its dot red while agent-east's tab keeps working.

---

### Task 6: Docs, Changelogs, and Rules

**Files:**
- Modify: `website/prometheus-proxy/docs/web-dashboard.md`:
  - a Screens section;
  - Exposure's Framing bullet, for `false` with screens;
  - Behind a Reverse Proxy, with `/screens` in the nginx example;
  - Login, for the cookie at `/`, logins in frames, and SSO.
- Modify: `website/prometheus-proxy/docs/configuration/proxy.md` (rows for `screens`, `screens.name`, `screens.url`,
  `screens.start`, and a paragraph), `production.md`, `SECURITY.md` (the framing line), `llms.txt`, `AGENTS.md` (the
  Dashboard bullet), `.claude/rules/dashboard-constraints.md` (the framing bullet, and a bullet for the page's
  frames-never-move rule), `docs/DESIGN.md` (a short Screens page entry), `docs/TESTING.md` (every new spec), and
  `CHANGELOG.md` and `RELEASE_NOTES.md` (4.7.1).

- [ ] **Step 1: Write the docs**

Within the spec's Docs goals, these points, each said once where a reader looks for it:
- every screen must allow framing, with Grafana's setting named;
- a login inside a frame works on the same site, and "Open in a tab" covers the rest;
- SSO in front of everything gives one login;
- the page is down while the proxy is;
- `false` with screens means `'self'`;
- a reverse proxy passes `/screens`.

RELEASE_NOTES gets a New Features entry, plus a Before You Upgrade line: a dashboard's session cookie moves to
`Path=/`, so a reverse proxy that rewrites cookie paths should leave it alone.

- [ ] **Step 2: Run the docs specs**

Run: `rm -rf build/test-results/unitTest && ./gradlew unitTest --rerun --tests 'io.prometheus.misc.*'`
Expected: PASS, `WebsiteConfigKeysTest` (the new rows' defaults), `ConfigFileKeysTest`, `TestingGuideTest`, and
`CliReferenceTest` among them.

---

### Task 7: Verification

- [ ] **Step 1: Lint and build.** Run `./gradlew detekt lintKotlinMain lintKotlinTest && ./gradlew build -x test`.
  Expected: BUILD SUCCESSFUL.
- [ ] **Step 2: Full suite and coverage.** Run `./gradlew check unitTest --rerun harnessTest --rerun`, then
  `./gradlew koverVerify koverVerifyPerClass -x unitTest -x harnessTest`. Expected: BUILD SUCCESSFUL, with each new
  class over the per-class floor.
- [ ] **Step 3: The consumers and CodeScene.** Run `make ktor-demo-check plain-kotlin-demo-check` (nothing they use
  should change) and `make codescene`. Expected: both pass, with no new CodeScene finding outside `src/test/`.
- [ ] **Step 4: Report.** Summarize for the user, with the screenshots from Task 4 Step 5. Leave everything uncommitted.
