# The Screens Page with Many Screens

**Date:** 2026-11-26. **Status:** approach A chosen; design awaiting approval.

## Intent

The proxy's screens page (`/screens`, #577) works for the demos' eight screens and breaks with many. A proxy listing
32 screens (the cap), run at 1440, 1024, and 600px wide in a 723px-tall window, showed:

- **The tabs wrap without limit:** 7 rows (267px) at 1440px, 12 at 1024px, 16 (579px) at 600px, where the screen
  below gets 100px.
- **All draws no screen at all.** Each tile shows its title bar only, though all 33 frames loaded. The cause is the
  CSS: a pane (`min-height:0`, `overflow:hidden`) gives the grid's auto rows no minimum, so with a definite stage
  height the rows share what's left (448px across 11 rows, about 30px each). It shows whenever the tiles' full height
  passes the stage's, so the demos' eight screens clip too on a short window.
- **All starts every screen at once:** 33 dashboards, each with its own WebSocket, on one click.
- **The brand and the layout buttons sit on the last row of tabs**, since the bar sets its items on its floor.

Customers who list more than a dozen screens meet this today, and option 2 (agents advertising their dashboards,
discussed but not designed) would bring more. The page should stay usable at the cap at any width.

## Goals

- **One row of tabs at any width,** with a **More** menu holding what doesn't fit.
- **No status hidden:** the More button says how many screens it holds and how many of those aren't answering.
- **A search over every screen,** in the More menu.
- **All usable at the cap:** tiles at full size, a grid that scrolls, and a frame that loads when its tile first comes
  into view.
- **Long names cut with an ellipsis,** the full name in a tooltip.
- **A short list looks as it does today:** no More button while every tab fits.

## Non-Goals

- Option 2: agents advertising their dashboards, and an Agents section of the menu.
- A side rail, or any change to the page's look beyond the row and the menu.
- Changing the 32-screen cap, the layouts, the links, the handoff, or the probes.
- The dashboards' own tab bars, which hold four fixed views.

## Anti-Goals

- A screen that's down with nothing in view to say so.
- A frame reloading because a tab moved into or out of the menu, the window resized, or All scrolled.
- Tabs jumping between the row and the menu as dots change color or a tab is selected, beyond the one swap below.

## Constraints

- `.claude/rules/dashboard-constraints.md`'s screens rules: a frame never moves in the page, and every frame's `src` is
  set in `setSrc` alone, by the load rule; dark colors go through `DashboardCss.darkRule`; text tokens hold 4.5:1 on
  `--surface-2`; forced colors keep their own paint; the coarse-pointer buttons are 46px tall.
- The page stays one server-rendered document with its script inline (`ScreensScript`), no htmx, no new assets.
- Tests check the script as strings (`ScreensScriptTest`) and the markup through the renderer (`ScreensHtmlTest`),
  as the dashboards' are checked.

## Approaches

- **A. A More menu at the end of one row (chosen).** Tabs that don't fit go into a menu with a search and a dot per
  screen; the button counts the hidden screens and those not answering. Short lists are unchanged; the search scales
  to option 2's agents; the page keeps the dashboards' tab bar.
- **B. A side rail,** the screens listed down the left, collapsible to dots. Scales to hundreds and shows every status
  at once, but takes width from every screen and changes the page's look for everyone.
- **C. A scrolling strip,** one row that scrolls sideways. Cheapest, but a red dot can scroll out of view with no sign,
  and there's no search, which is weak at 30 or more.

The user chose A, on my recommendation.

## Design

### The Row

- The tabs stay in the `<nav>` in the list's order, the proxy's first; the script fits them to the row by hiding the
  ones that don't fit, and never reorders the DOM, so a screen reader and Left/Right meet them in the list's order.
- The row shows tabs in order until the next one wouldn't fit beside the More button. If the selected screen isn't
  among them, it takes the last slot, room for it reserved first; a hidden tab takes no room, so it follows the
  others with no reordering. In Split, so does the right pane's screen. In All, no tab is current, and none is kept.
- The row refits when the tabs' width changes (a `ResizeObserver` on the `<nav>`, which takes the bar's free width,
  acting on a width change only) and when the selected or right pane's screen changes. Each tab is measured once, in
  bold, so selecting a tab doesn't push its neighbour into the menu, and the fit reserves the More button's widest
  label (every screen hidden, every one down), so a dot changing color never moves a tab.
- The More button stays hidden while every tab fits.
- With one row, the brand, the More button, the layout buttons, and the bar's end share the tabs' line. Below 720px
  the tabs keep their row of their own (`flex-basis:100%`), now a single line with More at its end.

### The More Menu

- A button at the row's end, inside the `<nav>`, so it travels with the tabs below 720px: **More 28**, followed by
  **· 2 down** in `--crit` while any screen it hides isn't answering. Its accessible name says the same in words
  ("More screens: 28 hidden, 2 not answering").
- It opens a popover (the native `popover` attribute, which gives light dismiss and Escape) holding a search field and
  a link per screen, in the list's order, each with its dot and full name. It lists every screen, not just the hidden
  ones, so the search finds any screen, as Chrome's tab search does. It opens below the button, their right edges
  aligned (placed by the script when it opens), 320px wide at most and `min(60vh, 480px)` tall, scrolling beyond.
  Opening it clears the search and focuses it.
- Typing filters the list by name, case-insensitively, as a substring. Down from the field moves into the list, Up and
  Down move through it, Enter follows the focused entry (from the field, the first entry the filter leaves), and
  Escape closes the menu and returns focus to the button. A change in the tabs' width closes an open menu, which
  would otherwise float where the button was.
- An entry is an `<a href="#<id>">` like a tab, handled by the same click code: a click selects the screen and closes
  the menu, a shift-click puts it in Split's right pane, and a modified click opens it elsewhere, which the handoff
  brings back. The current screen's entry carries `aria-current`, and the right pane's the `second` mark.
- `mark()` updates a screen's dot and words on its tab, its menu entry, and the More button's count.
- Left and Right still step through every screen, the menu's included; inside the menu they do nothing.

### Long Names

- A tab's name is cut at 24 characters' width (`max-width:24ch`, the dashboards' monospace) with an ellipsis, the full
  name in its `title`. The visually hidden state word stays whole. Menu entries show the full name, wrapping if need
  be.

### All

- Tiles keep their full height (title bar plus the scaled 1280×800 view): All's grid sizes its rows to their content
  (`grid-auto-rows:max-content`), and the grid, which already scrolls, scrolls. `min-height:auto` on a pane wouldn't
  do it: `overflow:hidden` makes a pane a scroll container, whose automatic minimum is 0.
- The load rule gains a case: in All, a pane counts as shown for loading once its tile comes within half the stage's
  height of its view (an `IntersectionObserver` on the stage, `rootMargin: '50% 0px'`), so opening All loads the
  visible rows and the next, and the rest load as they scroll in. A frame never unloads; one already loaded is drawn
  as it is. One and Split are unchanged.
- A screen that answers again reloads only if its frame has loaded, as today.

## Tests and Docs

- `ScreensHtmlTest`: the More button and its popover, an entry per screen in order with its dot and state word, the
  search field's label, the tabs' `title`s.
- `ScreensScriptTest`: the fit keeps DOM order and pins the selected and right pane's screens; `mark` updates the tab,
  the entry, and the count; the menu's keys; Left/Right ignore the menu; All's loading by intersection still sets
  `src` only through `load`/`setSrc`; All's grid takes `grid-auto-rows:max-content`.
- The docs' Screens Page section (`web-dashboard.md`): the More menu and its search, and All's scrolling and loading.
- `.claude/rules/dashboard-constraints.md`: the row's fit hides tabs and never reorders them; All loads by
  intersection and never unloads.
- Both changelogs' 4.7.1 sections, concisely.
- A browser check at the cap (1440, 1024, 600px, both themes, forced colors), and of the demos' eight screens; retake
  the basics demo's screenshots if its row changes at their width.

## Left to the Builder

- The menu's look within the dashboards' tokens: its border, shadow, padding, and the entries' spacing.
