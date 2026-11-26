# The Screens Page with Many Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the proxy's `/screens` page usable at its 32-screen cap: one row of tabs with a More menu and a search,
long names cut, and an All grid that keeps its tiles whole and loads them as they scroll into view.

**Architecture:** All changes are on the screens page's three sources: `ScreensHtml` (markup), `ScreensCss` (styles),
and `ScreensScript` (the inline script). The script fits the tabs to one row by hiding the ones that don't fit (class
`out`), never moving an element, and a native popover holds the menu. All's rows size to their content, and an
`IntersectionObserver` applies the existing load rule as tiles near the view.

**Tech Stack:** Kotlin, kotlinx.html, inline ES5-style JavaScript and CSS in Kotlin strings, Kotest `StringSpec`.

**Spec:** `docs/superpowers/specs/2026-11-26-screens-page-many-screens-design.md`

## Global Constraints

- Work in the worktree `.claude/worktrees/screens-many-screens`, branch `screens-many-screens`. **No commits or pushes
  until the user says so** (their global instructions): each task ends with its tests green and its changes left in
  the working tree.
- Extend `ScreensHtmlTest` and `ScreensScriptTest` (Kotest `StringSpec`, `init {}`); add no spec file, which
  `TestingGuideTest` would require `docs/TESTING.md` to name.
- The screens rules in `.claude/rules/dashboard-constraints.md` hold: no frame moves (the script contains none of
  `appendChild`, `insertBefore`, `.append(`, `.prepend(`, `replaceChild`, `.before(`, `.after(`, nor `style.order`),
  and the script sets `.src =` once, in `setSrc`.
- Colors come from the dashboards' tokens only; no bare `prefers-color-scheme` block. Every new dot and current mark
  gets a `forced-colors` rule as the tabs' have; under `pointer: coarse` the More button, the search, and the entries
  are at least 46px tall.
- Kotlin source lines, script and CSS lines included, stay within 120 characters (detekt `MaxLineLength`). The
  script's new functions sit at the IIFE's top level, two spaces in, so `ScreensScriptTest.body(name)` finds them.
- Values from the spec: tab names `max-width:24ch`; the menu `width:min(320px, calc(100vw - 32px))` and
  `max-height:min(60vh, 480px)`; All `grid-auto-rows:max-content`; the intersection `rootMargin: '50% 0px'`; the
  button `More <n>` then ` · <d> down`; its accessible name `More screens: <n> hidden` then `, <d> not answering`; the
  search's label `Find a screen` and placeholder `Find a screen…`.
- Before calling the work done: `./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test`.

## Review Focus

1. A link that opens the page on a screen the menu holds (`#agent-x`, `#proxy+agent-x`) shows that tab in the row,
   current: Task 4's kept-tabs test.
2. A name with `<`, `>`, `&`, or `"` stays escaped in the tab's `title` and its menu entry: Tasks 2 and 3 extend the
   escaping test.
3. A search that matches nothing leaves an empty list where Enter and Down do nothing, with no script error: Task 5's
   keys test.
4. A window resized while the menu is open closes the menu, rather than leaving it where the button was: Task 5.
5. Screens going down or up while hidden change the More button's count and never move a tab: Task 4's test that
   `mark` and `updateMore` never refit.

## Browser Check Setup

Tasks 1, 4, 5, and 6 end with a look in Chrome. `$S` is
`/private/tmp/claude-501/-Users-pambrose-git-prometheus-proxy/c90cb0aa-701b-434e-b950-3890031982be/scratchpad`.

- `$S/screens32/proxy.conf` lists 32 screens (30 `agent-<region>-<role>` names, Prometheus, Grafana): ports 18080,
  18050, dashboard 18094 on `127.0.0.1`, most screens at `http://localhost:18094/dashboard` (answering), every
  seventh and Prometheus and Grafana at dead ports 18998/18999. If it's gone, regenerate it the same way, and write
  `$S/screens8/proxy.conf` alike with 7 short names, for the short-list check.
- Build and run: `./gradlew proxyJar -q && cp build/libs/prometheus-proxy.jar $S/screens32/`, then
  `java -jar $S/screens32/prometheus-proxy.jar --config $S/screens32/proxy.conf` in the background; open
  `http://localhost:18094/screens` in a new Chrome tab. Stop with `pkill -f screens32/prometheus-proxy.jar`.

---

### Task 1: All Keeps Each Tile Whole, and Loads Frames as They Come into View

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensCss.kt:75` (`.stage.all`)
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensScript.kt` (`render`, a new observer after the
  `ResizeObserver` block at lines 279-293, the KDoc's Layouts bullet)
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/ScreensScriptTest.kt` (its All tests, near line 208)

**Interfaces:**
- Produces: `ScreensScript.ALL_LOAD_MARGIN: String = "50% 0px"` (a `const val`).

- [ ] **Step 1: Write the failing tests**

```kotlin
"the All grid should size its rows to their tiles, and scroll" {
  ScreensCss.CSS shouldContain "grid-auto-rows:max-content;"
}

"the All layout should load a tile's frame only once it comes near the stage's view" {
  body("render") shouldContain "if (at >= 0 && layout !== 'all') show(id);"
  js shouldContain "root: stage, rootMargin: '${ScreensScript.ALL_LOAD_MARGIN}'"
  js shouldContain "if (!isThePage || layout !== 'all') return;"
  js shouldContain "if (entry.isIntersecting) show(entry.target.getAttribute('data-screen'));"
  js shouldContain "loader.observe(panes[id]);"
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.ScreensScriptTest'`
Expected: FAIL, compilation: unresolved reference `ALL_LOAD_MARGIN`.

- [ ] **Step 3: Implement**

Add `grid-auto-rows:max-content;` to the `.stage.all` rule, with a comment saying why (a pane's `overflow:hidden`
makes it a scroll container, whose automatic minimum is 0, so auto rows shrank to share the stage's height). In
`render`, call `show(id)` only outside All. After the `ResizeObserver` block, create
`var loader = new IntersectionObserver(callback, { root: stage, rootMargin: '$ALL_LOAD_MARGIN' })`, whose callback
returns unless `isThePage` and `layout === 'all'`, and shows each intersecting pane's screen; observe every pane. The
KDoc's Layouts bullet adds that All loads a frame when its tile nears the view.

- [ ] **Step 4: Run the screens specs**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.Screen*'`
Expected: PASS.

- [ ] **Step 5: Check in the browser** (Browser Check Setup, 32 screens, 1440×900 window)

Select All: each tile shows its scaled screen under its title bar, and the stage scrolls. In the console,
`document.querySelectorAll('iframe[src]').length` is well under 33 on opening All, and grows as you scroll.

---

### Task 2: Long Tab Names Cut with an Ellipsis

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensHtml.kt:108-123` (`tabs`)
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensCss.kt` (after `.nav a.screen { white-space:nowrap; }`)
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/ScreensHtmlTest.kt`

**Interfaces:**
- Produces: each tab renders as
  `<a href="#<id>" class="screen[ on]" data-screen="<id>"[ aria-current="page"] title="<name>"><span class="name"><name></span> <span class="sr-only state">checking</span></a>`.

- [ ] **Step 1: Write the failing test, and update the tests the markup changes**

```kotlin
"each tab should carry its full name in a tooltip, and its name in a span the bar can cut" {
  page().apply {
    attributeValues("a", "screen", "title") shouldBe [PROXY_NAME, "agent-east", "Grafana"]
    this shouldContain """<span class="name">agent-east</span> <span class="sr-only state">checking</span></a>"""
  }
  ScreensCss.CSS shouldContain
    ".nav a.screen .name { min-width:0; max-width:24ch; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }"
}
```

Update to the new markup: "the proxy's tab should be named for the proxy, and be the current one" (the tab's opening
tag now ends in `title="$PROXY_NAME">`, and its text is `<span class="name">$PROXY_NAME</span> <span class="sr-only state">`);
"each tab's dot should have a text equivalent" (the agent-east tab's tag); and the escaping test, where the tab's text
becomes `<span class="name">&lt;b&gt;Ops &quot;A&quot;&lt;/b&gt;</span> <span class="sr-only state">` and
`title="&lt;b&gt;Ops &quot;A&quot;&lt;/b&gt;"` now appears twice (the tab's and the frame's).

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.ScreensHtmlTest'`
Expected: FAIL on the new test and the three updated ones.

- [ ] **Step 3: Implement**

In `tabs`, set `attributes["title"] = screen.name` after the existing attributes, and render the name as
`span("name") { +screen.name }`, then `+" "`, then the state span. Add the CSS rule above.

- [ ] **Step 4: Run the screens specs**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.Screen*'`
Expected: PASS.

---

### Task 3: The More Button and the Menu's Markup and Styles

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensHtml.kt` (constants; `tabs` gains the button and the
  menu after the tabs, inside the `<nav>`)
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensCss.kt`
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/ScreensHtmlTest.kt`

**Interfaces:**
- Produces: `ScreensHtml.MORE_ID = "screens-more"`, `ScreensHtml.MENU_ID = "screens-menu"`,
  `ScreensHtml.SEARCH_ID = "screens-search"`; the button's children `span.count` and `span.down-count`; per screen,
  in order, `<li><a href="#<id>" class="entry[ on]" data-screen="<id>"[ aria-current="true"]><span class="name"><name></span> <span class="sr-only state">checking</span></a></li>`
  in `ul.entries`. The proxy's entry is current (`on`, `aria-current="true"`; `page` stays the tab's alone).

- [ ] **Step 1: Write the failing tests, and update the tests the markup changes**

```kotlin
"the tabs should end in a More button, hidden until a tab doesn't fit, that opens the menu" {
  page().apply {
    this shouldContain """<button type="button" class="more" id="${ScreensHtml.MORE_ID}" """ +
      """popovertarget="${ScreensHtml.MENU_ID}" aria-label="More screens" hidden="hidden">""" +
      """More <span class="count"></span><span class="down-count"></span></button>"""
    indexOf("id=\"${ScreensHtml.MENU_ID}\"") shouldBeGreaterThan indexOf("id=\"${ScreensHtml.MORE_ID}\"")
    indexOf("</nav>") shouldBeGreaterThan indexOf("id=\"${ScreensHtml.MENU_ID}\"")
  }
}

"the menu should be a popover with a labelled search and an entry per screen, in order" {
  page().apply {
    this shouldContain """<div class="menu" id="${ScreensHtml.MENU_ID}" popover="auto">"""
    this shouldContain """<input type="search" id="${ScreensHtml.SEARCH_ID}" aria-label="Find a screen" """ +
      """placeholder="Find a screen…" autocomplete="off">"""
    attributeValues("a", "entry", "data-screen") shouldBe ["proxy", "agent-east", "grafana"]
    attributeValues("a", "entry", "href") shouldBe ["#proxy", "#agent-east", "#grafana"]
    this shouldContain """<a href="#proxy" class="entry on" data-screen="proxy" aria-current="true">""" +
      """<span class="name">$PROXY_NAME</span> <span class="sr-only state">checking</span></a>"""
    Regex("""aria-current="page"""").findAll(this).count() shouldBe 1
  }
}

"the More button and the menu should use the dashboards' tokens, dots, and touch sizes" {
  ScreensCss.CSS.apply {
    this shouldContain ".more[hidden] { display:none; }"
    this shouldContain ".more .down-count { color:var(--crit); }"
    this shouldContain "width:min(320px, calc(100vw - 32px)); max-height:min(60vh, 480px);"
    this shouldContain ".menu a.entry.up::before { background:var(--ok); }"
    this shouldContain ".menu a.entry.down::before { background:var(--crit); }"
    this shouldContain ".menu a.entry.up::before { background:LinkText; }"
    this shouldContain ".more, .menu input, .menu a.entry { min-height:46px; }"
  }
}
```

Update: "each tab's dot should have a text equivalent" counts 6 `checking` spans (3 tabs, 3 entries); the escaping
test expects the escaped name's `<span class="name">…</span> <span class="sr-only state">` twice (tab and entry);
and "the pane's down notice should be styled only inside its view" also lets a `.down` follow `a.entry`.

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.ScreensHtmlTest'`
Expected: FAIL, compilation: unresolved references `MORE_ID`, `MENU_ID`, `SEARCH_ID`.

- [ ] **Step 3: Implement**

In `tabs`, after the tab links: the button (attributes in the order the test gives; `+"More "`, then the two empty
spans), then `div("menu")` with `id`, `popover="auto"`, the `input(type = InputType.search)`, and `ul("entries")`
with an `li` per screen. Styles: the button sits on the tabs' line (`height:26px; margin-bottom:7px`, the tabs' own
values), quiet as the bar's end is, `white-space:nowrap`; the menu is `position:fixed; inset:auto; margin:0` (the
script places it), the width and height above, `overflow:auto`, on `--surface` with a `--line` border; an entry has
the tabs' 7px dot as `::before`, `--ink` and weight 600 when `on`, a line in `--ink-3` when `second`, and
`overflow-wrap:anywhere` on its name. Add the `forced-colors` rules for the entries' dots and the coarse-pointer
line above to the existing blocks. The page looks as before: the button is hidden and the popover closed.

- [ ] **Step 4: Run the screens specs**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.Screen*'`
Expected: PASS.

---

### Task 4: The Tabs Fit One Row

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensScript.kt`
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensCss.kt:36` (`body.screens .nav`), and a rule for
  `out`
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/ScreensScriptTest.kt`

**Interfaces:**
- Consumes: `MORE_ID`, `span.count`, `span.down-count` (Task 3).
- Produces: `fitTabs()`, `updateMore()` (Task 5 relies on `mark` calling it), `measureTabs()`, the class `out` on a
  hidden tab, and the `<nav>`'s `ResizeObserver` callback, which Task 5 adds `closeMenu()` to.

- [ ] **Step 1: Write the failing tests**

```kotlin
"the tabs should fit one row, the ones that don't fit hidden, never moved" {
  ScreensCss.CSS shouldContain "body.screens .nav { flex:1 1 0; flex-wrap:nowrap; min-width:0; }"
  ScreensCss.CSS shouldContain ".nav a.screen.out { display:none; }"
  body("fitTabs").apply {
    this shouldContain "var room = nav.clientWidth;"
    this shouldContain "tabs[id].classList.toggle('out', row.indexOf(id) < 0);"
    this shouldContain "updateMore();"
  }
  js shouldNotContain "style.order"
}

"a screen a link or a tab selects, and Split's right one, should be kept in the row" {
  body("fitTabs") shouldContain "var kept = layout === 'split' ? [first, second] : layout === 'one' ? [first] : [];"
  body("render") shouldContain "fitTabs();"
}

"each tab should be measured once, in bold, and the More button at its widest label" {
  body("measureTabs").apply {
    this shouldContain "tab.classList.remove('out');"
    this shouldContain "tab.classList.add('on');"
    this shouldContain "' · ' + ids.length + ' down'"
  }
}

"a screen going down or up should change the More button's count, never the row" {
  body("updateMore").apply {
    this shouldContain "'More screens: ' + out.length + ' hidden'"
    this shouldContain "', ' + down + ' not answering'"
    this shouldContain "more.hidden = out.length === 0;"
    this shouldNotContain "fitTabs"
  }
  body("mark") shouldContain "updateMore();"
  body("mark") shouldNotContain "fitTabs"
}

"the row should refit when the tabs' width changes, not their height" {
  js shouldContain "tabSizer.observe(nav);"
  js shouldContain "if (width === navWidth) return;"
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.ScreensScriptTest'`
Expected: FAIL: no `fitTabs`, `measureTabs`, or `updateMore` in the script.

- [ ] **Step 3: Implement**

Read `var more = document.getElementById('$MORE_ID');` beside `nav`, and keep `var tabWidth = Object.create(null);`,
`var moreWidth = 0;`, and `var TAB_GAP = 6;` (the `.nav`'s `gap`, `DashboardCss`). `measureTabs()` shows every tab
(removing `out`), measures each with `on` added (restoring it after), then shows the button with the widest label,
`String(ids.length)` and `' · ' + ids.length + ' down'`, measures it into `moreWidth`, and hides it again.
`updateMore()` counts the tabs with `out`, and of those the ones with `down`; it sets `.count`, sets `.down-count`
to `' · ' + down + ' down'` or empty, sets the accessible name, and hides the button with none out. `fitTabs()`:

```js
function fitTabs() {
  var room = nav.clientWidth;
  var kept = layout === 'split' ? [first, second] : layout === 'one' ? [first] : [];
  var widthOf = function (sum, id) { return sum + tabWidth[id] + TAB_GAP; };
  var row = ids;
  if (ids.reduce(widthOf, 0) > room) {
    var used = kept.reduce(widthOf, moreWidth);
    var full = false;
    row = ids.filter(function (id) {
      if (kept.indexOf(id) >= 0) return true;
      full = full || used + tabWidth[id] + TAB_GAP > room;
      if (!full) used += tabWidth[id] + TAB_GAP;
      return !full;
    });
  }
  ids.forEach(function (id) { tabs[id].classList.toggle('out', row.indexOf(id) < 0); });
  updateMore();
}
```

`render` calls `fitTabs()` at its end; `mark` calls `updateMore()` after changing a tab's state. Call
`measureTabs()` before the handoff section, so it runs before the first `render`, and again, followed by
`fitTabs()`, once `document.fonts.ready` resolves where `document.fonts` exists. Add a `ResizeObserver` on `nav`
(inside the existing `if (window.ResizeObserver)`) named `tabSizer`, keeping `var navWidth = -1;`; its callback reads
`entries[0].contentRect.width`, returns when it equals `navWidth`, then stores it and calls `fitTabs()`. CSS:
`body.screens .nav { flex:1 1 0; flex-wrap:nowrap; min-width:0; }` replaces the wrapping rule (its comment now says
the tabs take the bar's free width and the script fits them to it), and `.nav a.screen.out { display:none; }`.

- [ ] **Step 4: Run the screens specs**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.Screen*'`
Expected: PASS, the existing no-move test included.

- [ ] **Step 5: Check in the browser** (32 screens, windows 1440, 1024, and 600px wide)

One row of tabs at each width, the brand and layout buttons on it (below 720px, the tabs on a row of their own),
ending in `More <n> · <d> down`, the last tab never under the button. Open `#agent-ap-southeast-2-search`: its tab
is in the row and current. Shift-click a tab into Split: both screens' tabs are in the row. Select tabs one after
another: no other tab moves into or out of the menu. Open the 8-screen config: no More button, the page as before.

---

### Task 5: The Menu Works

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensScript.kt`
- Test: `src/test/kotlin/io/prometheus/proxy/dashboard/ScreensScriptTest.kt`

**Interfaces:**
- Consumes: `MENU_ID`, `SEARCH_ID`, `a.entry`, its `.name`, `.state`, and `li` (Task 3); `updateMore()` and the
  `tabSizer` callback (Task 4).
- Produces: `menuItems` (entry link by screen id, as `tabs` holds the tabs), `closeMenu()`, `placeMenu()`, `filterMenu(text)`,
  `visibleEntries()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
"a menu entry should work as a tab does, and close the menu" {
  js shouldContain "evt.target.closest('a.screen, a.entry')"
  body("closeMenu") shouldContain "if (menu.matches(':popover-open')) menu.hidePopover();"
}

"each entry should mark the current screen, the right pane's, and its screen's state" {
  body("render").apply {
    this shouldContain "entry.classList.toggle('on', current);"
    this shouldContain "if (current) entry.setAttribute('aria-current', 'true');"
    this shouldContain "else entry.removeAttribute('aria-current');"
    this shouldContain "entry.classList.toggle('second', layout === 'split' && id === second);"
  }
  body("mark") shouldContain "menuItems[id].classList.toggle('down', !up);"
  body("mark") shouldContain "menuItems[id].querySelector('.state').textContent = state.textContent;"
}

"the search should filter the entries by name, case-insensitively, as a substring" {
  body("filterMenu").apply {
    this shouldContain "var query = text.trim().toLowerCase();"
    this shouldContain "entry.querySelector('.name').textContent.toLowerCase().indexOf(query) < 0"
    this shouldContain "entry.parentNode.hidden ="
  }
  js shouldContain "search.addEventListener('input', function () { filterMenu(search.value); });"
}

// A search that matches nothing leaves no entry: Enter and Down then do nothing (Review Focus 3).
"the menu's keys should move through the entries left, and Enter in the search open the first" {
  body("visibleEntries") shouldContain "!entry.parentNode.hidden"
  js shouldContain "if (evt.key === 'Enter' && evt.target === search && list.length) list[0].click();"
  js shouldContain "var next = evt.key === 'ArrowDown' ? list[at + 1] : at <= 0 ? search : list[at - 1];"
  js shouldContain "if (next) next.focus();"
}

"opening the menu should place it under More and focus a cleared search; closing it should focus More" {
  body("placeMenu").apply {
    this shouldContain "var rect = more.getBoundingClientRect();"
    this shouldContain "menu.style.top ="
    this shouldContain "menu.style.right ="
  }
  js shouldContain "if (evt.newState === 'open') {"
  js shouldContain "search.value = '';"
  js shouldContain "search.focus();"
  js shouldContain "more.focus();"
}

"Left and Right should not step the tabs from inside the menu" {
  js shouldContain "if (evt.target.closest('#${ScreensHtml.MENU_ID}')) return;"
}

// Review Focus 4.
"a change in the tabs' width should close an open menu" {
  Regex("""navWidth = width;\s+closeMenu\(\);""").containsMatchIn(js) shouldBe true
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.ScreensScriptTest'`
Expected: FAIL: no `closeMenu`, `filterMenu`, `placeMenu`, or `visibleEntries`.

- [ ] **Step 3: Implement**

Read `menu` and `search` by id, and fill `menuItems` from `menu.querySelectorAll('a.entry')` (not `entries`, the
observers' callbacks' parameter). The nav's click handler
takes `a.screen, a.entry`, and after `select` or `fillRight` calls `closeMenu()`. `render` marks each screen's entry
as it marks its tab, through a local `var entry = menuItems[id];` (the lines in the test). `mark` sets the entry's `up`/`down` and copies the tab's state
words. `filterMenu(text)` hides each entry's `li` whose name lacks the query (an empty query shows all).
`visibleEntries()` returns the entry links whose `li` isn't hidden. A `keydown` listener on `menu` handles Enter in
the search and Up/Down as in the test (`at` is `list.indexOf(evt.target)`, -1 for the search; prevent the default
for the arrows). `placeMenu()` sets `top` to the button's bottom plus 4px and `right` to the window's
`clientWidth` minus the button's right, at least 8px. A `toggle` listener on `menu`: opening places it, clears the
search, filters, and focuses the search; closing, with focus inside the menu or on the body, focuses `more`. The body's
`keydown` handler returns for a target inside the menu, before its other checks. The `tabSizer` callback calls
`closeMenu()` after storing the width.

- [ ] **Step 4: Run the screens specs**

Run: `./gradlew unitTest --tests 'io.prometheus.proxy.dashboard.Screen*'`
Expected: PASS.

- [ ] **Step 5: Check in the browser** (32 screens, 1440px)

More opens below itself, right edges aligned, the search focused. Type `east-2`: five entries left; Enter opens the
first, its tab now in the row and current, the menu closed, focus on More. Type `zzz`: no entries; Enter and Down do
nothing, the console clean. Down and Up move through the entries; Escape closes and focuses More. Shift-click an
entry: Split, that screen on the right. Resize with the menu open: it closes. Each entry's dot matches its tab's.

---

### Task 6: Docs, Rules, Changelogs, and the Whole Check

**Files:**
- Modify: `website/prometheus-proxy/docs/web-dashboard.md` (The Screens Page, after the paragraph ending "opens its
  screen on its own.")
- Modify: `.claude/rules/dashboard-constraints.md` (the bullet "The screens page never moves a frame.")
- Modify: `CHANGELOG.md` (4.7.1's `proxy.dashboard.screens` entry, from line 38)
- Modify: `RELEASE_NOTES.md` (4.7.1's "One page for every screen", from line 41)

- [ ] **Step 1: Write the docs**

`web-dashboard.md`, a paragraph after the layouts paragraph:

> When the tabs don't fit one row, the row ends in **More**, which says how many screens it holds and how many of
> those aren't answering, such as *More 28 · 2 down*. It opens a list of every screen, each with its dot, and a
> search: type to narrow it, Up and Down to move through it, and Enter to open a screen; shift-click puts one in
> Split's right pane. The selected screen's tab, and in Split the right pane's, always stays in the row, and a name
> too long for its tab shows in full in its tooltip. In **All**, the grid scrolls, and each screen loads as its tile
> comes into view.

`dashboard-constraints.md`, in the screens bullet: the row fits by hiding tabs (`fitTabs`, the `out` class), never
reordering them, keeping the selected and Split's right screen; the More menu (`ScreensHtml.MENU_ID`) lists every
screen; `mark` and `updateMore` never refit, so a dot changing never moves a tab; in All a frame loads when its tile
nears the stage's view (an `IntersectionObserver`), never all at once, and never unloads. `ScreensScriptTest` and
`ScreensHtmlTest` pin these.

Changelogs, a clause each, concise: tabs that don't fit one row go into a More menu with a search, and All scrolls,
loading each screen as it comes into view.

- [ ] **Step 2: Run the docs specs**

Run: `rm -rf build/test-results/unitTest && ./gradlew unitTest --tests 'io.prometheus.misc.*' --rerun`
Expected: PASS.

- [ ] **Step 3: Check in the browser, whole** (32 screens, then 8)

Tasks 4 and 5's checks again at 1440, 1024, and 600px, in light and dark themes (the theme button), and All at each
width. The 8-screen page looks as it did before the change. The basics demo's screenshots show 8 short-named tabs
in Split, which fit: retake them (`make -C demos/basics screenshots`) only if a difference shows.

- [ ] **Step 4: Run lint, the build, the whole suite, and coverage**

First check that no other worktree is running the suite (`ps aux | grep -c '[G]radleWorkerMain'`), since the harness
specs bind fixed ports.

Run: `./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test`
Run: `./gradlew check unitTest --rerun harnessTest --rerun`
Run: `./gradlew koverVerify koverVerifyPerClass -x unitTest -x harnessTest`
Expected: all succeed. Then tell the user the branch is ready; commit only when they say so.
