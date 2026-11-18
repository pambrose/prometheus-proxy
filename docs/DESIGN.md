---
name: prometheus-proxy Dashboard
description: A monospace instrument surface for platform teams operating the proxy — flat, dense, and factual.
colors:
  bg: "#f2f5f8"
  surface: "#ffffff"
  surface-2: "#e9edf2"
  surface-3: "#dde3ea"
  line: "#cfd7e0"
  line-soft: "#e3e9ef"
  ink: "#131820"
  ink-2: "#48525f"
  ink-3: "#626b77"
  accent: "#c8461f"
  ok: "#1a7a42"
  ok-soft: "#e3f4ea"
  warn: "#8a5d08"
  warn-soft: "#f9f0da"
  crit: "#c02a20"
  crit-soft: "#fbe6e4"
  bg-dark: "#0d1015"
  surface-dark: "#151a21"
  surface-2-dark: "#1c222b"
  surface-3-dark: "#242c37"
  line-dark: "#2b333f"
  line-soft-dark: "#212934"
  ink-dark: "#e7ebf1"
  ink-2-dark: "#a4aebc"
  ink-3-dark: "#858f9d"
  accent-dark: "#ff7043"
  ok-dark: "#3fb950"
  ok-soft-dark: "#12251a"
  warn-dark: "#d29922"
  warn-soft-dark: "#2a2110"
  crit-dark: "#f85149"
  crit-soft-dark: "#2d1512"
typography:
  title:
    fontFamily: "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "15px"
    fontWeight: 640
    lineHeight: 1.5
    letterSpacing: "normal"
  body:
    fontFamily: "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  body-emphasis:
    fontFamily: "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "12.5px"
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: "normal"
  meta:
    fontFamily: "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "11.5px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  badge:
    fontFamily: "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "10.5px"
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: "normal"
  label:
    fontFamily: "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "10px"
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: "0.09em"
  micro:
    fontFamily: "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace"
    fontSize: "10px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
rounded:
  none: "0"
  tab: "6px 6px 0 0"
  pill: "20px"
  dot: "50%"
spacing:
  micro: "2px"
  tight: "4px"
  snug: "7px"
  label: "9px"
  row: "10px"
  gutter-list: "15px"
  gutter-detail: "18px"
  block: "22px"
components:
  row-item:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "10px 15px 10px 16px"
  row-item-hover:
    backgroundColor: "{colors.surface-3}"
    textColor: "{colors.ink}"
  row-item-current:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
  nav-tab:
    backgroundColor: "transparent"
    textColor: "{colors.ink-3}"
    typography: "{typography.body}"
    rounded: "{rounded.tab}"
    padding: "7px 14px 6px"
  nav-tab-hover:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.ink}"
  nav-tab-active:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.ink}"
    typography: "{typography.body-emphasis}"
  pill-ok:
    backgroundColor: "{colors.ok-soft}"
    textColor: "{colors.ok}"
    typography: "{typography.badge}"
    rounded: "{rounded.pill}"
    padding: "2px 7px"
  pill-crit:
    backgroundColor: "{colors.crit-soft}"
    textColor: "{colors.crit}"
    typography: "{typography.badge}"
    rounded: "{rounded.pill}"
    padding: "2px 7px"
  tag:
    backgroundColor: "{colors.surface-3}"
    textColor: "{colors.ink-2}"
    typography: "{typography.micro}"
    rounded: "{rounded.pill}"
    padding: "2px 7px"
  tag-warn:
    backgroundColor: "{colors.warn-soft}"
    textColor: "{colors.warn}"
  tag-crit:
    backgroundColor: "{colors.crit-soft}"
    textColor: "{colors.crit}"
  led-ok:
    backgroundColor: "{colors.ok}"
    rounded: "{rounded.dot}"
    width: "7px"
    height: "7px"
  led-crit:
    backgroundColor: "{colors.crit}"
    rounded: "{rounded.dot}"
    width: "7px"
    height: "7px"
  section-label:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.ink-3}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: "9px 15px"
  table-header-cell:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.ink-3}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: "9px 15px"
  table-cell:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "8px 15px"
  kv-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "7px 18px"
  kv-row-hover:
    backgroundColor: "{colors.surface-2}"
  copy-button:
    backgroundColor: "transparent"
    textColor: "{colors.ink-3}"
    rounded: "{rounded.pill}"
    width: "24px"
    height: "24px"
  copy-button-hover:
    backgroundColor: "{colors.surface-3}"
    textColor: "{colors.ink}"
  copy-button-copied:
    textColor: "{colors.ok}"
  copy-button-failed:
    textColor: "{colors.crit}"
  row-link:
    backgroundColor: "transparent"
    textColor: "{colors.ink-3}"
    typography: "{typography.body}"
  row-link-hover:
    textColor: "{colors.ink}"
  top-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "8px 15px 0"
  empty-state:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-3}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "22px 18px"
---

# Design System: prometheus-proxy Dashboard

## Overview

**Creative North Star: "The Terminal, Grown Up"**

This is a terminal that kept its honesty and gained a spine. Monospace is not a style choice here — it
is the operator's native tongue, the same face they read logs, configs, and `promtool` output in, and
switching them out of it at the dashboard boundary would be a small act of condescension. What the
terminal gained is everything a TTY never had: four real surface planes, a status palette with
semantics instead of ANSI approximations, hairline structure, and full light/dark parity so the page
matches whatever the operator's other windows are already doing.

The register is **instrumental and factual**. The interface reports; it does not interpret, reassure,
or editorialize. A status code is rendered as a number, not as "Healthy ✓". A departed agent is
labeled `gone` and left on screen rather than tactfully removed. Absent data gets an en dash, because
an empty cell reads as a rendering bug and this surface cannot afford to be mistaken for broken — its
entire job is telling an operator whether something is broken. Every visual decision is downstream of
that: if the dashboard is ever wrong, or ever *looks* wrong, it has failed at the one thing it exists
to do.

The system's discipline shows most in what it refuses. There are no shadows, anywhere. There are no
transitions, anywhere — hover, selection, and live swaps all land instantly, which is itself a claim
about latency the interface then has to keep. The accent color has exactly two jobs on the entire
surface: focus, and the current item. The only thing that moves is a 7px dot reporting whether the socket is alive. This is
a system built by subtraction, and the remaining elements are load-bearing.

**Key Characteristics:**

- One typeface — monospace — at 12.5px base, for every role from title to micro-label.
- Zero shadows. Depth is four tonal planes plus two hairline weights.
- Two radii: `0` and fully round. Rectangles hold content; circles report status. The one exception is the top
  corners of the folder tabs.
- The accent is used twice: focus rings and the current item's edge. It never fills a surface.
- No CSS transitions. The only animation in the system is the connection beacon.
- Full-bleed layout — no centered container, no max-width, no page margins.
- Complete light/dark parity via `prefers-color-scheme`; neither theme is the afterthought.

## Colors

A cool blue-grey neutral family carrying a single warm accent and a three-tier status palette — the
accent is the only warm hue in the system, which is why it can be used so sparingly and still be seen.
(The Row Links' brand marks carry their tools' own colors, which are not tokens and stay inside the mark.)

Every token exists as a light/dark pair; the frontmatter carries both (`accent` / `accent-dark`). The
light theme is `:root`; the dark theme is a `prefers-color-scheme: dark` override. Neither is a tinted
derivative of the other — the dark status colors are re-picked for a dark ground, not algorithmically
dimmed.

### Primary

- **Burnt Orange** (`accent`): The only warm hue on the surface, and deliberately the rarest. It draws
  edge of the current item (the selected row's 2px left edge, and the 2px line along the current tab's
  bottom) and the 2px focus-visible outline. That is the complete list. It brightens to a coral (`accent-dark`) in dark mode, which is the one place the two
  themes differ in character rather than only in value — against black it reads as a lit filament
  rather than as rust.

### Neutral

- **Cold Paper** (`bg`): The page ground. In light mode it is *slightly* darker than the reading
  plane, so panels sit forward on it. In dark mode it is the darkest value in the system.
- **Reading Plane** (`surface`): Pure white in light, near-black in dark. Where content that must be
  read carefully lives — the detail pane, the table body, the top bar.
- **Recessed Plane** (`surface-2`): The agent list, section headers, sticky table headers, the
  search strip, and the current tab. Structurally
  *behind* the reading plane in both themes.
- **Pressed Plane** (`surface-3`): The furthest step from the reading plane. Row hover and neutral tag
  backgrounds.
- **Structural Rule** (`line`): 1px hairlines that separate regions — bar from body, list from detail.
- **Soft Rule** (`line-soft`): 1px hairlines that separate rows *within* a region. Always lower
  contrast than `line`, so the eye reads region boundaries before it reads row boundaries.
- **Ink** (`ink`): Primary text — agent names, paths, table cells.
- **Ink Secondary** (`ink-2`): Neutral tag text. Used sparingly.
- **Ink Tertiary** (`ink-3`): Metadata, timestamps, hostnames, micro-labels, dimmed table columns,
  empty-state copy.

### Status

Three levels, each shipping as a **solid** and a **soft** companion.

- **Live Green** (`ok` / `ok-soft`): A valid agent's LED, the healthy connection beacon, 2xx scrape
  pills, a successful scrape's dot in a history strip, and healthy status counters.
- **Drift Amber** (`warn` / `warn-soft`): Reserved for conditions that are true but not yet failures —
  the `failed over` tag, and internal counters approaching their threshold. Notably it has **no LED
  form**; there is no amber dot in the system.
- **Fault Red** (`crit` / `crit-soft`): An invalid agent's LED, the disconnected beacon, non-2xx scrape
  pills, a failed scrape's dot in a history strip, the `gone` tag, departed table rows, and the "agent no
  longer connected" empty state.

### Named Rules

**The Two Places Rule.** The accent appears on focus rings and the current item's edge: the
selected row's 2px left edge and the current tab's 2px bottom edge, which mean the same thing. Nowhere else. It never fills a background, never sets body type, never becomes a button, never appears in a
chart. Its rarity is what makes selection findable in a field of monospace at a glance.

**The Paired Status Rule.** Solid status colors carry meaning on type, dots, and borders. Soft status
colors have exactly one job: backing a pill or tag. A soft color never becomes a row background, a
panel fill, or a section header — a status tint spread across a region would claim the whole region
has that status.

**The No Amber Dot Rule.** LEDs and beacons are binary: `ok` or `crit`. Warning is a *tag*, not a
lamp. An operator scanning the agent list is answering "is anything red," and a third dot color turns
a binary scan into a reading task.

### Measured contrast

Measured in headless Chrome against the live dashboard (both themes, both layouts, 1280px and 390px),
not computed from the token values. **Every text pairing clears 4.5:1**, and every non-text indicator
clears 3:1.

| Combination | Light | Dark |
|---|---|---|
| `ink-3` on `surface-2` (table + section headers) — *the binding case* | 4.59:1 | 4.88:1 |
| `ink-3` on `surface` | 5.40:1 | 5.37:1 |
| `ok` on `ok-soft` (2xx pill) | 4.71:1 | 6.33:1 |
| `ok` on `surface` (`live` label, healthy counters) | 5.38:1 | 6.88:1 |
| `warn` on `warn-soft` (`failed over` tag) | 5.07:1 | 6.29:1 |
| `crit` on `crit-soft` (non-2xx pill, `gone` tag) | 4.88:1 | 5.10:1 |
| `crit` on `surface` (departed row path cell) | 5.84:1 | 5.21:1 |
| `ink` on `surface` | 17.81:1 | 14.61:1 |

Non-text: LEDs and the beacon measure 4.57–6.88:1 against every ground they sit on, and the `accent`
current-item edge 4.83:1 light / 6.37:1 dark against `surface`, and 4.10:1 / 5.83:1 against the
`surface-2` of the current tab's fill above it and the strip below it.

**The binding constraint is `surface-2`, not `surface`.** Every quiet color lands on the recessed plane
somewhere — `ink-3` under every table and section header — and that pairing is a full tonal step
tighter than the reading plane. A color chosen against white alone will fail there. Check the worst
ground a token touches, never its most flattering one.

PRODUCT.md records no formal conformance commitment, so AA here is a property the system happens to
have rather than one it claims. Keeping it is nearly free; the values above are the floor to preserve.

## Typography

**Display Font:** none — the system has no display face.
**Body Font:** `ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace`
**Label/Mono Font:** the same stack. There is exactly one.

**Character:** A pure system-monospace stack, self-hosted by definition — no webfont, no CDN request,
no FOUT. It resolves to SF Mono on Apple platforms, Consolas on Windows, and the platform default
elsewhere, so it always matches the terminal the operator already has open. Hierarchy comes entirely
from size, weight, case, and color — never from a second family, because there isn't one.

### Hierarchy

- **Title** (weight 640, 15px, lh 1.5): The agent name in the detail hero. The single largest text on
  the surface. The unusual 640 weight sits between the stack's regular and bold — heavy enough to
  anchor the pane, light enough not to shout.
- **Body** (weight 400, 12.5px, lh 1.5): The base. Agent names in the list, paths, table cells, kv
  labels. Nearly everything.
- **Body Emphasis** (weight 600, 12.5px): The brand mark and the current tab. Weight, not size,
  carries the difference.
- **Meta** (weight 400, 11.5px, `ink-3`): The hero metadata strip, failover line, status bar, and the
  second line of an agent row (`address · N paths`) — facts that support the primary reading without
  competing with it. One role, not two: an earlier 11px step for the agent-row line did the same job as
  11.5px half a pixel apart, which is a distinction no reader can act on.
- **Badge** (weight 600, 10.5px): Status-code pills. The only place a number is bolded.
- **Label** (weight 600, 10px, `0.09em`, uppercase, `ink-3`): Section headers, list headers, table
  column headers, and the Column Guide's names for them. Engraved rather than printed — the letterspacing does the
  work that a rule or a heavier weight would otherwise have to.
- **Micro** (weight 400, 10px, normal spacing, mixed case): Tag text only — `consolidated`, `failed
  over`, `gone`. Deliberately *not* the Label treatment despite sharing its 10px size: a tag reports a
  fact about one row, so it stays lowercase and unweighted rather than reading as a column heading.

### Named Rules

**The Single Face Rule.** One monospace stack, everywhere, no exceptions. Introducing a sans for "UI
chrome" or a serif for warmth would break the premise: the dashboard reads as an extension of the
operator's terminal precisely because it never switches voice.

**The Tabular Rule.** Any number a reader will compare down a column carries
`font-variant-numeric: tabular-nums` and right-alignment. Durations and status codes already do. A
monospace face makes this nearly free — which is one more reason the face is monospace.

**The Engraved Label Rule.** Every region header is 10px, uppercase, `0.09em`, weight 600, in `ink-3`.
Small and quiet, but unmistakably a label rather than content. Region headers never get larger; if a
region needs more prominence, it gets a hairline, not bigger type.

## Layout

**Full-bleed, no container.** There is no `max-width`, no centered column, no page padding. The
interface occupies the entire viewport, because a dashboard on a wide monitor should use the monitor.

**The master–detail grid.** `262px 1fr`. The 262px list column is sized to hold a typical agent name
plus its address line without wrapping.

**Height comes from flex, never arithmetic.** `body` is a column flex container at `min-height: 100vh`
and the content region is `flex: 1`, so it occupies exactly the space the top bar leaves. It must not
go back to subtracting a bar height: the bar sizes itself from its own content and grows on touch
devices, so any hard-coded number is wrong on some viewport — the previous `calc(100vh - 44px)` was
subtracting 44px from a bar that actually measured 48px.

**The path table.** Deliberately flat — no selection, no drill-in, no per-session state. The row *is*
the answer. Column headers are `position: sticky; top: 0`, and the whole table lives inside its own
`overflow-x: auto` scroller so wide content never makes the page body scroll sideways. Cells are
`white-space: nowrap`; a path or target URL is truncated by the scroller, never wrapped, because a
wrapped path destroys the column scan the table exists for.

**Two gutters, deliberately.** List and table regions use a **15px** horizontal gutter; the detail
pane uses **18px**. This is not drift — the wider gutter marks the detail pane as the reading surface
and the narrower one keeps the scanning surfaces dense. Vertical rhythm follows the region: 9px for
label rows, 10px for list rows, 8px for table cells, 7px for kv rows, and 8px above the tabs in the top bar, which stand on its bottom rule.

**The kv row.** Inside the detail pane, key/value pairs use a fixed `220px` minimum label column so
values align down the pane without a table.

**Responsive.** One width breakpoint: **720px**. Below it the master–detail grid collapses to a single
column and the list's right border becomes a bottom border, and the tabs take a row of their own, the
top bar's last, so they still stand on its rule and open into the strip below. Nothing else changes —
no hamburger, no drawer, no reflowed table. The table keeps its horizontal scroller, which is the correct mobile
behavior for tabular data.

**One input-mode breakpoint: `pointer: coarse`.** Tabs grow to a 44px minimum height on touch
devices only. Target size is a property of the *input*, not the viewport — a tablet is wide and
touch-driven, a narrow desktop window is neither — so this is deliberately not folded into the 720px
rule, and pointer devices keep the dense 34px tab rather than paying for vertical space they cannot
use.

### Named Rules

**The Full Bleed Rule.** No centered container, ever. This is instrumentation, not a document.

**The Contained Scroll Rule.** Wide content scrolls inside its own `overflow-x: auto` container. The
page body never scrolls horizontally. If a new region can outgrow the viewport, it gets its own
scroller before it ships.

## Elevation & Depth

**There are no shadows in this system.** Not one `box-shadow` declaration exists. Depth is built
entirely from two mechanisms: four tonal planes and two hairline weights.

The tonal planes do not form a simple light-to-dark ramp. `surface` is the **reading plane**;
`surface-2` and `surface-3` step *away* from it in the direction of contrast — darker in light mode,
lighter in dark mode — while `bg` sits just behind the reading plane as the page ground. The result
reads identically in both themes even though the absolute lightness ordering inverts.

The two hairline weights are the other half. `line` separates regions; `line-soft` separates rows
within a region. Because `line-soft` is always the lower-contrast of the pair, the eye resolves
structure before it resolves content — you see the panes, then the rows, then the data.

### Named Rules

**The No-Shadow Rule.** Zero shadows. Not for hover, not for panels, not for the "elevated" state of
anything. If an element needs to separate from its neighbor, it gets a hairline or a tonal step.

**The Reading Plane Rule.** `surface` is where content is read. `surface-2` and `surface-3` are always
*away* from it in contrast, never toward it. When adding a plane, ask which direction from the reading
plane it belongs, not whether it is lighter or darker in absolute terms.

## Shapes

The form language is **two radii and nothing between them.**

- **`0` — everything structural.** Panels, rows, cells, the top bar, section headers, empty states, the
  table. No rounded corners on any container, with one exception, below.
- **`6px` on the top corners of the folder tabs, and only there.** A tab is a handle on the layout below
  it, not a container, and its rounded top is what reads as a folder tab rather than a box on the bar.
- **Fully round (`20px` on short elements, `50%` on dots) — everything that reports state.** Status
  pills, tags, LEDs, the connection beacon.

The split is semantic, not decorative: **rectangles hold content, round things report status.** A
7px circle is a lamp. A 20px-radius capsule is a badge. Neither shape is ever used for a container,
and no container is ever softened toward them. Apart from the tabs' top corners there is no `4px`, no
`6px`, no `8px` radius in the system, and introducing one would blur a distinction that currently does
real work.

Borders carry the remaining form language: 1px hairlines for structure, among them the tabs' `line`
outlines, and the two current-item edges in `accent`, the only borders in the system thicker than a
hairline: the row item's 2px left border, transparent at rest, and the current tab's 2px bottom.

### Named Rules

**The Square-or-Circle Rule.** Radius is `0` or fully round. Nothing in between ships, but for the
folder tabs' top corners. If a new element feels like it wants `6px`, it is a container and it wants
`0`.

## Components

The component philosophy is **legible under pressure**: every element is optimized to be read
correctly on the first glance by someone who is busy. Contrast, alignment, and tabular numbers outrank
refinement, and no component earns visual weight it doesn't need to do its job.

### Row Item (agent list)

A `<button>` styled as a full-width list row, so it is keyboard-operable by construction.

- **Shape:** square (`0`), full-bleed, `10px 15px` padding (`16px` on the left, so with the `2px` edge the text sits
  `18px` in), `line-soft` bottom divider.
- **Structure:** two lines — an LED plus the agent name, followed by a neutral Tag naming its identity when
  it registered as one, then `address · N paths` in Caption/`ink-3`.
- **Rest:** transparent background.
- **Hover:** background steps to `surface-3`. No transition.
- **Focus:** `2px solid accent` outline, `outline-offset: -2px` so the ring sits *inside* the row and
  never overlaps its neighbors. The row's id is built from its agent id, so htmx gives focus back after
  each push replaces the list.
- **Current:** background lifts to `surface` (the reading plane, matching the detail pane it controls)
  and the 2px left border becomes `accent`. Marked `aria-current="true"`.

### Status Pill

- **Style:** soft status background, solid status text, fully round, `2px 7px`, Badge type (600/10.5px).
- **Variants:** `ok` for 2xx, `crit` for everything else. Only two.
- **Content:** the literal status code. Never "Success" or an icon.

### Tag

- **Style:** fully round, `2px 7px`, Micro type (400/10px, normal spacing, mixed case) — *not* the
  uppercase Label treatment, despite the shared 10px size.
- **Neutral:** `surface-3` background, `ink-2` text — used for `consolidated`, and for an agent's identity in
  the agent list.
- **Warn:** `warn-soft` / `warn` — used for `failed over`, and for a `taken over` event in the Activity Table.
- **Crit:** `crit-soft` / `crit` — used for `gone`, and for the problem events in the Activity Table (`rejected`,
  `refused`, `bad token`, `evicted`). A tag after a name sits `7px` from it; one that opens a cell takes no gap.

### Status LED

A `7px` circle, `50%` radius, `ok` or `crit`. Binary by rule (see The No Amber Dot Rule). Appears
before the agent name in both the list row and the detail hero, so the same signal sits in the same
relative position in both panes.

### Navigation

Folder tabs, one per layout: Agents, Paths, Activity.

- **Style:** top corners rounded `6px`, `7px 14px 6px`, `ink-3` at rest, outlined in `line` across the
  top and down the sides, and open at the bottom. The tabs stand on the top bar's bottom rule: the nav
  reaches 1px into it, so a tab at rest lets the rule show through its transparent bottom, and its
  outline and the rule read as one line.
- **Hover:** `surface-2` background, `ink` text. The rule still shows below it.
- **Active:** `surface-2` background, `ink` text, weight 600, the same `line` outline, and a `2px`
  `accent` bottom that covers the rule beneath it: the tab's edge, as the row item's left border is the
  row's. Its bottom padding drops to `5px` to pay for the extra pixel, so it stands as tall as the
  others. Marked `aria-current="page"`.
- **Focus:** `2px solid accent`, `outline-offset: -2px`, *inside* the tab, as on the row item: the tab
  meets the strip below it, which an outside ring would cross.
- **Forced colors:** the tabs paint themselves in system colors (`forced-color-adjust: none`), so the
  current tab is told apart by more than its weight: every tab outlined in `CanvasText` as the rule is,
  `LinkText` at rest, and the current tab in `CanvasText` with a `Highlight` bottom line. Its focus ring
  is `Highlight`.
- **Behavior:** real navigation, not a swap. The three layouts are bookmarkable URLs, so they are links
  in a `<nav>`, not ARIA tabs. **Keyboard:** Tab reaches each one; Left and Right open the layout before
  or after the current one, wrapping at the ends, from anywhere on the page but a text field, where they
  still move the caret. Not only from a focused tab: a click on a tab loads a page whose focus is on
  `<body>`, and Safari never focuses a link on a click or a Tab. From a focused tab they step from that
  tab, and the page that opens focuses its own, so a keyboard user's next arrow keeps going. A key held
  with a modifier is left alone (Alt+Left is the browser's Back).

### Section Header

`surface-2` background, `line` bottom border, `9px 15px`, Label type. A flex pair: the section name on
the left, a bare count on the right. The count is never decorated — no pill, no parentheses, no "items".

### Key–Value Row

`7px 18px`, `line-soft` divider, `220px` minimum label column. Key in `ink`, value in `ink-3`. The
scrape variant prepends a fixed `64px` timestamp column so times align down the pane.

- **Hover:** the row steps to `surface-2`, the same step the path table's rows take. No transition.

Every scrape time, here and in the Path Table, is `HH:mm:ss` on a 24-hour clock in the viewer's zone, the one
Grafana uses, whatever zone the proxy runs in. Its title gives the date and the zone's name, which the column has no
room for.

### Path Table

The signature component. Eight columns: Path, Agent(s), Target, Src, Last scrape, Status, Duration, History; a
ninth, Identity, after Agent(s) whenever agent auth is on; and Links last whenever `proxy.dashboard.links` names any.

- **Header:** `surface-2`, sticky at `top: 0`, Label type, `line` bottom border.
- **Cells:** `8px 15px`, `line-soft` divider, `white-space: nowrap`.
- **Numeric columns:** right-aligned with `tabular-nums`.
- **Dimmed columns:** Identity, Target, Src, Last scrape, Duration, and Links render in `ink-3` — supporting facts,
  scanned only after the row is located by path or status.
- **Row hover:** cells step to `surface-2`. No transition.
- **Path:** a Copy Button follows a registered path and copies its proxy URL.
- **Target:** a Copy Button follows the URL in the same cell, showing the target of the agent the row
  names; a departed row keeps the target it had as its agent left. A row with no target shows the en dash
  and no button, since there is nothing to copy.
- **History:** a History Strip, then the share of those scrapes that succeeded in `ink-3`, rounded down so a
  path with any failure never reads 100%. A path not yet scraped shows the en dash, as does a departed path
  whose agent left before its first scrape.
- **Identity:** the auth identity of the path's first registrant, which any agent joining the path must
  share. The legacy shared `agentToken` reads *shared token*; a departed path keeps the identity it had as
  its agent left, so a search for an identity finds its gone paths too. Shown or hidden by whether agent auth
  is on, never by the rows, so the column doesn't come and go as a search narrows or the last live path goes.
- **Links:** the row's Row Links. Shown or hidden by the config, never by the rows, as Identity is, and last, so the
  columns read to find a row keep their places with links on or off.
- **Departed row:** the path cell turns `crit` and a `gone` tag follows the agent name. The row stays
  in place — a path whose agent vanished is the single most important thing this table shows, and
  removing it is how the old interface hid exactly that case. It appears as the path leaves, scraped or not.
- **Match count:** while a search is active, a Meta-type line in `ink-3` above the header says
  `Showing N of M paths`; a search that leaves no row is an Empty State instead (*No paths match the
  filter*, or *No problems: every path's last scrape succeeded* for "Problems only" alone).

### Path Search

The one form in the system, above the Path Table and the Activity Table on `surface-2` with a `line` bottom border,
so it reads as part of the table's header rather than a separate panel. One component on both: the label and the
placeholder name what it filters, and the form submits to its own layout's route.

- **Input:** a `type="search"` field, square (it holds content), `surface` ground, `line` border, Body
  type in `ink` with an `ink-3` placeholder. Focus is the standard `2px solid accent` ring.
- **Problems only:** a checkbox and its label in `ink-2`. The checked box is neutral `ink-2`, not the
  accent, which stays on focus rings and the current item (see The Two Places Rule).
- **Column guide:** the form's last item, pushed to the strip's right end, the button that opens the Column
  Guide below it. On a narrow viewport it wraps with the checkbox onto a second row and keeps to the right.
- **Behavior:** a change applies as it is typed (debounced 250ms) and lands in the URL
  (`/paths?q=…&problems=1`), so a filtered view is a link. It sits outside the table's out-of-band
  region, so a push never replaces the field someone is typing in. Without the script it is a plain GET
  form. The match count is spoken through the Connection Announcer once per change.

### Column Guide

What each of the table's columns holds, in one place: a strip between the Path Search and the table (above the
Scrape Result, when that is open), on `surface` with a `line` bottom border, as the Scrape Result is. On the Paths and
Activity layouts; the Agents layout has no table columns to explain.

- **Button:** *Column guide*, at the search's right end, a `<button type="button">` with `aria-expanded` and
  `aria-controls`: a disclosure, not a dialog. Body type in `ink-2`, like the Problems only label beside it, after a
  `13px` question-mark glyph drawn as the Copy Button's are (a CSS mask in `currentColor`). No border and no
  background, at least `24px` tall. **Hover** and **open:** `ink`. **Focus:** `2px solid accent`, `outline-offset:
  1px`, the accent's only appearance on it (see The Two Places Rule). Named rather than a bare `?`, which wouldn't say
  what it opens, and not *Columns*, which reads as a picker that shows and hides them.
- **Head:** `COLUMNS` in Label type on the left, and the Scrape Result's close button on the right.
- **Body:** a `<dl>` on a `max-content 1fr` grid, `6px 18px` gaps, baseline-aligned. Each `<dt>` is the column's name
  in the header's own Label type, so it reads as the header it explains; each `<dd>` is one short sentence in `ink-2`.
- **One list:** each view builds its columns once (`TableColumn`), and the header row and the guide both iterate them,
  so a column can't reach the header without an explanation, and the guide lists Identity and Links exactly when the
  table shows them. The Event explanation names the problem kinds from `ActivityKind`, so a new kind can't be left out.
- **A strip, not a layer:** it pushes the table down. A floating panel would need a shadow (see The No-Shadow Rule)
  and would cover the columns it explains.
- **Survives pushes:** outside every out-of-band region, so it stays open as the table updates. Tooltips or a pop-up
  per header were ruled out for this reason: the table's header is replaced with it every refresh, which would close
  them; hover reaches neither touch nor the keyboard; and ten icons would crowd the engraved header.
- **Behavior:** starts closed on every page load. The button opens and closes it; the close button, or Escape from
  anywhere but a field being typed in, closes it and hands focus back to the button. Not only from inside it: Safari
  doesn't focus a button it clicks, so after a mouse click focus is on `<body>`. Escape in the search still clears it.

### Activity Table

The Activity layout's one component: the Path Table's surface, header, cells, and hover, listing the proxy's
recent events newest first. Six columns: Time, Event, Agent, Path, Detail, and Identity after Agent whenever agent
auth is on.

- **Event:** the ordinary coming and going (`connected`, `registered`, …) in plain `ink`; a problem as a Crit Tag and
  a take-over as a Warn Tag, so a scan for red finds what needs acting on. A burst folded into one row is counted
  after it in `ink-3` (`registered ×40`).
- **Path:** the paths the event concerns, each with its slash, a burst's first three then `and N more` in `ink-3`;
  the en dash for an event with none.
- **Detail:** `ink-3`, and the one cell in any table that wraps (`white-space: normal`, at least `280px`): a
  rejection's reason is a sentence, and a row that scrolled sideways to read it would hide the event it explains.
- **Time:** the Path Table's local time of day, its date and zone on hover.
- **States:** the Empty States, for a feed with nothing yet, with no problems, or with no match.
- **Pushes:** only on a change. Unlike the Path Table, pushed whole every refresh, a page is sent the table again
  only when the feed has recorded something or the page's search has changed, so a row being read or selected stays
  put between events. Nothing in it reads the clock as it renders, so the table a page keeps is the one a push would
  have sent.

### History Strip

A path's recent scrapes, oldest on the left: one `4px` dot per scrape on a `6px` pitch, `ok` for a 2xx and
`crit` for anything else. Round, because it reports state (see The Square-or-Circle Rule), and binary, like
the Status LED.

- **Drawing:** one `<i>` per run of like results, painting its dots as a repeating radial-gradient
  background `n × 6px` wide, rather than one element per scrape. The table is pushed whole every refresh, so
  a healthy path's strip stays one element however long it is.
- **Length:** `proxy.dashboard.pathHistorySize` dots (default 20), so `120px` at the default, on every row. Until
  a path's history fills, its first run is `slot`: dots in `line` for the scrapes still to come, so the strip is
  always full width, its newest dot and the percentage after it sit in the same place on every row, and the column
  doesn't widen on every refresh as the histories fill. A consolidated path records one scrape per agent, so its
  strip fills that many times faster.
- **Column width:** History, like Links, keeps its content's width (`width:1%`). A table wider than it needs
  shares its spare width in proportion to its columns' content, and a full-width strip would draw a wide share,
  opening a gap between the percentage and the links; the other columns take it instead.
- **Accessibility:** one `role="img"` whose `aria-label` and `title` say "N of M recent scrapes succeeded",
  the figure the percentage beside it rounds.
- **Forced colors:** `forced-color-adjust: none` on the dots, since forced colors drop backgrounds and the
  gradient with them.
- **Never a pill.** The strip doesn't use the Status Pill's class: the demo's screenshot script counts
  `pill ok` in the table against the number of paths.

### Copy Button

A `24px` icon-only `<button type="button">` that copies the URL of the cell it sits in: a path's proxy
URL after the path, a target URL after the target. They read the page, not the proxy; the Scrape Button,
which shares their look, is the one that acts.

- **Rest:** transparent, a `13px` copy glyph in `ink-3`, quieter than the path it follows and matching
  the dimmed Target text.
- **Hover:** fully round `surface-3` background, glyph in `ink`.
- **Focus:** `2px solid accent`, `outline-offset: 1px` — outside, as on the search input.
- **Copied:** the glyph becomes a check in `ok` for 1.5s. **Failed:** a cross in `crit`, as briefly.
  Either result is also spoken through the Connection Announcer, since the glyph and its color do not
  reach a screen reader.
- **Glyphs:** CSS masks painted in `currentColor`, not inline SVG, because the table is pushed whole on
  every refresh and an SVG per row would grow every frame. They are data URIs in the page's own CSS, so
  nothing is fetched.
- **Size:** `24px` meets the WCAG 2.2 minimum target size; negative vertical margins keep it from
  making the row taller.
- **Survives pushes:** the button's id is built from its kind and its path, so htmx gives focus back to
  it after each swap, and the page puts the result back on the new button until it expires.
- **Proxy URL:** the proxy can't know the name the viewer reaches it by, so the button carries the path
  and the page builds the URL from the host it was opened at and the scrape port the proxy renders onto
  `<body>`. A departed path has none: the proxy no longer serves it.

### Scrape Button

The Copy Button's shape, after a live path's proxy-URL button, with a circular-arrow glyph: it has the proxy
scrape the path at once and shows the result in the Scrape Result above the table. Rest, hover, focus, glyph,
and size are the Copy Button's, so the row's two buttons read as a pair. It names its path in its
`aria-label` (*Scrape /orders_metrics now*), and its id is built from the path, so focus survives a push. A
departed path has none, since the proxy no longer serves it. It needs no confirmation: a scrape changes
nothing on the proxy, and the activity feed records who asked.

### Scrape Result

A panel between the Path Search and the Path Table, below the Column Guide when that is open, on `surface` with a
`line` bottom border, hidden until a Scrape Button fills it. Outside every out-of-band region, so a push never clears a
result being read.

- **Heading:** a Status Pill, the path in weight 600, then the facts in `ink-3`, joined with ` · `: the
  outcome, the time, the size, and for a body, its content type, its families and samples when it is in a
  text format, and `# EOF` when the body ends with one. A body in no text format, such as protobuf, shows
  no counts rather than zeros, which would read as an empty text body. A close button, the Copy Button's
  shape with a cross, ends the line and hands focus back to the Scrape Button that filled the panel. A
  refused scrape shows a Crit Tag, *not scraped*, and why.
- **Failure:** the agent's reason in `crit`, on its own line.
- **Body:** the first 2 KB, cut at a line break, in a `<pre>` on `surface-2` with a `line-soft` border,
  at most `240px` tall, scrolling inside itself. Meta size in `ink-2`. When cut, a note in `ink-3` gives
  the whole body's size.
- **Announced:** the heading carries a sentence the page speaks through the Connection Announcer once
  the result arrives (*Scrape of /orders_metrics finished: 200 in 120 ms.*).

### Row Links

The Links column's content: each link the operator configured (`proxy.dashboard.links`) that the row's path can fill,
by its configured name or, with an `icon`, by its tool's brand mark. Two names in a row are joined with ` · `; a mark
stands `4px` from its neighbours instead. Centered in the column, under a centered header, so the header stands over
the marks rather than at their left. They open the path in another tool — Prometheus, Grafana, a runbook — and change
nothing, so they are links, not buttons.

- **Rest:** a name is the dimmed column's `ink-3`, underlined `2px` below the text so a link reads as one among plain
  supporting facts. Not the accent, which stays on focus rings and the current item (see The Two Places Rule).
- **Mark:** the tool's own, a `16px` Simple Icons mark in a `24px` target like the Copy Button's, in the brand's color
  rather than a token: the one warm hue on the page besides the accent, confined to the mark. Painted through the mark
  as a CSS mask, as the Copy Button's glyphs are, but fetched from the proxy rather than inlined, so a push carries only
  the link's class. Measured against `surface-2`, a hovered row's ground, at WCAG 1.4.11's 3:1 for a non-text mark,
  since the name is also in the link's tooltip and `aria-label`: Prometheus `#E6522C` at 3.18:1 light and 4.27:1
  dark; Grafana's `#F46800` at 5.20:1 dark but 2.62:1 light, so the light theme takes `#E26000`, at 3.02:1.
- **Hover:** a name steps to `ink`, as the nav's does. A mark's round target takes a `1px` `ink-3` ring rather than
  the Copy Button's `surface-3` fill, on which the light theme's marks would measure 2.90:1 and 2.75:1; on the hovered
  row's `surface-2` they hold 3:1. No transition.
- **Tooltip:** what the mark does, *Open in Grafana*, in the link's `title`, which the browser shows on hover as it
  shows the Copy Button's and the Scrape button's, so every tooltip in the table looks and waits alike.
- **Focus:** `2px solid accent`, `outline-offset: 1px`, as on the Copy Button.
- **Forced colors:** a mark is painted `LinkText`, since forced colors would otherwise replace its paint and leave the
  link blank.
- **Behavior:** each opens in a new tab, with `rel="noopener noreferrer"`, and names its path and tool in its
  `aria-label` (*Open /orders_metrics in Grafana*), so a screen reader's list of the page's links tells one row's from
  another's. Its id is its place in `proxy.dashboard.links` and its path (`link-1-orders_metrics`), so the push that
  replaces the table every refresh hands focus back to it, as a Copy Button's id does.
- **Absence:** a link whose template needs a value the path lacks (a label, an identity) is left out, never shown with
  an empty slot, so a departed row, whose labels went with its entry, keeps only the links that use none. A row
  left with no link shows the en dash (see The Named Absence Rule).
- **Never a pill.** Plain text or a mark, so the demo's screenshot script's count of `pill ok` stays one per row.

### Connection Beacon

The other signature component, and the only animated element in the system. A `7px` dot plus a text
label, both in the top bar.

- **Live:** `ok` dot, label `live`, `pulse 2.4s ease-out infinite` fading between full and 40%
  opacity. Slow enough to read as breathing rather than flashing.
- **Reconnecting:** `crit` dot, label `reconnecting…`, `blink 1s steps(2, start) infinite` between
  full and 25% opacity. The `steps(2)` timing makes it snap rather than fade.
- **Reduced motion:** both animations set to `none`. Color and label still carry the full signal, so
  nothing is lost.
- **Mechanism:** driven by a `ws-down` class on `<body>` rather than by the server, because the status
  bar is frozen precisely when the connection is down. Both labels are always in the DOM; CSS chooses.

### Status Bar

`surface`, `8px 15px 0`, Meta type in `ink-3`: no bottom padding, because the tabs stand on the bar's rule,
and the brand mark and the counters carry `6px` of their own, which sets their text on the tabs' line. One
row above 720px, where the counters wrap within themselves rather than push the tabs off the rule. Left to
right: the brand mark, the layout's tabs, then the
connection beacon and a run of counters — agents, paths, and the internal chunk/scrape gauges, which
turn `warn` as they approach their thresholds.

A **failing count** joins the run in `crit` whenever a registered path's last scrape failed (`2 failing`),
and the bar carries the tab's title, which counts the paths failing or departed, the ones "Problems only"
keeps, and reads `(3 problems) prometheus-proxy`, so a dashboard in a background tab still says something is
wrong.

A **departed count** joins the run in `crit` whenever any path is departed (`3 departed`). It exists
because the paths counter reports *registrations*, so a departed path is deliberately absent from it —
which without explanation reads as the bar contradicting the table directly beneath it. Every stat
carries `white-space: nowrap` so a narrow viewport wraps between stats rather than through one.

### Connection Announcer

A visually-hidden `role="status" aria-live="polite"` region, written by the WebSocket lifecycle
handlers: *"Connection to the proxy lost. Reconnecting."* and, on recovery from a real outage,
*"Connection to the proxy restored."* The Copy Button writes its result there too, once per click, and
the Path Search its match count, on either table, once per change of filter.

It exists because the beacon reports connection state through color and a change of rhythm, and
neither reaches a screen reader — without it the page simply goes silently stale, which is the exact
failure this dashboard exists to make impossible to miss. Deliberately **outside every out-of-band
region**: a live region the push loop rewrote would re-announce itself on every frame. Scoped to the
connection and the result of a copy click for the same reason — announcing pushed counters would make
it unusable.

### Empty States

Plain sentences in `ink-3`, no illustration, no icon, no call to action. Three variants: inline
(`14px 15px`), padded (`22px 18px`), and the `gone` variant which renders in `crit` for "Agent X is no
longer connected." Copy states the fact and, where useful, the next action in the same breath —
"Select an agent to see its paths and recent scrapes."

### Not yet in the system

Recorded so future work doesn't assume otherwise: there are **no dialogs, no toasts, and no
destructive actions**, and the Path Search is the only form. The one action, the Scrape Button, changes
nothing on the proxy, so it borrowed the Copy Button's shape rather than defining an action button. PRODUCT.md
records that control actions that do change the proxy are a planned direction; when they arrive they will
need a genuinely new component vocabulary (destructive confirmation, action button, disabled state), and
inventing it is a design decision, not an extraction.

### Named Rules

**The Still Interface Rule.** No CSS transitions. Hover, selection, and live swaps land instantly.
This is a truth claim: the interface is asserting that nothing is being waited on, and a 150ms ease
would make a live push look like a request.

**The Named Absence Rule.** Absent data renders as an en dash (`–`), never as an empty cell. An empty
cell reads as a rendering failure; a dash reads as "the agent never told us." On a surface whose job
is reporting failure, the difference is the whole product.

**The Say-So Rule.** When state disappears, the interface says so rather than going blank or freezing.
A selected agent that disconnects gets a sentence, not a stale pane. A path whose agent departed stays
in the table with a `gone` tag. A silently frozen dashboard is what makes an operator stop trusting it.

## Do's and Don'ts

### Do:

- **Do** use the monospace stack for every new element. Hierarchy comes from size (15 / 12.5 / 11.5 /
  11 / 10.5 / 10), weight (400 / 600 / 640), case, and color — never from a second family.
- **Do** reach for a hairline or a tonal step when something needs to separate. `line` between
  regions, `line-soft` between rows.
- **Do** give every new region header the engraved Label treatment: 10px, uppercase, `0.09em`, weight
  600, `ink-3`, with a bare count on the right if it has a count.
- **Do** put `tabular-nums` and right-alignment on any number that will be compared down a column.
- **Do** wrap any region that can outgrow the viewport in its own `overflow-x: auto` scroller.
- **Do** render absent data as an en dash and departed state as visible-and-labeled.
- **Do** pair every animation with a `prefers-reduced-motion: reduce` override, and make sure color
  and text still carry the full signal when motion is off.
- **Do** put focus rings *inside* full-bleed rows and tabs (`outline-offset: -2px`) and *outside* icon
  buttons, links, and inputs (`outline-offset: 1px`).
- **Do** ship every new color as a light/dark pair. Neither theme is the fallback.
- **Do** check a new color against the *worst* ground it touches, not the reading plane. `surface-2` is
  the binding case for anything quiet; a value tuned against white alone fails under the table headers.
- **Do** name one agent the same way in every layout. The path table and the agent list are read
  against each other, so an internal id in one and a name in the other makes the operator translate.
- **Do** size the content region with flex, not by subtracting a bar height.
- **Do** give a state change that is conveyed by color or motion a text equivalent an assistive
  technology can reach — and keep any live region out of the pushed fragments.

### Don't:

- **Don't** drift toward a consumer SaaS dashboard — no gradient hero cards, no `rounded-2xl`, no soft
  drop shadows, no illustrated empty states, no sparkline that decorates rather than informs. *(User-confirmed
  anti-reference.)*
- **Don't** add a `box-shadow`. There are zero in the system and that is the elevation strategy, not
  an oversight.
- **Don't** add a radius between `0` and fully round. No `4px`, no `8px`, no `12px`.
- **Don't** fill anything with the accent. It draws focus rings and the current item's edge; that is the
  complete list.
- **Don't** use a soft status color as a region or row background. Soft colors back pills and tags only.
- **Don't** add a third LED color. Warning is a tag, not a lamp.
- **Don't** add CSS transitions to hover, selection, or live swaps.
- **Don't** load a webfont, an icon font, an icon package, or any remote asset. PRODUCT.md makes
  self-containment binding — the proxy ships into networks where the fetch would simply fail.
- **Don't** introduce a `max-width` container or center the layout.
- **Don't** wrap table cells. Paths and target URLs truncate into the scroller; wrapping destroys the
  column scan.
- **Don't** let `ink-3` drift lighter. At `#626b77` / `#858f9d` it clears AA on `surface-2` by
  0.09–0.38, and it carries every timestamp, hostname, label, and dimmed column in the system.
- **Don't** expose an internal id in the interface. Ids address things in code; names identify them
  to operators.
- **Don't** remove a departed or failing row to keep the view tidy. Those rows are the product.
