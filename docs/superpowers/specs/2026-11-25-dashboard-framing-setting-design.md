# Dashboard Framing Setting

## Intent

Let a page embed the proxy's and the agent's dashboards in frames. Since #563 (4.7.0), every dashboard response sends
`Content-Security-Policy: frame-ancestors 'none'` and `X-Frame-Options: DENY`, with no way to turn them off, so no page
can frame a dashboard. The first use is a page in the basics demo that shows every dashboard at once, which is a
follow-up to this change. The 4.7.0 release notes also told users that a portal framing a dashboard stops working.

The user chose to allow framing by default, so a dashboard works in a frame out of the box, and an operator who wants
4.7.0's refusal turns it back on with one setting.

## Goals

- One boolean per dashboard, `proxy.dashboard.framingEnabled` and `agent.dashboard.framingEnabled`, default `true`.
- On (the default): the dashboard sends neither framing header, so any page can frame it.
- Off: every response behaves exactly as in 4.7.0.

## Non-Goals

- A list of the origins allowed to frame a dashboard.
- A login that works in a frame on another site: the session cookie stays `SameSite=Lax`, so a framed dashboard with a
  login works only when the framing page is on the same site (the basics demo's case: every port on `localhost`).
- A Ktor plugin key, a Spring Boot starter property, or a builder method. An embedded agent can still set it with
  `setting("dashboard.framingEnabled", false)` (its keys are relative to `agent`), and the Spring Boot starter's and
  the Ktor plugin's `settings.dashboard.framingEnabled`.
- The basics demo page itself (a separate change).

## Anti-Goals

- A refusal that can't be restored: `false` must give back 4.7.0's headers exactly, once each, on every response.
- A change nobody hears of: the upgrade notes say the default changed and how to keep 4.7.0's behavior, and the
  startup log and the Settings view show the setting.

## Approaches

- **A list of origins, validated at startup.** Each entry a plain origin; the header names them, and
  `X-Frame-Options` is dropped while the list is set. Only the named sites can frame the dashboard. Not chosen: the
  user preferred a simpler on/off switch.
- **CSP source expressions passed through.** Allows wildcards, but `*` or `https:` silently lets any site frame it.
- **A boolean (chosen), on by default.** Simplest to set and explain. On means any site can frame the dashboard, which
  brings back, by default, the clickjacking #563 closed: a page the viewer opens can lay content over a framed
  dashboard and steer clicks onto Scrape now or Log out. The framing page still can't read the frame. With a login
  set, a page on another site gets only the login page in its frame, since the session cookie isn't sent there.

A boolean meaning "pages on the dashboard's own host, any port" was considered and dropped: the header would be built
from each request's `Host`, which a reverse proxy rewrites, and it adds the complexity the boolean was chosen to avoid.

## Design

- **Config:** `framingEnabled = true` in both dashboard sections of `config/config.conf`, then `make tsconfig` to
  regenerate `ConfigVals`. A scalar, so `-D` sets it too; no CLI flag or environment variable, as with
  `scrapeNowEnabled`.
- **Server:** `DashboardServer` installs its `noFraming` plugin only while framing is off. The brand mark's own
  `sandbox` policy and, with a login, `Cache-Control: no-store` stay either way. `DashboardSettings.framingEnabled`
  has no default, so each caller says which it wants.
- **Visibility:** an INFO line at startup while it's on, saying any page can frame the dashboard and naming the setting
  that refuses it. A "Framing" row in the Dashboard group of the Settings view, through `DashboardValues` and
  `CommonSettings.dashboard`.
- **Docs:** the Framing bullet of `web-dashboard.md`'s Exposure section says framing is allowed by default, what that
  lets another site's page do, how to refuse it, and that a login in a frame works only on the same site; the
  production guide recommends `framingEnabled = false` for a dashboard nothing embeds; rows in the dashboard tables of
  `configuration/proxy.md` and `configuration/agent.md`; `agent-dashboard.md`'s list of the other settings; both
  changelogs' Unreleased sections, the release notes with a Before You Upgrade note on the changed default.
- **Tests:** `DashboardResponsesTest` keeps its cases with framing off and adds one with it on: no `frame-ancestors`
  and no `X-Frame-Options` on each kind of response, and, with a login, `no-store` still sent. The harness specs check
  the wiring both ways: `ProxyWebDashboardTest` and `AgentWebDashboardTest`'s Scrape now case expect no framing headers
  by default, and `ProxyWebDashboardLoginTest` and `AgentWebDashboardTest`'s login case set `framingEnabled = false`
  and keep their refusal checks. The Settings view row and the startup line get tests where those are already tested.

## Left to the Builder

- The startup line's exact wording, and the Settings row's values.
