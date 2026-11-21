# Dashboard Login Form Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task,
> inline in the main checkout (the maintainer runs no subagents). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dashboard's HTTP Basic Auth browser prompt with a login page styled after Grafana's, backed by
an in-memory session cookie, while still accepting Basic Auth from scripts.

**Architecture:** A new `DashboardSessions` holds sessions; `DashboardLogin` checks a form's or a Basic header's
credentials; `LoginHtml` renders the page. `ProxyDashboardService`'s application-wide `authCheck` plugin admits a
valid cookie or Basic header, sends page navigations to the login page, and answers everything else with a bare 401;
new `login`, `logout`, and `session` routes sit beside the others.

**Tech Stack:** Kotlin, Ktor 3 (CIO server, kotlinx.html), Kotest `StringSpec`, Ktor client in the harness specs.

**Spec:** `docs/superpowers/specs/2026-11-20-dashboard-login-form-design.md`

## Global Constraints

- The settings don't change: `proxy.dashboard.password` / `DASHBOARD_PASSWORD` turns the login on;
  `proxy.dashboard.username` / `DASHBOARD_USERNAME` defaults to `admin`. No new config keys.
- Never send `WWW-Authenticate`: no browser may show its own prompt.
- The page's heading reads `Prometheus Proxy Dashboard`; no Grafana or Prometheus logo.
- Session: 24 hours idle, 7 days at most, 1,000 at once, a 256-bit random token, only its SHA-256 kept.
- Cookie: `prometheus_proxy_dashboard_<dashboard port>`, `HttpOnly`, `SameSite=Lax`, `Path` = the base path (`/` at a
  root mount), `Max-Age=604800`, `Secure` when the login POST's `Origin` is `https://…`.
- A login POST body is at most 4 KB with a `Content-Length`; a wrong login waits 1 second.
- No commits: the maintainer commits only when asked (global instructions), so the commit steps are omitted.
- `.claude/rules/dashboard-constraints.md` holds: Log out stays outside every `hx-swap-oob` region; every action stays
  behind an Origin check.

## Review Focus

- A `next` that leaves the dashboard (`//evil.example`, `https://evil.example/`, `/\evil.example`, `/other`) must land
  on the base, never off-site: `safeNext` tests in Task 4.
- A dashboard mounted at the root (`path = "/"`): login at `/login`, cookie `Path=/`, redirects work: Task 4 harness.
- A session that expires while the page is open must end on the login page, not a dashboard stuck "reconnecting":
  Task 5 script test and Task 4's `/session` 401 after logout.
- An HA pair on one host (two dashboard ports): each proxy's cookie has its own name: `cookieName` test in Task 4.
- A huge or chunked unauthenticated login POST must get a 413 without being read whole: Task 4 harness.

---

### Task 1: Sessions

**Files:**
- Create: `src/main/kotlin/io/prometheus/proxy/dashboard/DashboardSessions.kt`
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/DashboardSessionsTest.kt`

**Interfaces:**
- Produces: `internal class DashboardSessions(idleTimeout: Duration = 24.hours, maxAge: Duration = 7.days,
  maxSessions: Int = 1_000, timeSource: TimeSource = TimeSource.Monotonic)` with `fun create(): String`,
  `fun isValid(token: String?): Boolean` (a valid one counts as a request, restarting its idle time),
  `fun remove(token: String?)`, and `val size: Int`. Companion `const val MAX_AGE_SECS = 604_800`.

- [ ] **Step 1: Write the failing tests** (`TestTimeSource`):
  - `a new session is valid, and a token it never issued, null, or empty isn't`
  - `tokens are 43 base64url characters and differ each time` (`Regex("[A-Za-z0-9_-]{43}")`, 100 distinct)
  - `a session ends 24 hours after its last request, and a request restarts that` (+23h valid, +23h valid, +24h1s not)
  - `a session ends 7 days after it began, however recently it was used` (touch every 12h; invalid past 7 days)
  - `removing a session ends it`
  - `past 1,000 sessions the least recently used goes` (maxSessions = 3: create a,b,c, touch a, create d → b invalid)
  - `an expired session is forgotten` (size drops after `isValid` on it)
- [ ] **Step 2: Run** `./gradlew test --tests 'io.prometheus.proxy.dashboard.DashboardSessionsTest'` — FAIL (missing).
- [ ] **Step 3: Implement.** `SecureRandom` 32 bytes, `Base64.getUrlEncoder().withoutPadding()`; key a
  `LinkedHashMap(accessOrder = true)` by the token's SHA-256 (hex), guarded by its monitor, each entry holding the
  login's and last request's `TimeMark`.
- [ ] **Step 4: Run** the spec — PASS.

### Task 2: Form credentials

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/DashboardLogin.kt`
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/DashboardLoginTest.kt`

**Interfaces:**
- Produces: `fun accepts(username: String, password: String): Boolean` beside `fun accepts(header: String?)`; both
  compare both halves every time. `CHALLENGE` is removed. KDoc says why: browsers get the page, scripts send Basic.

- [ ] **Step 1: Tests** `a form's username and password should be accepted only when both match` (right pair; wrong
  user; wrong password; empty; UTF-8 pair) and drop the `CHALLENGE` assertion.
- [ ] **Step 2: Run** `--tests '…DashboardLoginTest'` — FAIL.
- [ ] **Step 3: Implement** by encoding both as UTF-8 and reusing `matches`.
- [ ] **Step 4: Run** — PASS.

### Task 3: The login page

**Files:**
- Create: `src/main/kotlin/io/prometheus/proxy/dashboard/LoginHtml.kt`
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/LoginHtmlTest.kt`

**Interfaces:**
- Produces: `internal object LoginHtml` with `const val HEADING = "Prometheus Proxy Dashboard"`,
  `const val FAILED = "Invalid username or password"`, and
  `fun HTML.renderLogin(routeBase: String, next: String, username: String = "", failed: Boolean = false)`. Form fields
  `username`, `password`, `next` (`LoginHtml.USERNAME_FIELD`, `PASSWORD_FIELD`, `NEXT_FIELD`).

- [ ] **Step 1: Tests:**
  - `the page has the heading, a form posting to the login route, and labeled fields` (`<h1>` with `HEADING`;
    `action="/dashboard/login"`, `method="post"`; `autocomplete="username"` and `"current-password"`; `<label for=…>`
    for each; one submit button `Log in`; `maxlength` on both fields)
  - `a failed login shows the alert and keeps the username` (`role="alert"` containing `FAILED`; `value="ops"`; focus on
    the password: `autofocus` there, else on the username)
  - `no alert without a failure`
  - `the next path and the username are escaped` (`next = "/dashboard/paths?q=\"><script>"` appears escaped)
  - `at a root mount the form posts to /login`
  - `the page loads nothing from anywhere`: no `<script`, no `src=`, no `http` URL
  - `its colors follow the dashboard's tokens in both themes` (`prefers-color-scheme` present; `--accent` used)
- [ ] **Step 2: Run** `--tests '…LoginHtmlTest'` — FAIL.
- [ ] **Step 3: Implement.** Inline `<style>`: the dashboard's `:root` color tokens (from `ProxyDashboardCss`, shared,
  not copied, by extracting its token block into a `TOKENS` constant both use) plus the login's own rules: a
  full-height radial gradient from `--surface` to `--bg`; a centered 400px column; the heading at 28px, weight 500, a
  system sans-serif stack (`Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`); labels above 40px
  inputs on `--surface-2` with a 1px `--line` border, 4px radius, `--accent` focus ring; a full-width 40px `--accent`
  button with white text (check contrast ≥ 4.5:1 on both themes' accents, darkening the light one if needed); the alert
  in `--crit-soft` with a `--crit` left border. `<title>`: `Log in · Prometheus Proxy Dashboard`.
- [ ] **Step 4: Run** — PASS.

### Task 4: Routes, the auth check, and Log out

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardService.kt`
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardHtml.kt` (`renderPage(…, loginEnabled = false)`
  renders a `form.logout` posting to `<base>/logout` with a `Log out` button after the status, outside every pushed
  region), `ProxyDashboardCss.kt` (its rules; the token block extracted per Task 3)
- Test: `src/test/kotlin/io/prometheus/harness/ProxyWebDashboardLoginTest.kt` (rewrite),
  `src/test/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardServiceTest.kt`,
  `src/test/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardHtmlTest.kt`, `common/TestPorts.kt` (a root-mount trio)

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces (companion, `internal`): `fun safeNext(next: String?, routeBase: String): String`,
  `fun cookieName(port: Int): String`, `fun wantsPage(request: ApplicationRequest): Boolean` (a GET whose `Accept`
  includes `text/html` and that has no `HX-Request`); constants `MAX_LOGIN_BODY_BYTES = 4_096`,
  `LOGIN_FAILURE_DELAY = 1.seconds`. `ProxyDashboardHtml.LOGOUT_ID = "logout"`.

- [ ] **Step 1: Unit tests** in `ProxyDashboardServiceTest`:
  - `safeNext keeps a path under the base and sends anything else to the base` (`/dashboard/paths?q=a` kept;
    `/dashboard` kept; `null`, `""`, `//evil.example`, `https://evil.example/`, `/\evil.example`, `/other`,
    `/dashboardx` → `/dashboard`; at a root mount `/paths` kept and `//evil.example` → `/`)
  - `each dashboard port has its own cookie` (`cookieName(8094) != cookieName(8194)`)
- [ ] **Step 2: Harness tests** (`ProxyWebDashboardLoginTest`, password set; client without redirects):
  - `a page navigation without a session goes to the login page` (Accept `text/html` on `/dashboard/paths?q=a` → 303,
    `Location: /dashboard/login?next=%2Fdashboard%2Fpaths%3Fq%3Da`)
  - `anything else without a session gets a 401 and no challenge` (WebSocket handshake, scrape POST, `fetch`-style GET,
    `/session`, an asset; no `WWW-Authenticate` on any)
  - `the login page is reachable and shows the heading` (GET `/dashboard/login` → 200, `no-store`,
    `frame-ancestors 'none'`, contains `Prometheus Proxy Dashboard`)
  - `a wrong login waits, says so, and is logged once` (≥ 1s; 401; alert text; one WARN for two, no username)
  - `a right login sets the cookie and returns to next` (303 to `next`; `Set-Cookie` with the name, `HttpOnly`,
    `SameSite=Lax`, `Path=/dashboard`, `Max-Age=604800`, no `Secure`; with `Origin: https://…` it has `Secure`)
  - `with the cookie, the dashboard works` (page 200 with `id="logout"`; WebSocket 101 and a frame; `/session` 204;
    `/dashboard/login` → 303 to `/dashboard`)
  - `Log out ends the session` (POST `/dashboard/logout` → 303 to the login page, cookie expired; the old cookie then
    gets 401 on `/session`)
  - `a foreign origin can't log in or out` (403 each)
  - `an oversized or chunked login body is refused` (5 KB body → 413; no `Content-Length` → 413)
  - `scripts still log in with Basic Auth` (`basicAuth` → 200; a wrong one → 401)
  - `at a root mount, the login lives at /login` (`path = "/"`: 303 to `/login?next=%2F`; cookie `Path=/`)
  - `with no password set, there is no login page` (`/dashboard/login` → 404; no `id="logout"`)
- [ ] **Step 3: Run** both specs — FAIL.
- [ ] **Step 4: Implement.** `authCheck` skips the two `login` routes; admits `sessions.isValid(cookie)` or
  `login.accepts(header)` (a present, wrong header logs `logBadLogin`); else 303 to the login page for `wantsPage`,
  else a bare 401. Routes, registered only with a login: `get/post("$routeBase/login")`, `post("$routeBase/logout")`;
  `get("$routeBase/session")` always (204). POSTs run `pageRefusal`'s Origin check without its `HX-Request` rule
  (split it into `originRefusal`). The login POST checks `Content-Length` before `receiveParameters`. `Proxy.kt` needs
  no change.
- [ ] **Step 5: Run** both specs, `ProxyDashboardHtmlTest`, and `ProxyWebDashboardTest` — PASS.

### Task 5: The page's script

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/DashboardScript.kt`
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/DashboardScriptTest.kt`

- [ ] **Step 1: Test** `a lost socket or a refused scrape sends an expired session to the login page`: the script
  fetches `<base>/session` from its `htmx:ws:close` handler and calls `location.reload()` on a 401, and the scrape
  `fetch` reloads on a 401. The base comes from the body's `hx-ws:connect` (strip `/events`).
- [ ] **Step 2–4:** run — FAIL; implement; run — PASS.

### Task 6: Docs and comments

**Files:** `website/prometheus-proxy/docs/web-dashboard.md` (Login: the page, sessions and their limits, Log out,
restart signs out, `curl -u` for scripts),
`configuration/proxy.md`, `README.md`, `llms.txt`, `SECURITY.md`, `CHANGELOG.md`, `RELEASE_NOTES.md` (4.6.1 entries say
"a login page", Basic Auth for scripts), `config/config.conf` and `ConfigVals` (comment), `EnvVars.kt`,
`ProxyOptions.kt` comments, `demos/basics/config/proxy.conf` comment, `docs/TESTING.md` (DashboardSessionsTest,
LoginHtmlTest, the rewritten login spec), `docs/DESIGN.md` (the login page's type and layout).

- [ ] **Step 1:** Edit; `make tsconfig`; `make build-site`; `./gradlew test --tests 'io.prometheus.misc.*'` — PASS.

### Task 7: Verify

- [ ] `./gradlew detekt lintKotlinMain lintKotlinTest && ./gradlew build -x test`
- [ ] `./gradlew koverVerify koverVerifyPerClass` (the full suite)
- [ ] Browser, on the basics demo from this checkout (`make -C demos/basics up-local`, login on): the page in light
  and dark, a wrong then a right login, the WebSocket live, Scrape now, Log out; `make down`.
