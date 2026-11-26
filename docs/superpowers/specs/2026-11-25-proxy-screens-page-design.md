# The Proxy's Screens Page

## Intent

An operator of the proxy works across several browser screens: the proxy's dashboard, each agent's dashboard,
Prometheus, Grafana, and their own tools. The demos run the same set, eight screens in the basics demo, and today
hand the reader a list of URLs and a tab per screen. Both want the same thing: **one page that holds every screen**, so
nobody keeps track of URLs.

The proxy serves that page at `/screens`, on its dashboard's port. It's a production feature first; the demos are its
first users. It's possible because dashboards can be framed from 4.7.1 (`framingEnabled`, #576).

This supersedes the earlier design for a demo-only page (an nginx container in the basics demo), which the proxy's
page makes unnecessary.

## Goals

- **The page, `/screens`**, on the proxy's dashboard port (for example `http://localhost:8094/screens`), with:
  - **A tab per screen.** The proxy's own dashboard first, always, then the screens the operator lists, in their
    order. Each tab has the dashboard's 7px dot: green while the screen answers, red while it doesn't, checked every
    few seconds.
  - **Three layouts:**
    - One: the selected screen at full size.
    - Split: two screens side by side, each at its own narrow layout, not scaled.
    - All: every screen in a grid, each laid out at 1280×800 and scaled to fit its tile; clicking a tile's title opens
      it in One.
  - **Links into the page** that select a layout, a screen, and a view within it (see Links).
  - **One copy of the page.** A link opened while the page is already open hands itself to that page and closes the
    new tab (see Handing a Link Over).
  - **An "Open in a tab" link** on each pane, to the screen's own URL, for a screen that can't be framed or logged
    into from a frame.
  - **A pane for a screen that isn't answering** says so. Once the screen answers again, its pane reloads by itself.
- **The list of screens** is `proxy.dashboard.screens` in the config file, each a name, a URL, and optionally the path
  it opens on (see Config). Values can come from the environment with HOCON's `${?NAME}`; there's no environment
  variable or CLI flag for the list itself, as with `links` and `allowedOrigins`.
- **A link to the page** in the proxy dashboard's bar, shown only when screens are listed. With none listed,
  `/screens` answers 404 and the bar has no link.
- **Logins.** The page is behind the proxy dashboard's login. Each screen keeps its own login, which shows in its frame
  the first time. Signing in from the page returns to it, link and all.
- **Visibility and checks.** The Settings view gets a Screens group. Startup rejects a list the page can't use (see
  Config), naming the entry and the problem.
- **The basics demo uses it.** Its `proxy.conf` lists its seven other screens, and its tour links into the proxy's
  page, which replaces the list of URLs it prints today.
- **Docs.**
  - The dashboard page gets a Screens section, covering framing, the same-site rule for logins in frames, SSO in front
    of everything for a single login, and reverse proxies.
  - The proxy configuration page gets the setting.
  - The production guide, the changelogs, llms.txt, AGENTS.md, and the dashboard rules file get the feature.

## Non-Goals

- **Discovering screens.** The proxy doesn't find agents' dashboards on its own: agents don't report a dashboard
  address, one behind a firewall is often not reachable from the reader's browser, and an agent-supplied URL would let
  any agent put a page of its choosing inside the proxy's dashboard.
- **A login shared across screens.** The proxy issues nothing the agents or other tools trust. One login comes from SSO
  in front of everything, which the docs describe.
- **A screens page on the agent's dashboard.** The parts are shared (`common/dashboard`), so one can follow.
- **A link-only screen** for a tool that refuses framing: each pane's "Open in a tab" covers it.
- **Other demos.** Ktor, Spring Boot, plain Kotlin, network, nginx, and Kubernetes can follow the basics demo.
- **A screenshot of the page on the docs site**, until 4.7.1's images are on Docker Hub.

## Anti-Goals

- **Losing a screen's place.** Switching tabs or layouts, or following a link that names only a screen, never reloads
  a screen that's already loaded, so a dashboard keeps the agent or path the reader selected.
- **A screen URL that leaks or misleads.**
  - A URL with credentials in it would put them in the page's HTML for anyone who can read the dashboard.
  - A `javascript:` or other non-web URL in a frame is a hazard.
  - Startup refuses both.
- **A page that disagrees with the config about where a screen is.** The page renders from the resolved config, so
  `${?NAME}` values and the demo's port variables land in it as compose sets them.
- **Tour or docs links that point at a screen the page doesn't have.** A check catches them.

## Constraints

- **Framing.**
  - Every listed screen has to allow framing. Agent dashboards do from 4.7.1 unless `framingEnabled = false`.
    Prometheus always does. Grafana needs `allow_embedding` (`GF_SECURITY_ALLOW_EMBEDDING`).
  - The page can't tell a refused frame from a blank one, since it's another origin, so the docs say it.
  - **The proxy's own tab with `framingEnabled = false` (the user's decision).** #576 makes that send
    `frame-ancestors 'none'` and `X-Frame-Options: DENY`, which refuses even the proxy's own `/screens`. With
    screens listed, the proxy sends `frame-ancestors 'self'` and `X-Frame-Options: SAMEORIGIN` instead. Only the
    dashboard's own origin can frame it, so clickjacking from another site stays refused, and scanners accept
    `SAMEORIGIN`.
  - What `false` means changes with it, so these all say "refused, but for the screens page" while screens are
    listed:
    - the startup line;
    - the Settings view's Framing row;
    - the Exposure section;
    - SECURITY.md;
    - `.claude/rules/dashboard-constraints.md`;
    - `DashboardResponsesTest`, which pins both headers.
    The agent's dashboard is unchanged: it has no screens page.
- **Same site.** A login inside a frame needs the screen on the same site as the proxy, its session cookie being
  `SameSite=Lax` (the dashboards') or Grafana's default. "Open in a tab" covers the rest.
- **The session cookie's path** is `Path=/dashboard` (the base path) today, which a browser wouldn't send to
  `/screens`.
  - It's set at `/screens` too, with the same token, and sign-out expires both. At a root mount, `Path=/` covers both.
  - The agent's dashboard has no page outside its path, so its cookie stays at its path alone.
  - Not `Path=/`, as this spec first said (changed in review):
    - A browser would send the token with every request to the host, to Grafana, Prometheus, and any other app on it.
    - After a rollback to 4.7.0, a root cookie left from 4.7.1, dead after the restart, would sit beside 4.7.0's at
      `/dashboard`. Ktor reads the last of two cookies with one name, the root one, so every sign-in would loop back
      to the login page.
  - The cookie's name already carries the dashboard's port, so it can't collide with another dashboard's.
- **Reverse proxies** pass `/dashboard` through today. They now pass `/screens` too, and the "Behind a Reverse Proxy"
  section's nginx example shows it.
- **The page is down when the proxy is.** An open page keeps its frames, but can't be reloaded. In the demo, part 9
  stops the proxy, and the step text says the page stays open through it.
- **The design system,** as of #575: `docs/DESIGN.md`'s tokens, monospace type ramp, its 7px status dot, and both
  themes.
  - **The tabs reuse the dashboards' own, as #575 shipped them**: the `.nav` markup `DashboardPage` renders and the CSS
    `DashboardCss` gives it. A borderless link per screen, its marker before its name, a tinted ground on hover, and
    the current tab marked as the dashboards mark theirs, with `aria-current`, forced-colors styles, and Left and
    Right stepping through them. Reusing them, not restyling them, keeps the page matching whatever the dashboards'
    tabs become.
  - Each screen's status dot stands where a dashboard tab has its icon. The tabs are links (`href="#<id>"`) in a
    `<nav>`.
  - **The bar** has the Theme Button and, with a login, Log out, as every dashboard page does. (My call.)
  - **Contrast** holds on `--surface-2`, as `DashboardThemeTest` requires of the dashboards.
  - The page and its script follow `.claude/rules/dashboard-constraints.md`, where they apply.
- **Ports.** Nothing new is published: the page is on the dashboard's port.

## Approaches

- **A. A page the proxy renders, built like its dashboard pages (chosen).**
  - Rendered server-side with kotlinx.html from the resolved config.
  - Reuses `DashboardPage`'s head and theme script, `DashboardCss`'s tokens, the versioned asset route for a script
    of its own, and `DashboardServer`'s login and guards.
  - Tested like the other pages.
- **B. The prototype as a static resource, plus a `screens.json` route.** The fastest to write, but a second way of
  building pages, with copied styles, outside the HTML specs and design checks that guard the dashboards. Not chosen.
- **C. A fifth view inside the dashboard.** Every screen would sit under the dashboard's own bar, the proxy's own tab
  would nest the dashboard inside itself, and the dashboard's live updates would keep running under the frames. Not
  chosen.

## Design

### Config

```hocon
proxy.dashboard.screens = [
  { name = "agent-east", url = "http://agent-east.corp.example.com:8095/dashboard" }
  { name = "Prometheus", url = "http://prometheus.corp.example.com:9090", start = "/alerts" }
  { name = "Grafana",    url = ${?GRAFANA_URL}, start = "/d/overview?kiosk" }
]
```

- **`name`** (required): the tab's title. Its slug (lowercase, every run of other characters a `-`) is the screen's
  id in links, such as `agent-east` or `prometheus`.
- **`url`**: the screen's address, which links resolve against. Declared optional in the schema, empty by default,
  so an unset `${?NAME}` leaves it empty instead of failing in the config loader with a message that names no entry;
  startup then refuses the entry by name ("has no url; is the variable it reads set?"). A typo never silently drops a
  tab. (My call.)
- **`start`** (optional): the path the screen opens on, appended to `url`, such as `/alerts`. It's empty by default.
- **Startup checks.** The proxy refuses a list with any of these, naming the entry and the problem:
  - an empty name, or more than 64 characters;
  - two names with the same slug, or a slug the page reserves: `proxy` (its own tab) or `all` (the grid);
  - a URL that isn't absolute `http` or `https`, or that carries user info;
  - a `start` that doesn't begin with `/` or `?`;
  - more than 32 entries.
- **The proxy's own tab** is named for the proxy, as the dashboard's bar names it. Its id is `proxy`, its URL built
  from `routeBase`, so a dashboard mounted at the root doesn't produce `//activity`.
- **Settings view.** A Screens group lists each screen's name and URL, as the Links group does. The URL is shown
  through `Utils.sanitizeUrl`, as every logged URL is.

### Links

`<dashboard>/screens#<link>`, where `<link>` is one of:

- `all`: the All grid;
- `<id>`: One, on the screen;
- `<id>/<path>`: One, on the screen's `url` plus `/<path>`, its query string included. For example:
  - `agent-east/connection`
  - `proxy/activity?q=agent-rogue`
  - `prometheus/targets`
- `<id>[/<path>]+<id>[/<path>]`: Split, left then right, such as `proxy/paths+grafana`.

What a link does to a screen already loaded (the page can't see where the reader has gone within a frame, which is
another origin):

- **A link with a path loads it**, reloading the screen if it's loaded. That includes an empty path: `proxy/` reloads
  the proxy's tab on its default view.
- **A bare id only brings the screen up**, keeping the reader's place; it loads the screen's start only the first
  time.
- **The hash** shows the layout and what the page last loaded, not where the reader has gone since.

### Handing a Link Over

- **A page opened with a link** (a hash) asks over a `BroadcastChannel` whether another copy is open on the same
  origin.
- **If one answers**, that copy follows the link, and the new one closes itself. A script may close a tab with a
  single history entry, which a link opened from the terminal or the docs is. If the browser refuses, the new tab says
  the link went to the open page, and offers to stay here instead.
- **With no answer** within a short wait, the new page is the page.
- **A page opened without a hash** is simply another copy.
- **What the browser shows next.** A browser won't let a page bring another tab to the front. So the reader lands on
  the open page when it was the tab they last had, as in the tour: page, terminal, link.

### The Page

- **Rendering.** A full page of its own, not a view under the dashboard's bar. Its bar holds the proxy's name, a folder
  tab per screen, and the layout buttons. It uses the dashboard's head and theme script, and a link back to the
  dashboard is its proxy tab.
- **Frames.**
  - A screen's frame loads the first time it's shown and stays in the page from then on. Layouts show, hide, and size
    frames, and never move them in the page, which would reload them.
  - Each has a `title` for screen readers.
- **Dots.**
  - A request to each screen's `url` every few seconds, made so it doesn't read the response (`no-cors`). It succeeds
    whenever the screen answers, and fails when its port doesn't.
  - Paused while the page is hidden.
  - Each dot has a text equivalent ("up" or "not answering") for screen readers.
- **Split.** The selected screen goes on the left, and a second on the right. Each pane has a dropdown to change its
  screen, and shift-clicking a tab fills the right pane.
- **A login keeps the link.** The hash never reaches the server, so the login page carries it through.
  - The redirect to the login page keeps it, since browsers keep a fragment across a redirect that has none.
  - The login page's form adds it back to where the login returns.
  - `DashboardServer.safeNext` accepts `/screens` as a page to return to, beside the dashboard's own.

### The Basics Demo

- **`proxy.conf`** lists agent-east, agent-west-1, agent-west-2, agent-rogue, agent-east-copy, Prometheus (start
  `/alerts`), and Grafana (start `/d/prometheus-proxy-demo?kiosk`).
  - Each URL is built from its `DEMO_*_PORT` variable, which compose passes into the proxy's container with the
    defaults it publishes with.
  - The URLs use `localhost`, as the reader's browser reaches them, not the containers' names.
- **Grafana** gets `GF_SECURITY_ALLOW_EMBEDDING: "true"`.
- **`NEEDS_RELEASE_AFTER`** goes from 4.6.0 to 4.7.0, so until 4.7.1's images are on Docker Hub, `make up` builds the
  proxy and agent from the checkout.
- **`demo.sh`**
  - `SCREENS_URL` is the proxy dashboard's `/screens`.
  - `print_urls` prints it in place of the seven-line list, keeping the proxy's discovery URL and the docs link.
  - `cmd_open` opens it.
  - The tour's part 1 gains a last step that opens it and says each tab is one of the boxes in the diagram.
  - Every later step that opens a screen links into it:
    - part 2: the proxy dashboard;
    - part 3: Grafana. The discovery URL stays raw, since it's JSON, not a screen.
    - part 4: `proxy/paths+grafana`;
    - part 8: `proxy/activity?q=agent-rogue`;
    - part 9: agent-rogue, and agent-east's `connection` and `settings`. The step that stops the proxy says the page
      stays open through it.
    - part 10: agent-east and agent-east-copy;
    - part 11: no link. After the login restart, the open page's proxy tab shows the login page in its frame, and
      the step has the reader sign in there, which also shows a login in a frame working. A link would open a new
      tab that the login page, outside the page's script, can't hand over. (My call.)
  - Steps that act within a screen are unchanged.
- **The README and `website/prometheus-proxy/docs/basics-demo.md`**
  - Quick Start leads with the page.
  - The URL table stays as each screen's own address and the variable that moves it.
  - `make open` is described as opening the page, and the Makefile's help says the same.
  - The guided tour's links match `demo.sh`'s.

### Checks

- **HTML specs** for the page:
  - tabs and frames from the config, in order, names escaped;
  - the proxy's tab first;
  - the bar's link to the page only when screens are listed;
  - 404 with none listed.
- **An options spec** for each startup check.
- **A Settings spec** for the Screens group.
- **A harness spec** on a real proxy:
  - `/screens` behind the login;
  - the session cookie at the dashboard's path and at `/screens`;
  - signing in from `/screens` returning to it;
  - with `framingEnabled = false` and screens listed, `frame-ancestors 'self'`.
- **The page's script**, checked as `DashboardScriptTest` checks the dashboard's, for:
  - link parsing;
  - the "a path reloads, an id doesn't" rule;
  - the handoff.
- **A spec in `src/test/kotlin/io/prometheus/misc/`**: every page link in the basics demo's `demo.sh`, README, and docs
  page parses and names a screen its `proxy.conf` lists.
- **Existing guards**: `WebsiteConfigKeysTest`, `ConfigFileKeysTest`, `TestingGuideTest`, `DemoReleaseGateTest`, and
  the Kover floors.

## Left to the Builder

- The page's exact markup, the dot's check interval, the handoff's wait, and the All grid's tile width.
- How the page's script is tested, within what `DashboardScriptTest` does today.
- The exact wording of the tour's new step, the panes' text, and the docs, within the demo copy style.
- Whether the basics demo's architecture diagram shows the page.
