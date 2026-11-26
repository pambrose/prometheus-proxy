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

import io.prometheus.common.dashboard.DashboardPage.THEME_BUTTON_ID
import io.prometheus.common.dashboard.ThemeScript
import io.prometheus.proxy.dashboard.Screen.Companion.ALL_ID
import io.prometheus.proxy.dashboard.Screen.Companion.PROXY_ID
import io.prometheus.proxy.dashboard.ScreensHtml.HANDOFF_ID
import io.prometheus.proxy.dashboard.ScreensHtml.MENU_ID
import io.prometheus.proxy.dashboard.ScreensHtml.MORE_ID
import io.prometheus.proxy.dashboard.ScreensHtml.NAV_ID
import io.prometheus.proxy.dashboard.ScreensHtml.SEARCH_ID
import io.prometheus.proxy.dashboard.ScreensHtml.STAGE_ID

/**
 * The screens page's script ([ScreensHtml]), inlined into the page. It reads the page's markup, a tab and a pane per
 * screen, and runs what the page does in the browser:
 *
 * - **Layouts.** One, Split, and All show, hide, and size panes, and never move one in the page, which would reload
 *   its frame. A frame loads the first time its pane shows, in All once its tile nears the grid's view, and keeps its
 *   place from then on.
 * - **Links.** The hash says what to show: `all`, `<id>`, `<id>/<path>`, or two of those joined by `+`. A link with a
 *   path loads it, reloading a screen already loaded; a bare id only brings a screen up, keeping the reader's place in
 *   it. The hash is kept in step with what the page shows, with no history entry.
 * - **The handoff.** A page opened with a link asks over a `BroadcastChannel` whether a copy is open; if one answers,
 *   it follows the link, and this copy closes itself, or says it couldn't.
 * - **The row.** The tabs fit one row: those that don't fit are hidden, never moved, keeping the selected screen's,
 *   and the More button says how many the row leaves out and how many of those aren't answering. Its menu lists every
 *   screen, with a search, and its entries work as tabs do.
 * - **Dots.** Each screen is probed every few seconds, while the page is visible, with a request that can't read the
 *   answer and succeeds whenever the screen answers. A screen that comes back reloads its frame.
 * - **The theme button**, as on the dashboards ([ThemeScript]), and Left and Right through the tabs.
 */
internal object ScreensScript {
  /** How long a page opened with a link waits for a copy already open to take it. */
  const val HANDOFF_WAIT_MILLIS = 300

  /** How often each screen's dot is checked, while the page is visible. */
  const val PROBE_INTERVAL_MILLIS = 5_000

  /** How long a check waits for a screen to answer: less than the interval, so the next check finds it free. */
  const val PROBE_TIMEOUT_MILLIS = 4_000

  /** The width and height a screen lays out at in the All grid, a desktop's, before it is scaled to its tile. */
  const val FRAME_WIDTH = 1280
  const val FRAME_HEIGHT = 800

  /** The More menu's widest, in pixels; a narrower window gives it its width less 32px. */
  const val MENU_WIDTH = 320

  /** How near the All grid's view a tile comes before its frame loads: half the grid's height, above and below. */
  const val ALL_LOAD_MARGIN = "50% 0px"

  /** The script. */
  val JS =
    """
    (function () {
      var HANDOFF_WAIT_MILLIS = $HANDOFF_WAIT_MILLIS;
      var PROBE_INTERVAL_MILLIS = $PROBE_INTERVAL_MILLIS;
      var PROBE_TIMEOUT_MILLIS = $PROBE_TIMEOUT_MILLIS;
      var stage = document.getElementById('$STAGE_ID');
      var nav = document.getElementById('$NAV_ID');
      var handoff = document.getElementById('$HANDOFF_ID');
      var more = document.getElementById('$MORE_ID');
      var menu = document.getElementById('$MENU_ID');
      var search = document.getElementById('$SEARCH_ID');
      // Each screen's pane, tab, and More menu entry by its id, and the ids in the page's order, the proxy's first.
      // Objects without a prototype, so an id such as "constructor" names only a screen.
      var ids = [];
      var panes = Object.create(null);
      Array.prototype.forEach.call(stage.querySelectorAll('section.pane'), function (pane) {
        var id = pane.getAttribute('data-screen');
        ids.push(id);
        panes[id] = pane;
      });
      var tabs = byScreen(nav, 'a.screen');
      var menuItems = byScreen(menu, 'a.entry');
      // What the page shows: the layout, the screen alone or on the left, and the one on the right in Split; and the
      // path each screen last loaded from a link, or null once it loaded its start, which the hash shows.
      var layout = 'one';
      var first = '$PROXY_ID';
      var second = null;
      var loaded = Object.create(null);
      // Whether this copy is the page, which follows links other copies hand it; false while it waits to hand its own.
      var isThePage = false;
      var handedOver = false;
      // The row's fit (fitTabs): each tab's width with the gap after it, measured once, and the More button's at its
      // widest label.
      var tabWidth = Object.create(null);
      var moreWidth = 0;
      var moreCount = more.querySelector('.count');
      var moreDown = more.querySelector('.crit');

      // The theme button and the live region's say, which every page with the bar shares (see ThemeScript).
      ${ThemeScript.indented("      ")}

      // The elements under root that selector matches, by their screen's id.
      function byScreen(root, selector) {
        var found = Object.create(null);
        Array.prototype.forEach.call(root.querySelectorAll(selector), function (el) {
          found[el.getAttribute('data-screen')] = el;
        });
        return found;
      }
      function frameOf(id) {
        return panes[id].querySelector('iframe');
      }
      // Where a screen starts, its url and its start, which the page renders joined (ScreensHtml).
      function startUrl(frame) {
        return frame.getAttribute('data-start');
      }
      // A link's path, after the screen's url with one slash between; empty is the url itself, and a query alone
      // follows it with none, since the dashboards don't take /dashboard/ for /dashboard. Its leading slashes and
      // backslashes go, so at a root mount, where the proxy's url is /, a path can't join to //host, which a browser
      // reads as another site.
      function pathUrl(frame, path) {
        var url = frame.getAttribute('data-url');
        var rest = path.replace(/^[\/\\]+/, '');
        if (rest === '') return url;
        if (rest.charAt(0) === '?') return url + rest;
        return url.replace(/\/+$/, '') + '/' + rest;
      }
      // The one place a frame's src is set: by the load rule (load), and for a screen that answers again (mark). The
      // pane's Open in a tab follows it.
      function setSrc(frame, url) {
        frame.src = url;
        var pane = frame.closest('.pane');
        pane.removeAttribute('data-reload');
        pane.querySelector('a.open').setAttribute('href', url);
      }
      // Loads a link's path into a screen's frame, or its start when the path is null, and remembers which.
      function load(id, path) {
        var frame = frameOf(id);
        setSrc(frame, path === null ? startUrl(frame) : pathUrl(frame, path));
        loaded[id] = path;
      }
      // Shows a screen's frame: its start the first time, and again if the screen came back while it was hidden;
      // otherwise as it was, so a screen keeps the reader's place.
      function show(id) {
        var frame = frameOf(id);
        if (!frame.getAttribute('src')) load(id, null);
        else if (panes[id].hasAttribute('data-reload')) setSrc(frame, frame.getAttribute('src'));
      }
      function otherThan(id) {
        return second && second !== id ? second : ids.filter(function (o) { return o !== id; })[0];
      }
      function linkOf(id) {
        var path = loaded[id];
        return path === null || path === undefined ? id : id + '/' + path;
      }
      // Shows the layout and its screens, marks the tabs and the layout buttons, and puts it in the hash; nothing in a
      // copy that isn't the page, which shows its handoff notice until Stay here makes it the page.
      function render() {
        if (!isThePage) return;
        stage.className = 'stage ' + layout;
        var shown = layout === 'all' ? ids : layout === 'split' ? [first, second] : [first];
        ids.forEach(function (id) {
          var at = shown.indexOf(id);
          var current = layout !== 'all' && id === first;
          var isSecond = layout === 'split' && id === second;
          panes[id].classList.toggle('shown', at >= 0);
          panes[id].style.gridColumn = layout === 'all' || at < 0 ? '' : String(at + 1);
          markLink(tabs[id], current, isSecond, 'page');
          markLink(menuItems[id], current, isSecond, 'true');
          if (at >= 0 && layout !== 'all') show(id);
        });
        Array.prototype.forEach.call(document.querySelectorAll('.layouts button'), function (b) {
          b.setAttribute('aria-pressed', String(b.getAttribute('data-layout') === layout));
        });
        fitTabs();
        var hash = layout === 'all' ? '$ALL_ID' : shown.map(linkOf).join('+');
        history.replaceState(null, '', '#' + hash);
      }
      // Marks a tab or a menu entry current, and as the right pane's in Split; the tab is the page's current link
      // (aria-current page), its entry only marked current, so the page has one.
      function markLink(link, current, isSecond, currentValue) {
        link.classList.toggle('on', current);
        link.classList.toggle('second', isSecond);
        if (current) link.setAttribute('aria-current', currentValue); else link.removeAttribute('aria-current');
      }
      // The More button's label: how many screens the row leaves out, and how many of those aren't answering.
      function labelMore(out, down) {
        moreCount.textContent = String(out);
        moreDown.textContent = down ? ' · ' + down + ' down' : '';
      }
      // Measures each tab once, shown and in bold, as the current one is, so selecting a tab never pushes its neighbour
      // out; and the More button at its widest label, every screen out and every one down, so a dot changing colour
      // never moves a tab. Every change first, then every reading, so the page lays out once.
      function measureTabs() {
        var wasOn = ids.filter(function (id) { return tabs[id].classList.contains('on'); });
        nav.classList.remove('tight');
        ids.forEach(function (id) {
          tabs[id].classList.remove('out');
          tabs[id].classList.add('on');
        });
        more.hidden = false;
        labelMore(ids.length, ids.length);
        var gap = parseFloat(getComputedStyle(nav).columnGap) || 0;
        ids.forEach(function (id) { tabWidth[id] = tabs[id].getBoundingClientRect().width + gap; });
        moreWidth = more.getBoundingClientRect().width;
        ids.forEach(function (id) { tabs[id].classList.toggle('on', wasOn.indexOf(id) >= 0); });
        more.hidden = true;
      }
      // Fits the tabs to one row, in the list's order, hiding (out) those from the first that doesn't fit beside the
      // More button on. The selected screen's tab, and in Split the right pane's, stays, its room kept first; a hidden
      // tab takes none, so a kept one follows the others with nothing moved. In All no tab is current, and none is
      // kept. Where even the kept tabs and More are wider than the row, the row is tight: the tabs shrink, their names
      // cut, rather than spill over the layout buttons.
      function fitTabs() {
        var room = nav.clientWidth;
        var kept = layout === 'split' ? [first, second] : layout === 'one' ? [first] : [];
        var widthOf = function (sum, id) { return sum + tabWidth[id]; };
        var row = ids;
        var tight = false;
        if (ids.reduce(widthOf, 0) > room) {
          var used = kept.reduce(widthOf, moreWidth);
          tight = used > room;
          var full = false;
          row = ids.filter(function (id) {
            if (kept.indexOf(id) >= 0) return true;
            full = full || used + tabWidth[id] > room;
            if (!full) used += tabWidth[id];
            return !full;
          });
        }
        ids.forEach(function (id) { tabs[id].classList.toggle('out', row.indexOf(id) < 0); });
        nav.classList.toggle('tight', tight);
        updateMore();
      }
      // The More button: how many screens the row leaves out, and how many of those aren't answering, in words too;
      // hidden while every tab fits. It never refits the row, which keeps room for its widest label.
      function updateMore() {
        var out = ids.filter(function (id) { return tabs[id].classList.contains('out'); });
        var down = out.filter(function (id) { return tabs[id].classList.contains('down'); }).length;
        var label = 'More screens: ' + out.length + ' hidden';
        labelMore(out.length, down);
        more.setAttribute('aria-label', down ? label + ', ' + down + ' not answering' : label);
        more.hidden = out.length === 0;
      }
      // A link's pane, or the proxy's tab, with no path, for an id the page doesn't have.
      function paneOf(pane) {
        var id = pane.id;
        if (!panes[id]) return { id: '$PROXY_ID', path: null };
        return pane;
      }
      // A link's panes, split at the first + that a screen's id follows, alone or before its path: a + elsewhere is
      // part of a pane's query, where the dashboards' search writes a space as +.
      function splitPanes(text) {
        var at = text.indexOf('+');
        while (at >= 0) {
          var rest = text.slice(at + 1);
          if (panes[rest.split('/')[0]]) return [text.slice(0, at), rest];
          at = text.indexOf('+', at + 1);
        }
        return [text];
      }
      // A link into the page: all, <id>, <id>/<path>, or two of those joined by +, the left pane's first.
      function parseLink(text) {
        if (text === '$ALL_ID') return { layout: 'all', panes: [] };
        var parts = splitPanes(text).map(function (part) {
          var slash = part.indexOf('/');
          return paneOf({ id: slash < 0 ? part : part.slice(0, slash), path: slash < 0 ? null : part.slice(slash + 1) });
        });
        return { layout: parts.length > 1 ? 'split' : 'one', panes: parts };
      }
      // Follows a link: its layout and its screens, loading each pane's path when it has one, and bringing up a bare
      // id's screen as it was.
      function follow(link) {
        layout = link.layout;
        if (link.panes.length) first = link.panes[0].id;
        if (layout === 'split') second = link.panes[1].id === first ? otherThan(first) : link.panes[1].id;
        link.panes.forEach(function (pane) {
          if (pane.path !== null) load(pane.id, pane.path);
        });
        render();
      }
      // A tab's click: its screen alone, or on the left in Split, trading places with the right pane's if it was there.
      function select(id) {
        if (!isThePage) return;
        if (layout === 'all') layout = 'one';
        if (layout === 'split' && id === second) second = first;
        first = id;
        render();
      }
      // A tab's shift-click: its screen in the right pane, in Split.
      function fillRight(id) {
        if (!isThePage) return;
        if (id === first) return;
        second = id;
        layout = 'split';
        render();
      }
      function setLayout(next) {
        if (!isThePage) return;
        layout = next;
        if (layout === 'split' && (!second || second === first)) second = otherThan(first);
        render();
      }
      function typingIn(el) {
        var tag = el.tagName;
        return el.isContentEditable || tag === 'TEXTAREA' || tag === 'SELECT' || tag === 'INPUT';
      }
      // Closes the More menu if it's open; nothing where the browser has no popovers, whose selector would throw.
      function closeMenu() {
        if (menu.hidePopover && menu.matches(':popover-open')) menu.hidePopover();
      }
      // Sizes the menu, $MENU_WIDTH px or the window's width less 32px, and places it under the More button, their
      // right edges aligned, at least 8px in from the window's on both sides: at a phone's width More can sit nearer
      // the left edge than the menu is wide.
      function placeMenu() {
        var rect = more.getBoundingClientRect();
        var width = document.documentElement.clientWidth;
        var menuWidth = Math.min($MENU_WIDTH, width - 32);
        menu.style.width = menuWidth + 'px';
        menu.style.top = Math.round(rect.bottom + 4) + 'px';
        menu.style.right = Math.round(Math.max(8, Math.min(width - rect.right, width - menuWidth - 8))) + 'px';
      }
      // Narrows the menu to the screens whose name holds the search's text, in any case; empty, it shows them all.
      function filterMenu() {
        var query = search.value.trim().toLowerCase();
        ids.forEach(function (id) {
          var entry = menuItems[id];
          entry.parentNode.hidden = entry.querySelector('.name').textContent.toLowerCase().indexOf(query) < 0;
        });
      }
      // The entries the search leaves, in the menu's order.
      function visibleEntries() {
        return ids.map(function (id) { return menuItems[id]; }).filter(function (entry) {
          return !entry.parentNode.hidden;
        });
      }
      // A tab's or a menu entry's click: its screen alone, or on the right in Split with shift, closing the menu. A
      // modified click opens the link elsewhere, as a link does, where the page there hands it back here.
      function onScreenClick(evt) {
        var link = evt.target.closest && evt.target.closest('a.screen, a.entry');
        if (!link || evt.metaKey || evt.ctrlKey || evt.altKey || evt.button !== 0) return;
        evt.preventDefault();
        var id = link.getAttribute('data-screen');
        if (evt.shiftKey) fillRight(id); else select(id);
        closeMenu();
      }

      nav.addEventListener('click', onScreenClick);
      menu.addEventListener('click', onScreenClick);
      // A click into a screen goes to its frame's document, which the menu's light dismiss never sees, and takes focus
      // out of this window, so the menu closes then.
      window.addEventListener('blur', closeMenu);
      search.addEventListener('input', filterMenu);
      // Opening the menu places it and starts a fresh search, before it shows, so it never draws a frame out of place.
      menu.addEventListener('beforetoggle', function (evt) {
        if (evt.newState !== 'open') return;
        placeMenu();
        search.value = '';
        filterMenu();
      });
      // Its search takes focus as the menu shows (autofocus), and again here, where a browser didn't; closing the menu
      // from inside it returns focus to the button.
      menu.addEventListener('toggle', function (evt) {
        if (evt.newState === 'open') search.focus();
        else if (menu.contains(document.activeElement) || document.activeElement === document.body) more.focus();
      });
      // In the menu, Down and Up move between the search and the entries it leaves, and Enter in the search opens the
      // first of them; with none left, neither does anything. Enter's default is prevented, or its keypress would reach
      // the More button, which the closing menu hands focus to, and open the menu again.
      menu.addEventListener('keydown', function (evt) {
        if (!isThePage) return;
        var list = visibleEntries();
        if (evt.key === 'Enter' && evt.target === search) {
          evt.preventDefault();
          if (list.length) list[0].click();
          return;
        }
        if (evt.key !== 'ArrowDown' && evt.key !== 'ArrowUp') return;
        evt.preventDefault();
        var at = list.indexOf(evt.target);
        var next = evt.key === 'ArrowDown' ? list[at + 1] : at <= 0 ? search : list[at - 1];
        if (next) next.focus();
      });
      document.body.addEventListener('click', function (evt) {
        if (!evt.target.closest) return;
        var layoutButton = evt.target.closest('.layouts button');
        var bar = evt.target.closest('.pane-bar');
        if (evt.target.closest('#$THEME_BUTTON_ID')) cycleTheme();
        else if (layoutButton) setLayout(layoutButton.getAttribute('data-layout'));
        else if (evt.target.closest('#$HANDOFF_ID button')) becomeThePage();
        else if (bar && layout === 'all' && !evt.target.closest('a, select'))
          select(bar.closest('.pane').getAttribute('data-screen'));
      });
      // A pane's screen picker, in Split: the pane's slot takes the screen picked, which trades places with the other
      // pane's if it was there. The picker goes back to naming its own pane's screen, which is now hidden.
      stage.addEventListener('change', function (evt) {
        if (!isThePage) return;
        var picker = evt.target.closest && evt.target.closest('.pane-bar select');
        if (!picker) return;
        var own = picker.closest('.pane').getAttribute('data-screen');
        var picked = picker.value;
        picker.value = own;
        if (own === first) {
          select(picked);
        } else {
          if (picked === first) first = second;
          second = picked;
          render();
        }
      });
      // Left and Right step through the tabs, from a focused tab or the current one, wrapping, as the dashboards' do.
      // Not while typing, nor with a modifier, nor a held key, which would load every screen on the way, not from
      // inside a frame, whose keys are its own, nor from the More menu, which would change the screen behind it.
      document.body.addEventListener('keydown', function (evt) {
        if (!isThePage) return;
        if (evt.key !== 'ArrowLeft' && evt.key !== 'ArrowRight') return;
        if (evt.altKey || evt.ctrlKey || evt.metaKey || evt.shiftKey || evt.repeat || !evt.target.closest) return;
        if (evt.target.closest('#$MENU_ID')) return;
        if (typingIn(evt.target)) return;
        var focusedTab = evt.target.closest('#$NAV_ID a');
        var from = focusedTab ? focusedTab.getAttribute('data-screen') : first;
        var next = ids[(ids.indexOf(from) + (evt.key === 'ArrowRight' ? 1 : -1) + ids.length) % ids.length];
        evt.preventDefault();
        select(next);
        if (focusedTab) tabs[next].focus();
      });
      window.addEventListener('hashchange', function () {
        if (isThePage) follow(parseLink(location.hash.slice(1)));
      });

      // All: each screen lays out at $FRAME_WIDTH wide and is drawn at its tile's width, from the size the observer
      // measured, so no tile forces a layout, and only on a change, since the scale sets the view's height, which the
      // observer sees again.
      if (window.ResizeObserver) {
        var sizer = new ResizeObserver(function (entries) {
          entries.forEach(function (entry) {
            var view = entry.target;
            var s = String(Math.min(1, entry.contentRect.width / $FRAME_WIDTH));
            if (view.style.getPropertyValue('--s') !== s) view.style.setProperty('--s', s);
          });
        });
        ids.forEach(function (id) {
          sizer.observe(panes[id].querySelector('.view'));
        });
        // The row refits when the tabs' width changes, as the window's does, not on a change in their height.
        var navWidth = -1;
        var tabSizer = new ResizeObserver(function (entries) {
          var width = entries[0].contentRect.width;
          if (width === navWidth) return;
          navWidth = width;
          closeMenu();
          fitTabs();
        });
        tabSizer.observe(nav);
      }

      // All: a tile's frame loads once the tile comes near the grid's view, by the load rule (show), so the grid
      // starts the screens a reader can see, not every one at once. A frame never unloads.
      var loader = new IntersectionObserver(function (entries) {
        if (!isThePage || layout !== 'all') return;
        entries.forEach(function (entry) {
          if (entry.isIntersecting) show(entry.target.getAttribute('data-screen'));
        });
      }, { root: stage, rootMargin: '$ALL_LOAD_MARGIN' });
      ids.forEach(function (id) {
        loader.observe(panes[id]);
      });

      // Marks a screen up or down from its probe, on its tab and its menu entry, in words for a screen reader, and on
      // its pane, when that changes. A screen that answers again reloads its frame, at once if it's shown, else when it
      // next shows (show).
      function mark(id, up) {
        var tab = tabs[id];
        if (tab.classList.contains(up ? 'up' : 'down')) return;
        var pane = panes[id];
        var frame = frameOf(id);
        var wasDown = tab.classList.contains('down');
        var text = up ? 'up' : 'not answering';
        [tab, menuItems[id]].forEach(function (link) {
          link.classList.toggle('up', up);
          link.classList.toggle('down', !up);
          link.querySelector('.state').textContent = text;
        });
        pane.classList.toggle('is-down', !up);
        updateMore();
        if (up && wasDown && frame.getAttribute('src')) {
          pane.setAttribute('data-reload', '');
          if (pane.classList.contains('shown')) show(id);
        }
      }
      // A request the page can't read, which succeeds whenever the screen answers and fails when its port doesn't. A
      // HEAD, so no screen renders a page for it, and with no cookie, so a page left open doesn't keep its login from
      // going idle, as the dashboards' pages don't. One at a time for each screen, so a slow one doesn't pile them up,
      // and given up before the next round, so a host that drops the request is marked as not answering then, not when
      // the browser gives up on the connection, and the next round finds it free. Freed before it's marked, so a mark
      // that throws can't leave it checked forever.
      var probing = Object.create(null);
      function probe(id) {
        if (probing[id]) return;
        probing[id] = true;
        var frame = frameOf(id);
        var signal = window.AbortSignal && AbortSignal.timeout ? AbortSignal.timeout(PROBE_TIMEOUT_MILLIS) : undefined;
        fetch(frame.getAttribute('data-probe'), {
          method: 'HEAD', mode: 'no-cors', cache: 'no-store', credentials: 'omit', signal: signal
        })
          .then(function () { probing[id] = false; mark(id, true); }, function () { probing[id] = false; mark(id, false); });
      }
      function probeAll() {
        if (document.hidden) return;
        ids.forEach(probe);
      }
      // Only the page checks its screens, from when it becomes the page: a copy that hands its link over shows none.
      var probingStarted = false;
      function startProbing() {
        if (probingStarted) return;
        probingStarted = true;
        document.addEventListener('visibilitychange', probeAll);
        setInterval(probeAll, PROBE_INTERVAL_MILLIS);
        probeAll();
      }

      // The tabs' widths, before the first render fits them, and again once the fonts are in, which can change them.
      measureTabs();
      if (document.fonts) document.fonts.ready.then(function () { measureTabs(); fitTabs(); });

      // The handoff. A page opened with a link asks whether a copy is open on this origin; one that is follows the link
      // and answers, and this one closes, or, where the browser won't let it, says where the link went and offers to
      // stay. With no answer within the wait, or no link, this copy is the page.
      var link = location.hash.slice(1);
      var ticket = String(Math.random()).slice(2);
      var channel = null;
      try {
        channel = new BroadcastChannel('prometheus-proxy-screens');
      } catch (e) {}
      function becomeThePage() {
        isThePage = true;
        handoff.hidden = true;
        follow(parseLink(link || '$PROXY_ID'));
        startProbing();
      }
      if (channel) {
        channel.onmessage = function (evt) {
          var data = evt.data || {};
          if (data.type === 'link' && isThePage) {
            follow(parseLink(String(data.link)));
            channel.postMessage({ type: 'taken', id: data.id });
          } else if (data.type === 'taken' && data.id === ticket && !isThePage && !handedOver) {
            handedOver = true;
            window.close();
            setTimeout(function () { handoff.hidden = false; }, 100);
          }
        };
      }
      if (link && channel) {
        channel.postMessage({ type: 'link', link: link, id: ticket });
        setTimeout(function () { if (!handedOver) becomeThePage(); }, HANDOFF_WAIT_MILLIS);
      } else {
        becomeThePage();
      }
    })();
    """.trimIndent()
}
