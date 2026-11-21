# Dashboard login form — design

Approved in conversation on 2026-11-20. Replaces the browser's native HTTP Basic Auth prompt, which the dashboard
login of 4.6.1 shows, with a login page.

## Intent

The browser's Basic Auth popup looks unfinished. The maintainer wants a login page like Grafana's: a page of the
dashboard's own, styled after Grafana's login, with a "Prometheus Proxy Dashboard" header.

## Goals

- A login page at `<base>/login`, styled after Grafana's login page: a full-page dark background with a soft gradient,
  a column centered across and 40% of the way down (the space left over split 2:3 above and below), a large
  "Prometheus Proxy Dashboard" heading, labels above full-width inputs, one full-width
  primary button, and a red alert above the form after a wrong login. It follows the system's light or dark mode,
  uses the dashboard's colors (its orange accent), and uses a sans-serif UI font, not the dashboard's monospace.
- A successful login sets a session cookie and returns to the page asked for. A **Log out** control in the dashboard's
  top bar ends the session.
- The settings don't change: `proxy.dashboard.password` / `DASHBOARD_PASSWORD` turns the login on, and
  `proxy.dashboard.username` / `DASHBOARD_USERNAME` is `admin` unless set.

## Non-goals

- More than one user, password hashes, "remember me", or password reset.
- Sessions that survive a proxy restart: sessions are kept in memory, so a restart signs everyone out.
- TLS for the dashboard (separate work).
- Using Grafana's or Prometheus's logo.

## Approaches considered

1. **Login page with a session cookie, Basic Auth kept for scripts (chosen).** Browsers get the page; `curl -u`,
   `demo.sh`, and `screenshots.sh` keep sending Basic credentials. As Grafana does for its API.
2. Login page only, no Basic Auth. Scripts would have to log in through the form and keep a cookie jar.
3. Keep the Basic Auth prompt. Rejected: it is what the maintainer finds unprofessional.

## Behavior

- **Authenticated** when a request carries a valid session cookie, or a correct Basic `Authorization` header.
- **Not authenticated**, with the login on:
  - a page navigation (a GET whose `Accept` includes `text/html`) gets a 303 to `<base>/login?next=<path and query>`;
  - anything else (the WebSocket handshake, the Scrape action, a `fetch`, `curl` without `-u`) gets a 401 with no
    `WWW-Authenticate` header, so no browser ever shows its own prompt;
  - only the login page's two routes are reachable.
- **`GET <base>/login`** renders the page (or redirects to the base when already logged in). `no-store` caching and
  `frame-ancestors 'none'`.
- **`POST <base>/login`** takes a form of `username`, `password`, and `next`:
  - refused with a 403 for a foreign `Origin`, as the page guard refuses one;
  - a body over 4 KB, or one with no `Content-Length`, is refused with a 413;
  - a wrong login logs the throttled WARN (address only), and renders the page again with a 401, the alert, and the
    username kept;
  - past 10 wrong logins from one address within a minute, or 100 in all, every login it covers is refused with a 429
    and `Retry-After: 60`, before its password is looked at, from the form or as Basic Auth (added after review; it
    replaced a one-second delay on each wrong login, which didn't slow a guesser sending logins in parallel);
  - a right one creates a session, sets the cookie, and redirects (303) to `next` when it is a path under the
    dashboard's base, else to the base, so it can't redirect off-site.
- **`POST <base>/logout`**: the same Origin check; removes the session, expires the cookie, and redirects to the login
  page.
- **`GET <base>/session`**: 204 when authenticated. The page's script calls it when its WebSocket closes, and reloads
  the page on a 401, so an expired session lands on the login page instead of a dashboard stuck reconnecting. The
  Scrape action reloads the page on a 401 the same way.
- **Sessions**: a random 256-bit token; the server keeps the SHA-256 of each, in memory. A session ends 24 hours after
  its last request or 7 days after login, whichever is first. At most 1,000 at once; the least recently used goes.
- **Cookie**: `prometheus_proxy_dashboard_<port>` (the port, since cookies are shared by every port on a host and an
  HA pair can run on one), `HttpOnly`, `SameSite=Lax` (so a link in from Grafana or a runbook keeps the session), `Path`
  the dashboard's base, `Max-Age` 7 days, and `Secure` when the login came from an `https://` origin, as behind a
  TLS-terminating reverse proxy.

## Left to the builder

The exact spacing, sizes, and gradient; the login page's copy beyond the heading, labels, button, and alert; where in
the top bar the Log out control sits, as long as it is outside every pushed region.
