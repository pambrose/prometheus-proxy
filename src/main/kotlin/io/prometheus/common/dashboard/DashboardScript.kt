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

import io.prometheus.common.dashboard.DashboardPage.ANNOUNCER_ID
import io.prometheus.common.dashboard.DashboardPage.COLUMN_GUIDE_ID
import io.prometheus.common.dashboard.DashboardPage.COLUMN_GUIDE_TOGGLE_ID
import io.prometheus.common.dashboard.DashboardPage.COPY_ATTR
import io.prometheus.common.dashboard.DashboardPage.FILTER_PROBLEMS_ID
import io.prometheus.common.dashboard.DashboardPage.FILTER_QUERY_ID
import io.prometheus.common.dashboard.DashboardPage.LOGOUT_ID
import io.prometheus.common.dashboard.DashboardPage.MATCH_COUNT_ATTR
import io.prometheus.common.dashboard.DashboardPage.NAV_ID
import io.prometheus.common.dashboard.DashboardPage.PROBLEMS_ON
import io.prometheus.common.dashboard.DashboardPage.PROBLEMS_PARAM
import io.prometheus.common.dashboard.DashboardPage.PROXY_PATH_ATTR
import io.prometheus.common.dashboard.DashboardPage.QUERY_PARAM
import io.prometheus.common.dashboard.DashboardPage.SAY_ATTR
import io.prometheus.common.dashboard.DashboardPage.SCRAPE_ID_PREFIX
import io.prometheus.common.dashboard.DashboardPage.SCRAPE_PORT_ATTR
import io.prometheus.common.dashboard.DashboardPage.SCRAPE_RESULT_ID
import io.prometheus.common.dashboard.DashboardPage.SCRAPE_URL_ATTR
import io.prometheus.common.dashboard.DashboardPage.STATUS_ID
import io.prometheus.common.dashboard.DashboardPage.TITLE_ATTR
import io.prometheus.common.dashboard.DashboardPage.VIEW_ATTR
import io.prometheus.common.dashboard.DashboardPage.THEME_KEY
import io.prometheus.common.dashboard.DashboardPage.THEME_CHOICE_ATTR
import io.prometheus.common.dashboard.DashboardPage.THEME_PICKER_ID

/**
 * A dashboard page's one script, which each dashboard's renderer inlines. Apart from the renderers so each stays a size
 * a reader can hold; it names the page's ids and attributes through [DashboardPage]'s constants, so the two can't drift
 * apart. A handler for an element a page doesn't have does nothing there, so every dashboard runs the same script.
 *
 * @param subject the process the page reports on, `proxy` or `agent`, as the connection's announcements name it
 * @param views the dashboard's views: the one served at its base, also shown with a row selected, and the others, each
 *   at its own route
 * @param selectionSegment the route segment a selected row's id follows, as in `<base>/agents/<id>`
 */
internal class DashboardScript(
  subject: String,
  views: List<DashboardView>,
  selectionSegment: String,
) {
  private val defaultLayout = views.first { it.segment.isEmpty() }.name

  // The layouts with a route of their own, by its segment, for the page's script: {"paths":"PATH",...}.
  private val layoutsJs =
    views.filter { it.segment.isNotEmpty() }.joinToString(",", "{", "}") { "\"${it.segment}\":\"${it.name}\"" }

  /**
   * The page's script. It reads the selection back out of the URL that hx-push-url already set, and tells the server,
   * so the push loop knows whether this session wants a detail pane. It also runs the connection indicator and the
   * session check, the table search, the copy and scrape-now buttons, the column guide, the theme picker, the tabs'
   * arrow keys, the tab's title, and the scrape times' conversion to the viewer's zone, the only things on the page
   * that htmx does not model; everything else is htmx.
   */
  val js =
    """
    (function () {
      // htmx 4 runs the inline scripts of content it swaps in, and every back or forward navigation
      // refetches the page and swaps it into <body>, so this script runs again on each one. The body, its
      // listeners, and the socket all survive that swap, so a second run would only stack another set of
      // listeners, and one announce per navigation would become two, then three. A second run only marks the theme
      // picker the swap brought, served with nothing pressed (showTheme, below), which no push redraws.
      if (window.__${subject}UiInit) { window.__${subject}UiInit(); return; }
      window.__${subject}UiInit = showTheme;
      // The dashboard's base, from the socket's URL. The page's route is what follows it: matched there, rather than by
      // the URL's last segment, a base or a selected row's id named like a view can't be taken for that view.
      var base = (document.body.getAttribute('hx-ws:connect') || '').replace(/\/events$/, '');
      function route() {
        var path = window.location.pathname;
        return (path.indexOf(base) === 0 ? path.slice(base.length) : path).replace(/^\/+|\/+$/g, '');
      }
      function selectedId() {
        var m = route().match(/^$selectionSegment\/([^/]+)$/);
        return m ? m[1] : null;
      }
      // Each layout's name by its route; the default layout is the base itself, and a selected row's.
      var LAYOUTS = $layoutsJs;
      function layout() {
        var r = route();
        return Object.prototype.hasOwnProperty.call(LAYOUTS, r) ? LAYOUTS[r] : '$defaultLayout';
      }
      // The extension's connection, which outlives its socket: each reconnect replaces connection.socket,
      // so this keeps the connection and reads the socket when it sends.
      var conn = null;
      // The count of messages this page has sent. The server stamps every frame with the count of the
      // message it rendered the frame from, so a frame stamped lower describes a page this one no longer
      // shows. Without it a row click raced the push loop: a tick rendered from the previous selection could
      // land after the click's own swap and put the previous agent back until the next frame.
      var seq = 0;
      function announce() {
        var s = conn && conn.socket;
        // Only on an open socket. Nothing is lost by skipping: every open announces again from the URL.
        if (!s || s.readyState !== WebSocket.OPEN) return false;
        seq += 1;
        // The URL's query carries the view's filter, the Paths or Activity table's (see applyFilter), which the server
        // applies to the table each push renders, so a reload or a shared link shows the same rows.
        s.send(JSON.stringify({ select: selectedId(), layout: layout(), seq: seq, search: window.location.search }));
        return true;
      }
      // Drops a frame rendered from an older message. The stamp is on the frame's first tag, the status
      // region, which the server writes whole; the match is anchored there, so no agent-supplied value can
      // stand in for it.
      document.body.addEventListener('htmx:ws:before:message:incoming', function (evt) {
        var data = evt.detail.message.data;
        var m = typeof data === 'string' && /^<span[^>]*\s$VIEW_ATTR="(\d+)"/.exec(data);
        if ((m ? Number(m[1]) : 0) < seq) evt.preventDefault();
      });
      // The connection indicator. The extension reconnects on its own (with the backoff capped in the
      // htmx-config meta tag); this only reflects that effort so the page does not keep claiming "live"
      // while frozen. Toggled on <body> so it persists while no push can arrive to re-render the status bar,
      // and is cleared the instant the socket reopens -- the very next push then re-renders the bar in its
      // healthy state. Deliberately not set on 'htmx:ws:before:connection': that also fires for the first
      // connect on load, and reacting to it would flash the indicator red before the initial open. Nor on
      // 'htmx:ws:error', which also reports a message that failed to send or to swap while the socket stays
      // open; a failed connect always ends in 'htmx:ws:close' too.
      //
      // A hidden tab is the other exception. The extension closes the socket while the tab is hidden and
      // reopens it when the tab is shown again, which frees the session for the server. That is a pause, not
      // an outage, so a close while the tab is hidden changes nothing. An outage that begins while the tab is
      // hidden still shows: the reconnect that runs when the tab is shown fails while it is visible.
      //
      // The same transition is spoken into the live region. The beacon reports this visually with colour
      // and a change of rhythm, neither of which reaches a screen reader; without this the page goes
      // silently stale, which is the one failure this dashboard exists to make impossible to miss.
      // Scoped to the connection and the user's own acts (a copy, a scrape-now, a theme choice, a filter change) --
      // announcing every pushed counter would make the region unusable.
      var wasDown = false;
      function announceConn(up) {
        var region = document.getElementById('$ANNOUNCER_ID');
        if (!region) return;
        // Only transitions are news. Every failed retry closes the socket again, and rewriting the region on
        // each one would re-announce the outage every couple of seconds for as long as it lasts. Nothing to
        // say about the first successful connect either; only a recovery from a real outage.
        if (!up && !wasDown) region.textContent = 'Connection to the $subject lost. Reconnecting.';
        else if (up && wasDown) region.textContent = 'Connection to the $subject restored.';
      }
      function connected(up) {
        document.body.classList.toggle('ws-down', !up);
        announceConn(up);
        wasDown = !up;
      }
      document.body.addEventListener('htmx:ws:after:connection', function (evt) {
        conn = evt.detail.connection;
        connected(true);
        // At once: until this message arrives, the server pushes a new session only the status bar.
        announce();
      });
      // Whether this page may still use the dashboard, asked when its socket closes: a session that has ended, by
      // expiring, a Log out elsewhere, or a restart, gets a 401, and a reload, which the server answers with the
      // login page. Only on a page with a login, which has Log out, and one check at a time, since every failed
      // reconnect closes the socket again; with the server down, nothing happens.
      var checkingSession = false;
      function checkSession() {
        if (checkingSession || !document.getElementById('$LOGOUT_ID')) return;
        checkingSession = true;
        fetch(base + '/session', { headers: { '${DashboardServer.HX_REQUEST_HEADER}': 'true' } })
          .then(function (response) {
            if (response.status === 401) location.reload();
          }, function () {})
          .then(function () { checkingSession = false; });
      }
      document.body.addEventListener('htmx:ws:close', function () {
        if (!document.hidden) { connected(false); checkSession(); }
      });
      // htmx 4 dispatches its history events on document, which a listener on <body> never hears.
      document.addEventListener('htmx:after:history:push', announce);
      window.addEventListener('popstate', announce);
      // The copy buttons beside the path table's paths and target URLs. One listener on <body> rather than one per
      // button: the push loop replaces the table every frame, so a button's own listener would last one
      // frame. The result mark is kept here for the same reason, by button id, and put back on that id's
      // new button after each frame until it expires.
      var mark = null;
      var markTimer = 0;
      function applyMark() {
        var btn = mark && document.getElementById(mark.id);
        if (btn) btn.classList.add(mark.cls);
      }
      function showMark(id, cls) {
        var prev = mark && document.getElementById(mark.id);
        if (prev) prev.classList.remove(mark.cls);
        mark = { id: id, cls: cls };
        applyMark();
        clearTimeout(markTimer);
        markTimer = setTimeout(function () {
          var btn = document.getElementById(id);
          if (btn) btn.classList.remove(cls);
          mark = null;
        }, $COPY_MARK_MILLIS);
      }
      // The tab's title, which counts the dashboard's problems, from the status bar, which every push renders.
      function updateTitle() {
        var bar = document.getElementById('$STATUS_ID');
        var title = bar && bar.getAttribute('$TITLE_ATTR');
        if (title && document.title !== title) document.title = title;
      }
      // The search above the Paths or Activity table. A change applies as it is typed: it goes into the URL, and the
      // socket is told, so the next frame's table is filtered. Without this script the form still works, as a plain
      // GET.
      var countDue = 0;
      var filterTimer = 0;
      function applyFilter() {
        var q = document.getElementById('$FILTER_QUERY_ID');
        var problems = document.getElementById('$FILTER_PROBLEMS_ID');
        if (!q || !problems) return;
        var params = new URLSearchParams();
        if (q.value.trim()) params.set('$QUERY_PARAM', q.value.trim());
        if (problems.checked) params.set('$PROBLEMS_PARAM', '$PROBLEMS_ON');
        var search = params.toString();
        history.replaceState(history.state, '', window.location.pathname + (search ? '?' + search : ''));
        // The first frame rendered from this filter says how many rows it left.
        if (announce()) countDue = seq;
      }
      document.body.addEventListener('input', function (evt) {
        if (evt.target.id !== '$FILTER_QUERY_ID') return;
        clearTimeout(filterTimer);
        filterTimer = setTimeout(applyFilter, $FILTER_DEBOUNCE_MILLIS);
      });
      document.body.addEventListener('change', function (evt) {
        if (evt.target.id === '$FILTER_PROBLEMS_ID') applyFilter();
      });
      document.body.addEventListener('submit', function (evt) {
        if (!evt.target.matches || !evt.target.matches('form.filters')) return;
        evt.preventDefault();
        clearTimeout(filterTimer);
        applyFilter();
      });
      // Spoken once per change of filter, the user's own act, as a copy's result is; never per push. Once the frame
      // shown, whose stamp the status bar carries, is one rendered from the change.
      function sayCount() {
        var bar = document.getElementById('$STATUS_ID');
        // The Paths or the Activity table, whichever the page shows.
        var table = document.querySelector('[$MATCH_COUNT_ATTR]');
        if (!countDue || !bar || !table || Number(bar.getAttribute('$VIEW_ATTR')) < countDue) return;
        countDue = 0;
        say(table.getAttribute('$MATCH_COUNT_ATTR') + '.');
      }
      document.body.addEventListener('htmx:ws:after:message:incoming', function () {
        applyMark();
        updateTitle();
        sayCount();
      });
      // navigator.clipboard exists only in a secure context, and the dashboard is usually plain http on a
      // LAN address, where it is undefined. There the copy goes through a hidden textarea and execCommand,
      // deprecated but still the only way to write the clipboard outside a secure context.
      function writeClipboard(text) {
        if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
        var focused = document.activeElement;
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.top = '0';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        area.setSelectionRange(0, text.length);
        var ok = false;
        try { ok = document.execCommand('copy'); } catch (e) {}
        document.body.removeChild(area);
        // Selecting the textarea took focus; hand it back so a keyboard user stays on the button.
        if (focused && focused.focus) focused.focus({ preventScroll: true });
        return ok ? Promise.resolve() : Promise.reject();
      }
      // The result goes to the live region too: the mark is an icon and a colour, which a screen reader
      // does not report. A click is the user's own act, so this speaks once per click, never per push.
      function say(text) {
        var region = document.getElementById('$ANNOUNCER_ID');
        if (region) region.textContent = text;
      }
      function copyFrom(btn) {
        // The id, not the element: a push can replace the button before the clipboard write settles.
        var id = btn.id;
        var path = btn.getAttribute('$PROXY_PATH_ATTR');
        var what = path ? 'Proxy URL' : 'Target URL';
        writeClipboard(path ? proxyUrl(path) : btn.getAttribute('$COPY_ATTR')).then(function () {
          showMark(id, 'copied');
          say(what + ' copied.');
        }, function () {
          showMark(id, 'copy-failed');
          say('Could not copy the ' + what.toLowerCase() + '.');
        });
      }
      // Scrape now: a path's Scrape button has the server scrape it at once, and the result shows in the panel above the
      // Paths view's table or list. A request of the script's own, not hx-post: the push loop replaces the button's
      // region every frame, which would drop a request its button made. The panel is outside every pushed region, so a
      // push never clears a result. The header is the one htmx sends, which the route requires, so no page on another
      // site can make the server scrape. Closing the panel hands focus back to the button that filled it.
      var scrapedFrom = null;
      function scrapeNow(btn) {
        var panel = document.getElementById('$SCRAPE_RESULT_ID');
        if (!panel) return;
        // The path is in the button's id, after the prefix.
        var path = btn.id.slice('$SCRAPE_ID_PREFIX'.length);
        scrapedFrom = btn.id;
        panel.hidden = false;
        panel.textContent = 'Scraping /' + path + '…';
        var url = panel.getAttribute('$SCRAPE_URL_ATTR') + '?path=' + encodeURIComponent(path);
        fetch(url, { method: 'POST', headers: { '${DashboardServer.HX_REQUEST_HEADER}': 'true' } })
          .then(function (response) {
            // A session that has ended: the reload brings the login page.
            if (response.status === 401) { location.reload(); return null; }
            return response.text();
          })
          .then(function (html) {
            if (html === null) return;
            panel.innerHTML = html;
            var heading = panel.querySelector('[$SAY_ATTR]');
            // A refusal the guard answers in plain text has no heading; it is said as the panel shows it.
            say(heading ? heading.getAttribute('$SAY_ATTR') : panel.textContent);
          }, function () {
            panel.textContent = 'No answer from the $subject for the scrape of /' + path + '.';
            say(panel.textContent);
          });
      }
      function closeScrapeResult(panel) {
        panel.hidden = true;
        panel.textContent = '';
        var btn = scrapedFrom && document.getElementById(scrapedFrom);
        if (btn) btn.focus();
      }
      // The column guide under the search, which its button opens and closes. Outside every pushed region, so it stays
      // open as the table updates; the page renders it closed, so it starts closed on each load. Closing it from inside,
      // with its close button or Escape, hands focus back to its button.
      function guideOpen() {
        var guide = document.getElementById('$COLUMN_GUIDE_ID');
        return !!guide && !guide.hidden;
      }
      function showGuide(open) {
        var guide = document.getElementById('$COLUMN_GUIDE_ID');
        var toggle = document.getElementById('$COLUMN_GUIDE_TOGGLE_ID');
        if (!guide || !toggle) return;
        guide.hidden = !open;
        toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      }
      function closeGuide() {
        showGuide(false);
        var toggle = document.getElementById('$COLUMN_GUIDE_TOGGLE_ID');
        if (toggle) toggle.focus();
      }
      // The theme: light, dark, or auto, which follows the system's, from the bar's picker, and kept in this browser.
      // The head applies a stored one before the page draws (DashboardPage.THEME_SCRIPT), so the theme in force is the
      // page's own, which the stylesheet marks on its button. This keeps each button's pressed state and tooltip in
      // step with it, auto's naming the system's theme as that changes, and on a press applies the theme, stores it,
      // and says it. Auto is stored as nothing.
      var systemDark = window.matchMedia('(prefers-color-scheme: dark)');
      function currentTheme() { return document.documentElement.getAttribute('data-theme') || 'auto'; }
      function themeTitle(t, current) {
        var title = 'Theme: ' + t;
        if (t === 'auto') title += ', following your system, now ' + (systemDark.matches ? 'dark' : 'light');
        return t === current ? title + ' (current)' : title;
      }
      function showTheme() {
        var current = currentTheme();
        document.querySelectorAll('#$THEME_PICKER_ID [$THEME_CHOICE_ATTR]').forEach(function (btn) {
          var t = btn.getAttribute('$THEME_CHOICE_ATTR');
          btn.setAttribute('aria-pressed', String(t === current));
          btn.title = themeTitle(t, current);
        });
      }
      function chooseTheme(t) {
        if (t === currentTheme()) return;
        if (t === 'auto') document.documentElement.removeAttribute('data-theme');
        else document.documentElement.setAttribute('data-theme', t);
        try {
          if (t === 'auto') localStorage.removeItem('$THEME_KEY'); else localStorage.setItem('$THEME_KEY', t);
        } catch (e) {}
        showTheme();
        say('Theme: ' + t + '.');
      }
      showTheme();
      systemDark.addEventListener('change', showTheme);
      // One listener on <body> for every button in the table, the result panel, the column guide, and the theme picker:
      // the push loop replaces the table every frame, and a back or forward the picker, so a button's own listener
      // would last one frame.
      document.body.addEventListener('click', function (evt) {
        if (!evt.target.closest) return;
        var copy = evt.target.closest('button.copy');
        var scrape = evt.target.closest('button.scrape-now');
        var panel = evt.target.closest('#$SCRAPE_RESULT_ID');
        var guide = evt.target.closest('#$COLUMN_GUIDE_ID');
        var theme = evt.target.closest('#$THEME_PICKER_ID [$THEME_CHOICE_ATTR]');
        if (copy) copyFrom(copy);
        else if (scrape) scrapeNow(scrape);
        else if (evt.target.closest('#$COLUMN_GUIDE_TOGGLE_ID')) showGuide(!guideOpen());
        else if (theme) chooseTheme(theme.getAttribute('$THEME_CHOICE_ATTR'));
        else if (panel && evt.target.closest('button.close')) closeScrapeResult(panel);
        else if (guide && evt.target.closest('button.close')) closeGuide();
      });
      // The tabs. Left and Right open the view before or after the current one, wrapping at the ends, from anywhere on
      // the page: not only from a focused tab, since a click on a tab loads a page whose focus is on <body>, and Safari
      // never focuses a link on a click or a Tab. Not while typing, so the arrows still move the caret in the search.
      // A bare key only: Alt+Left is the browser's Back, and a held key would start a page load per repeat. On <body>,
      // as the other listeners are: a back or forward swaps a new nav in.
      var TAB_FOCUS_KEY = '$subject-dashboard-tab-focus';
      function typingIn(el) {
        if (el.isContentEditable) return true;
        var tag = el.tagName;
        return tag === 'TEXTAREA' || tag === 'SELECT' || (tag === 'INPUT' && el.type !== 'checkbox');
      }
      document.body.addEventListener('keydown', function (evt) {
        // Escape closes an open column guide from anywhere but a field being typed in, where it still clears the search.
        // Not only from inside the guide or its button: Safari doesn't focus a button it clicks, so after opening the
        // guide with the mouse, focus is on <body>.
        if (evt.key === 'Escape') {
          if (guideOpen() && !typingIn(evt.target)) closeGuide();
          return;
        }
        if (evt.key !== 'ArrowLeft' && evt.key !== 'ArrowRight') return;
        if (evt.altKey || evt.ctrlKey || evt.metaKey || evt.shiftKey || evt.repeat || !evt.target.closest) return;
        if (typingIn(evt.target)) return;
        // From the focused tab when one is, else from the current view's.
        var focusedTab = evt.target.closest('#$NAV_ID a');
        var from = focusedTab || document.querySelector('#$NAV_ID a[aria-current="page"]');
        if (!from) return;
        var tabs = Array.prototype.slice.call(document.querySelectorAll('#$NAV_ID a'));
        var step = evt.key === 'ArrowRight' ? 1 : -1;
        var next = tabs[(tabs.indexOf(from) + step + tabs.length) % tabs.length];
        evt.preventDefault();
        // A keyboard user on a tab keeps their place: the page that opens focuses its own tab (below). Storage can throw
        // (a sandboxed frame, storage turned off); the view still opens, without the focus.
        if (focusedTab) {
          try { sessionStorage.setItem(TAB_FOCUS_KEY, next.pathname); } catch (e) {}
        }
        window.location.assign(next.href);
      });
      // A page an arrow opened focuses its own tab. Only the page the arrow named: a load that failed leaves the key
      // behind, and the next page this browser tab opens may be another view, which no arrow asked to focus.
      (function () {
        var arrowedTo = null;
        try {
          arrowedTo = sessionStorage.getItem(TAB_FOCUS_KEY);
          sessionStorage.removeItem(TAB_FOCUS_KEY);
        } catch (e) {}
        var current = arrowedTo === window.location.pathname &&
          document.querySelector('#$NAV_ID a[aria-current="page"]');
        if (current) current.focus({ focusVisible: true });
      })();
      // A path's proxy URL, the one to curl to check it through the proxy: the host this page was opened at, which the
      // proxy can't know, and the port Prometheus scrapes, which is plain HTTP. An IPv6 host keeps its brackets.
      function proxyUrl(path) {
        return 'http://' + window.location.hostname + ':' + document.body.getAttribute('$SCRAPE_PORT_ATTR') + path;
      }
      // Scrape times arrive as <time datetime="...Z"> showing the server's zone, and are shown in the viewer's, the
      // zone Grafana uses, so a failure here and the spike it caused there read the same clock. Every frame and row
      // click is converted in its fragment before htmx swaps it in, so no frame shows the server's zone first. The
      // title gives the date and the zone's name, which the column has no room for.
      function pad(n) {
        return (n < 10 ? '0' : '') + n;
      }
      function localTimes(root) {
        root.querySelectorAll('time[datetime]').forEach(function (el) {
          var at = new Date(el.getAttribute('datetime'));
          if (isNaN(at.getTime())) return;
          el.textContent = pad(at.getHours()) + ':' + pad(at.getMinutes()) + ':' + pad(at.getSeconds());
          el.title = at.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'long' });
        });
      }
      localTimes(document);
      document.addEventListener('htmx:before:swap', function (evt) {
        (evt.detail.tasks || []).forEach(function (task) {
          if (task.fragment) localTimes(task.fragment);
        });
      });
    })();
    """.trimIndent()

  private companion object {
    // How long the table search waits after a keystroke before applying the filter: long enough not to push a new table
    // per letter, short enough to read as typing into a live view.
    const val FILTER_DEBOUNCE_MILLIS = 250

    // How long a copy button shows its result: long enough to notice after the click, brief enough that it reads
    // as the click's answer rather than as a state of the row.
    const val COPY_MARK_MILLIS = 1500
  }
}
