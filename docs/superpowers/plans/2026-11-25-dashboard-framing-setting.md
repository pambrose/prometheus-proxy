# Dashboard Framing Setting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `framingEnabled` boolean on the proxy's and the agent's dashboards, on by default, that stops every
response from refusing framing, so a page can embed the dashboards; `false` restores 4.7.0's refusal.

**Architecture:** `DashboardServer` installs its `DashboardNoFraming` plugin only while `DashboardSettings.framingEnabled`
is false. Both services pass the value from their `ConfigVals` dashboard section, as they pass `allowedOrigins`.
`BaseOptions.assignDashboardOptions` logs a line while it's on, and the Settings view's Dashboard group shows it.

**Tech Stack:** Kotlin, Ktor server (CIO), Typesafe Config with tscfg-generated `ConfigVals`, Kotest `StringSpec`.

**Spec:** `docs/superpowers/specs/2026-11-25-dashboard-framing-setting-design.md`

## Global Constraints

- Keys: `proxy.dashboard.framingEnabled` and `agent.dashboard.framingEnabled`, both default `true`.
- On (the default): no response carries a `frame-ancestors` directive or `X-Frame-Options`. A brand mark's own
  `sandbox` policy and, with a login, `Cache-Control: no-store` are unchanged.
- Off: every response exactly as in 4.7.0, `frame-ancestors 'none'` and `X-Frame-Options: DENY` once each.
- Set in a config file or with `-D` only: no CLI flag, environment variable, builder method, Ktor plugin key, or Spring
  Boot starter property.
- No commits, pushes, or PRs until the user asks. Work on a branch, `dashboard-framing-setting`, in the main checkout
  (no worktree).
- Changelog entries concise; release notes user-facing only; new headings follow their file's existing case.

## Review Focus

1. Framing on with a login set: pages, fragments, and the 303 to the login page must still carry `no-store`
   (Task 1's `DashboardResponsesTest` case).
2. Framing on: a brand mark must keep its `sandbox` policy, which its own route sets, not the plugin (same case).
3. A service that drops the value: the harness specs check a real proxy and agent both ways (Task 1).
4. `-Dproxy.dashboard.framingEnabled=false` on the command line must turn it off, as a config file does (Task 2).
5. Framing on but the dashboard off: no startup line, since nothing is served (Task 2).

---

### Task 1: The Setting and the Server That Honors It

**Files:**
- Modify: `config/config.conf` (proxy dashboard section near line 59, agent's near line 211)
- Regenerate: `src/main/java/io/prometheus/common/ConfigVals.java` (`make tsconfig`)
- Modify: `src/main/kotlin/io/prometheus/common/dashboard/DashboardServer.kt` (`DashboardSettings` near line 114,
  its KDoc, `noFraming`'s comment near line 493, `buildServer` near line 527)
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardService.kt:164-177`
- Modify: `src/main/kotlin/io/prometheus/agent/dashboard/AgentDashboardService.kt:122-134`
- Modify: `src/test/kotlin/io/prometheus/common/TestPorts.kt:301-305`
- Test: `src/test/kotlin/io/prometheus/common/dashboard/DashboardResponsesTest.kt`,
  `src/test/kotlin/io/prometheus/common/dashboard/DashboardPushTest.kt` (its `DashboardSettings(...)`),
  `src/test/kotlin/io/prometheus/harness/ProxyWebDashboardTest.kt:322-323,737`,
  `src/test/kotlin/io/prometheus/harness/ProxyWebDashboardLoginTest.kt:95-100,213,270`,
  `src/test/kotlin/io/prometheus/harness/AgentWebDashboardTest.kt:266-268,397-414`

**Interfaces:**
- Produces: `ConfigVals.Proxy.Dashboard.framingEnabled: Boolean` and `ConfigVals.Agent.Dashboard.framingEnabled:
  Boolean` (generated); `DashboardSettings.framingEnabled: Boolean`, no default, after `allowedHosts`.

- [ ] **Step 1: Branch**

Run: `git switch -c dashboard-framing-setting`
Expected: `Switched to a new branch 'dashboard-framing-setting'`

- [ ] **Step 2: Write the failing unit test**

Add `const val DASHBOARD_RESPONSES_FRAMING_ENABLED_PORT = 11434` after `DASHBOARD_RESPONSES_HOST_LOG_PORT`. Give
`withServer` a `framingEnabled: Boolean = false` parameter passed to `DashboardSettings` (the existing cases test the
refusal), and add a case after the caching one:

```kotlin
"with framing enabled, no response should refuse framing, and a login should still keep them out of the cache" {
  withServer(TestPorts.DASHBOARD_RESPONSES_FRAMING_ENABLED_PORT, login = true, framingEnabled = true) {
    val signedIn: HttpRequestBuilder.() -> Unit = { basicAuth(USERNAME, PASSWORD) }
    val responses =
      mapOf(
        "login page" to get("/dashboard/login"),
        "page" to get("/dashboard", signedIn),
        "fragment" to
          get("/dashboard/$FRAGMENT") {
            signedIn()
            header(HX_REQUEST_HEADER, "true")
          },
        "action" to
          post("/dashboard/$ACTION") {
            signedIn()
            header(HX_REQUEST_HEADER, "true")
          },
        "page without a login" to get("/dashboard") { header(HttpHeaders.Accept, "text/html") },
        "unknown" to get("/dashboard/nothing-here", signedIn),
        "broken" to get("/dashboard/$BROKEN", signedIn),
      )
    val asset = get("/dashboard/$ASSET_DIR/htmx.min.js", signedIn)
    val icon = get("/dashboard/$ICON_DIR/${ICON.fileName}", signedIn)

    responses.mapValues { it.value.status.value } shouldBe
      mapOf(
        "login page" to 200,
        "page" to 200,
        "fragment" to 200,
        "action" to 200,
        "page without a login" to 303,
        "unknown" to 404,
        "broken" to 500,
      )
    (responses + mapOf("asset" to asset, "icon" to icon)).filterValues { !it.allowsFraming() }.keys.shouldBeEmpty()
    responses.filterValues { it.headers.getAll(HttpHeaders.CacheControl) != NO_STORE }.keys.shouldBeEmpty()
    icon.headers.getAll(CSP).orEmpty() shouldContain "sandbox"
  }
}
```

Beside `refusesFraming()` in the companion:

```kotlin
private fun HttpResponse.allowsFraming() =
  headers.getAll(CSP).orEmpty().none { it.contains("frame-ancestors") } && headers[X_FRAME_OPTIONS] == null
```

Update the class KDoc's first sentence: the headers every response carries "unless framing is enabled".

- [ ] **Step 3: Write the harness checks of the wiring, both ways**

- `ProxyWebDashboardTest` (default config): at lines 322-323 and 737, replace each `frame-ancestors 'none'` /
  `DENY` check with `headers["Content-Security-Policy"].orEmpty() shouldNotContain "frame-ancestors"` and
  `headers["X-Frame-Options"] shouldBe null`, and change each comment to say framing is allowed by default.
- `AgentWebDashboardTest`'s Scrape now case (lines 266-268): the same replacement and comment.
- `ProxyWebDashboardLoginTest`: add `add("-Dproxy.dashboard.framingEnabled=false")` to `startProxy`'s `args`
  `buildList` (line 96-100), and keep its refusal checks at 213 and 270.
- `AgentWebDashboardTest`'s login case (line 397-404): add `"-Dagent.dashboard.framingEnabled=false"` to its
  `extraArgs`, and keep its refusal check at 414.

- [ ] **Step 4: Run them to verify they fail**

Run: `./gradlew unitTest --tests 'io.prometheus.common.dashboard.DashboardResponsesTest'`
Expected: compilation fails on `framingEnabled`, the parameter `DashboardSettings` doesn't have yet.

- [ ] **Step 5: Add the config key and regenerate `ConfigVals`**

In both dashboard sections of `config/config.conf`, after `allowedHosts`:
`framingEnabled = true  // Lets any web page show the dashboard in a frame; false refuses framing on every response`
(align the comment with its neighbors'). Then run `make tsconfig`.
Expected: `git diff --stat` shows `config/config.conf` and `ConfigVals.java`, the latter with a `framingEnabled`
field, its read with a `true` default, in both `Proxy.Dashboard` and `Agent.Dashboard`, and nothing else.

- [ ] **Step 6: Implement `DashboardSettings.framingEnabled` and the conditional plugin**

Add `val framingEnabled: Boolean` (no default) after `allowedHosts`, with an `@param framingEnabled` line in the KDoc
(`<role>.dashboard.framingEnabled`: lets any page frame the dashboard; false refuses framing on every response). In
`buildServer`, `if (!settings.framingEnabled) install(noFraming)`. Extend `noFraming`'s comment: installed only while
`framingEnabled` is false; on, the default, any page can frame the dashboard. Pass
`framingEnabled = dashboardConfig.framingEnabled` after `allowedHosts` in both services' `DashboardSettings(...)`, and
`framingEnabled = false` in `DashboardPushTest`'s.

- [ ] **Step 7: Run the unit specs to verify they pass**

Run: `./gradlew unitTest --tests 'io.prometheus.common.dashboard.DashboardResponsesTest' --tests 'io.prometheus.common.dashboard.DashboardPushTest'`
Expected: PASS, five cases in `DashboardResponsesTest`, the four existing ones unchanged.

- [ ] **Step 8: Run the harness specs to verify they pass**

Run: `./gradlew harnessTest --tests 'io.prometheus.harness.ProxyWebDashboardTest' --tests 'io.prometheus.harness.ProxyWebDashboardLoginTest' --tests 'io.prometheus.harness.AgentWebDashboardTest'`
Expected: PASS.

---

### Task 2: The Startup Line

**Files:**
- Modify: `src/main/kotlin/io/prometheus/common/BaseOptions.kt:584-626` (`DashboardConfig`, `assignDashboardOptions`)
- Modify: `src/main/kotlin/io/prometheus/proxy/ProxyOptions.kt:300-313`
- Modify: `src/main/kotlin/io/prometheus/agent/AgentOptions.kt:393-406`
- Test: `src/test/kotlin/io/prometheus/proxy/ProxyDashboardOptionsTest.kt`, `src/test/kotlin/io/prometheus/agent/AgentDashboardOptionsTest.kt`

**Interfaces:**
- Consumes: `ConfigVals.{Proxy,Agent}.Dashboard.framingEnabled` (Task 1).
- Produces: `BaseOptions.DashboardConfig.framingEnabled: Boolean`, no default, after `allowedHosts`.

- [ ] **Step 1: Write the failing tests**

In `ProxyDashboardOptionsTest`, under a `// ==================== Framing ====================` banner:

```kotlin
"framing should be logged while it's on, the default, and only with the dashboard on" {
  fun framingLines(args: Array<String>) =
    captureLogs<ProxyOptions>(Level.INFO) { proxyOptions(args) }
      .map { it.formattedMessage }
      .filter { it.startsWith("dashboard framing") }

  framingLines(["--dashboard"]) shouldBe listOf(PROXY_FRAMING_LINE)
  framingLines(["--dashboard", "-Dproxy.dashboard.framingEnabled=false"]).shouldBeEmpty()
  framingLines([]).shouldBeEmpty()
}
```

with, in the companion, `PROXY_FRAMING_LINE = "dashboard framing: on; any web page can show the dashboard in a frame;
set proxy.dashboard.framingEnabled = false to refuse it"` (adapt `framingLines`' parameter to the type `proxyOptions`
takes). In `AgentDashboardOptionsTest`, the same three checks through `agentOptionsFromConfig`, with
`agent.dashboard { enabled = true }` (the line, naming `agent.dashboard.framingEnabled`),
`agent.dashboard { enabled = true, framingEnabled = false }` (none), and `agent.dashboard { enabled = false }` (none).

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.ProxyDashboardOptionsTest' --tests 'io.prometheus.agent.AgentDashboardOptionsTest'`
Expected: FAIL, the default cases finding no line.

- [ ] **Step 3: Implement**

Add `val framingEnabled: Boolean` to `DashboardConfig` after `allowedHosts`, and pass `framingEnabled = framingEnabled`
in `ProxyOptions` and `AgentOptions`. In `assignDashboardOptions`, after `logDashboardExposure(...)` (so only an
enabled dashboard logs it), when `dashboard.framingEnabled`:
`log.info { "dashboard framing: on; any web page can show the dashboard in a frame; set $role.dashboard.framingEnabled = false to refuse it" }`.

- [ ] **Step 4: Run them to verify they pass**

Run: the Step 2 command.
Expected: PASS.

---

### Task 3: The Settings View's Row

**Files:**
- Modify: `src/main/kotlin/io/prometheus/common/dashboard/CommonSettings.kt:38-46` (`DashboardValues`), `:174-178` (`dashboard`)
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ProxySettings.kt:241-250`
- Modify: `src/main/kotlin/io/prometheus/agent/dashboard/AgentSettings.kt:162-171`
- Test: `src/test/kotlin/io/prometheus/common/dashboard/CommonSettingsTest.kt:130-160`

**Interfaces:**
- Consumes: `ConfigVals.{Proxy,Agent}.Dashboard.framingEnabled` (Task 1).
- Produces: `DashboardValues.framingEnabled: Boolean`, no default, after `allowedHosts`; a Settings row named
  `Framing`, after `Allowed hosts`.

- [ ] **Step 1: Write the failing test**

In "the Dashboard group should show where it listens and its login, never the password", pass
`framingEnabled = false` and assert `value("Framing") shouldBe "refused"`. Add a case:

```kotlin
"the Dashboard group should say when any site can frame the dashboard" {
  CommonSettings
    .dashboard(
      options = proxyOptionsFromConfig("""proxy.dashboard { enabled = true }"""),
      values =
        DashboardValues(
          refreshIntervalSecs = 2,
          pathHistorySize = 20,
          activityLogSize = 500,
          scrapeNowEnabled = true,
          maxSessions = 50,
          allowedOrigins = emptyList(),
          allowedHosts = emptyList(),
          framingEnabled = true,
        ),
    ).value("Framing") shouldBe "allowed, by any site"
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew unitTest --tests 'io.prometheus.common.dashboard.CommonSettingsTest'`
Expected: compilation fails on `framingEnabled`.

- [ ] **Step 3: Implement**

Add `val framingEnabled: Boolean` to `DashboardValues`, the row
`Setting("Framing", if (framingEnabled) "allowed, by any site" else "refused")` after `Allowed hosts`, and
`framingEnabled = framingEnabled` in both `ProxySettings` and `AgentSettings`.

- [ ] **Step 4: Run it to verify it passes**

Run: the Step 2 command.
Expected: PASS.

---

### Task 4: Docs, Changelogs, and the Constraint Rule

**Files:**
- Modify: `website/prometheus-proxy/docs/web-dashboard.md:535-538` (Exposure's Framing bullet)
- Modify: `website/prometheus-proxy/docs/production.md:29-40` (the dashboard bullet)
- Modify: `website/prometheus-proxy/docs/configuration/proxy.md:181` (table) and `:196` (the paragraph after it)
- Modify: `website/prometheus-proxy/docs/configuration/agent.md:468` (table)
- Modify: `website/prometheus-proxy/docs/agent-dashboard.md:92-94`
- Modify: `CHANGELOG.md` (Unreleased), `RELEASE_NOTES.md` (Unreleased)
- Modify: `.claude/rules/dashboard-constraints.md` ("No dashboard response can be framed")
- Modify: `docs/TESTING.md:811-818` (`DashboardResponsesTest`'s entry, and the three harness specs' entries if they
  name the framing check)

- [ ] **Step 1: Write the docs**

- Framing bullet: framing is allowed by default (`proxy.dashboard.framingEnabled`, `agent.dashboard.framingEnabled`
  on an agent), so a portal or a page of your own can show the dashboard, and so can another site's page a viewer
  opens, which can lay content over it and steer clicks onto Scrape now or Log out, though it can't read it; with a
  login, a page on another site gets only the login page, since the session cookie is `SameSite=Lax`, so a login in a
  frame works only on the same site. `framingEnabled = false` refuses framing on every response
  (`frame-ancestors 'none'` and `X-Frame-Options: DENY`).
- `production.md`: one sentence in the dashboard bullet: set `proxy.dashboard.framingEnabled = false` unless a page
  embeds the dashboard.
- Tables: a row after `allowedHosts`, default `true`, "Lets any web page show the dashboard in a frame; false refuses
  framing", padded to the table's column widths. In `proxy.md`'s paragraph, one sentence: `framingEnabled = false`
  refuses framing; see Exposure.
- `agent-dashboard.md`: add `framingEnabled` to "The other settings" list.
- `CHANGELOG.md`: under Added, `proxy.dashboard.framingEnabled` and `agent.dashboard.framingEnabled`; under Changed,
  that a dashboard allows framing by default again, `false` restoring 4.7.0's refusal.
- `RELEASE_NOTES.md`: under Added, "**A dashboard can be framed again.**" and what the setting does; a
  `### Before you upgrade` section (as 4.7.0's is spelled) saying dashboards now allow framing by default, so set
  `framingEnabled = false` to keep 4.7.0's refusal.
- Constraint rule: retitle the bullet "A dashboard refuses framing only while its `framingEnabled` is false", say
  `DashboardNoFraming` is installed only then (the default is `true`, the user's choice), and replace "or add a setting
  that lifts it" with "or add another way to change it (a list of origins was considered and dropped)". Keep the
  narrow-width-check sentence and the `DashboardResponsesTest` line, adding that it pins both settings.
- `TESTING.md`: add the framing-enabled case to `DashboardResponsesTest`'s entry (no response with either header, a
  login's `no-store` and a brand mark's `sandbox` kept), and the harness specs' both-ways checks where their entries
  mention framing.

- [ ] **Step 2: Run the docs specs**

Run: `rm -rf build/test-results/unitTest && ./gradlew unitTest --rerun --tests 'io.prometheus.misc.*' --tests 'io.prometheus.common.TestPortsTest'`
Expected: PASS, in particular `WebsiteConfigKeysTest` (the rows' defaults against `config.conf`),
`ConfigFileKeysTest`, `CliReferenceTest`, and `TestingGuideTest`.

---

### Task 5: Verification

- [ ] **Step 1: Lint and build**

Run: `./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test`
Expected: BUILD SUCCESSFUL. (`DashboardSettings` reaches 12 constructor parameters, detekt's limit, not over it.)

- [ ] **Step 2: Full suite and coverage floors**

Run: `./gradlew check unitTest --rerun harnessTest --rerun && ./gradlew koverVerify koverVerifyPerClass -x unitTest -x harnessTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Report**

Summarize the changes and the check results to the user; leave everything uncommitted until they ask.
