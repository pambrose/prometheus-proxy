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

/**
 * Every dashboard's stylesheet, inlined into each page's `<style>` (see [DashboardPage.dashboardHead]): the color
 * tokens, light and dark, and the rules of the design system `docs/DESIGN.md` records, with the components they
 * implement and the contrast each color pairing was measured at. The login page ([LoginHtml]) starts from the tokens.
 *
 * Apart from the markup because it answers to a different contract.
 */
internal object DashboardCss {
  // The page's theme, as the bar's button sets it on <html> (DashboardPage.themeButton): absent for auto, which follows
  // the system's, or light or dark.
  private const val AUTO_DARK = """:root:not([data-theme="light"])"""
  private const val SET_DARK = """:root[data-theme="dark"]"""

  /**
   * [declarations] for [selector] in the dark theme: under the system's dark preference unless the page is set to
   * light, and whenever it is set to dark. Each dark rule is written once, here, so the two can't drift apart.
   */
  fun darkRule(
    selector: String,
    declarations: String,
  ): String {
    fun scoped(root: String) = if (selector == ":root") root else "$root $selector"
    return "@media (prefers-color-scheme: dark) {\n  ${scoped(AUTO_DARK)} { $declarations }\n}\n" +
      "${scoped(SET_DARK)} { $declarations }"
  }

  private const val DARK_TOKENS =
    "--bg:#0d1015; --surface:#151a21; --surface-2:#1c222b; --surface-3:#242c37; " +
      "--line:#2b333f; --line-soft:#212934; --ink:#e7ebf1; --ink-2:#a4aebc; --ink-3:#858f9d; " +
      "--accent:#ff7043; --ok:#3fb950; --ok-soft:#12251a; --warn:#d29922; --warn-soft:#2a2110; " +
      "--crit:#f85149; --crit-soft:#2d1512;"

  /**
   * The color tokens, light and dark, the dark ones for the system's dark preference and a page set to dark alike, and
   * the color scheme the browser draws its own widgets in, such as scrollbars, which follows the same choice.
   */
  val TOKENS =
    """
    /* Every quiet colour is set so its *worst* pairing still clears 4.5:1, not just its pairing with
       --surface. --ink-3 is the binding case: it lands on --surface-2 under the table and section
       headers, which is a full step closer than the reading plane. A hovered list row's --surface-3 is
       closer still, so the row's --ink-3 line steps up to --ink-2 there (.md-row:hover .host). */
    :root { color-scheme:light dark;
      --bg:#f2f5f8; --surface:#fff; --surface-2:#e9edf2; --surface-3:#dde3ea;
      --line:#cfd7e0; --line-soft:#e3e9ef; --ink:#131820; --ink-2:#48525f; --ink-3:#626b77;
      --accent:#c8461f; --ok:#1a7a42; --ok-soft:#e3f4ea; --warn:#8a5d08; --warn-soft:#f9f0da;
      --crit:#c02a20; --crit-soft:#fbe6e4;
      --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;
    }
    :root[data-theme="light"] { color-scheme:light; }
    :root[data-theme="dark"] { color-scheme:dark; }
    """.trimIndent() + "\n" + darkRule(":root", DARK_TOKENS)

  // The rules, after the tokens. Declared before CSS, which an object initializes in order.
  private val RULES =
    """
    /* Visible to assistive tech, absent from the render. Used for the connection announcer and the
       table caption, both of which duplicate something the layout already says visually. */
    .sr-only { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden;
      clip-path:inset(50%); white-space:nowrap; border:0; }
    * { box-sizing:border-box; margin:0; padding:0; }
    /* Column flex rather than a `calc(100vh - 44px)` on the content: the bar's height is set by its own
       content and changes with the touch-target rules below, so any hard-coded number is wrong on some
       viewport. This makes the content region exactly the remaining space, whatever the bar measures. */
    body { background:var(--bg); color:var(--ink); font-family:var(--mono); font-size:12.5px; line-height:1.5;
           display:flex; flex-direction:column; min-height:100vh; }
    body > main { flex:1; }
    /* The current tab's line lies on the bar's bottom rule, so the bar has no bottom padding and sets its items on its
       floor; the brand and the status carry their own, which puts their text on the tabs' line. One row above 720px:
       the status wraps inside itself, so it never pushes the tabs off the rule. */
    .bar { display:flex; align-items:flex-end; gap:10px; padding:8px 15px 0; border-bottom:1px solid var(--line);
           background:var(--surface); }
    /* The logo's small-size cut beside the name, centered on its line, which the mark is shorter than, so the bar's
       height and the name's place on the tabs' line are as they were without it. */
    .brand { display:flex; align-items:center; gap:7px; font-weight:600; flex:none; padding-bottom:10px; }
    .brand img { flex:none; }
    /* The dashboard's name, cut with an ellipsis past DashboardLogo.BRAND_MAX_CHARS characters of its monospaced type,
       which a ch measures. */
    .brand-name { max-width:${DashboardLogo.BRAND_MAX_CHARS}ch; overflow:hidden; text-overflow:ellipsis;
      white-space:nowrap; }
    /* The bar's end: the theme button and Log out, quieter than the tabs, in one box that stretches each to the
       taller's height, so Log out is as tall as the theme button however tall a coarse pointer makes it. */
    .bar-end { display:flex; align-items:stretch; gap:10px; flex:none; margin-bottom:6px; }
    /* The bar's end's two icon buttons, the theme button and Log out, each in a quiet border, 30px wide, its glyph a
       CSS mask in currentColor as the table's icon buttons' are. The theme button is 26px tall, a 24px target, the
       minimum size, inside its border, and Log out takes its height through the bar's end. */
    .logout { display:flex; }
    .theme, .logout button { display:inline-flex; align-items:center; justify-content:center; width:30px; padding:0;
      color:var(--ink-2); background:none; border:1px solid var(--line); border-radius:3px; cursor:pointer; }
    .theme:hover, .logout button:hover { color:var(--ink); background-color:var(--surface-2); }
    .theme:focus-visible, .logout button:focus-visible { outline:2px solid var(--accent); outline-offset:1px; }
    /* Log out: an exit icon, a door with an arrow leaving it. */
    .logout button {
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'><path d='M6.5 2.75H3.75a1 1 0 0 0-1 1v8.5a1 1 0 0 0 1 1H6.5'/><path d='M10.25 5l3 3-3 3M13.25 8H6.75'/></svg>"); }
    /* The theme button: the theme in force's icon, light's sun, dark's moon, or auto's half-filled circle, from
       <html data-theme>, which the head sets before the page draws, so the icon is right before the script runs. */
    .theme { height:26px;
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.5'><circle cx='8' cy='8' r='5.75'/><path d='M8 2.25a5.75 5.75 0 0 0 0 11.5z' fill='black'/></svg>"); }
    :root[data-theme="light"] .theme {
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.5' stroke-linecap='round'><circle cx='8' cy='8' r='2.75'/><path d='M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1.06 1.06M11.54 11.54l1.06 1.06M3.4 12.6l1.06-1.06M11.54 4.46l1.06-1.06'/></svg>"); }
    :root[data-theme="dark"] .theme {
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.5' stroke-linejoin='round'><path d='M13.5 9.75A5.75 5.75 0 1 1 6.25 2.5a4.75 4.75 0 0 0 7.25 7.25z'/></svg>"); }
    .bar-right { display:flex; align-items:center; justify-content:flex-end; flex-wrap:wrap; gap:2px 14px;
      margin-left:auto; min-width:0; padding-bottom:10px; color:var(--ink-3); font-size:11.5px; }
    /* Each stat stays whole; the row wraps between them rather than mid-phrase, which at 390px was
       splitting "0 agents" across two lines. */
    .bar-right > span { white-space:nowrap; }
    .bar-right .ok { color:var(--ok); } .bar-right .warn { color:var(--warn); } .bar-right .crit { color:var(--crit); }
    .conn { display:inline-flex; align-items:center; gap:6px; color:var(--ok); }
    .conn .conn-down { display:none; }
    .beacon { width:7px; height:7px; border-radius:50%; background:var(--ok); animation:pulse 2.4s ease-out infinite; }
    @keyframes pulse { 0%{opacity:1} 70%{opacity:.4} 100%{opacity:1} }
    /* Disconnected: green calm becomes red blink, and the label swaps to name the effort. The faster,
       harder blink reads as "trying" rather than the solid dot of something simply dead. Driven off the
       body class so a frozen status bar still flips, and it wins over the healthy rules by specificity. */
    body.ws-down .conn { color:var(--crit); }
    body.ws-down .conn .conn-live { display:none; }
    body.ws-down .conn .conn-down { display:inline; }
    body.ws-down .beacon { background:var(--crit); animation:blink 1s steps(2, start) infinite; }
    @keyframes blink { 0%{opacity:1} 50%{opacity:.25} 100%{opacity:1} }
    @media (prefers-reduced-motion: reduce) { .beacon, body.ws-down .beacon { animation:none } }
    .md { display:grid; grid-template-columns:262px 1fr; }
    .md-list { border-right:1px solid var(--line); background:var(--surface-2); }
    .md-head, .section { display:flex; justify-content:space-between; padding:9px 15px; font-size:10px;
      letter-spacing:.09em; text-transform:uppercase; color:var(--ink-3); font-weight:600;
      border-bottom:1px solid var(--line); background:var(--surface-2); }
    /* The current row's edge is 2px, as the current tab's is, and the left padding a pixel wider than the right, so
       with the edge a row's text sits 18px in. */
    .md-row { display:block; width:100%; text-align:left; font:inherit; cursor:pointer; background:none;
      border:0; border-bottom:1px solid var(--line-soft); border-left:2px solid transparent;
      padding:10px 15px 10px 16px; color:var(--ink); }
    .md-row:hover { background:var(--surface-3); }
    /* Inside the row, which meets the rows around it, and inside a tab, around the ground its hover lays. */
    .md-row:focus-visible, .nav a:focus-visible { outline:2px solid var(--accent); outline-offset:-2px; }
    .md-row[aria-current="true"] { background:var(--surface); border-left-color:var(--accent); }
    /* 11.5px, matching .bar-right and .hero-meta: this is the same job -- quiet supporting metadata --
       and a separate 11px step for it was a size the hierarchy never used. */
    .md-row .host { display:block; color:var(--ink-3); font-size:11.5px; margin-top:2px; }
    /* --ink-3 on a hovered row's --surface-3 is 4.18:1 light and 4.30:1 dark; --ink-2 is 6.14:1 and 6.28:1. */
    .md-row:hover .host { color:var(--ink-2); }
    .md-row .tag, .kv .k .tag { margin-left:7px; }
    .md-detail { background:var(--surface); }
    .hero { padding:14px 18px; border-bottom:1px solid var(--line); }
    .hero-title { font-size:15px; font-weight:640; display:flex; align-items:center; gap:9px; }
    .hero-meta { color:var(--ink-3); font-size:11.5px; margin-top:5px; }
    .hero-failover { font-size:11.5px; margin-top:5px; display:flex; align-items:center; gap:8px; }
    .tag.warn { background:var(--warn-soft); color:var(--warn); }
    .tag { font-size:10px; background:var(--surface-3); color:var(--ink-2); padding:2px 7px; border-radius:20px; }
    .kv { display:flex; gap:10px; padding:7px 18px; border-bottom:1px solid var(--line-soft); align-items:center; }
    /* The same step the path table's rows take on hover, so a row is as easy to follow across in both views. */
    .kv:hover { background:var(--surface-2); }
    .kv .k { min-width:220px; } .kv .v { color:var(--ink-3); } .kv .t { color:var(--ink-3); min-width:64px; }
    .led { display:inline-block; width:7px; height:7px; border-radius:50%; margin-right:7px; }
    .led.ok { background:var(--ok); } .led.crit { background:var(--crit); }
    .pill { font-size:10.5px; font-weight:600; padding:2px 7px; border-radius:20px; }
    .pill.ok { background:var(--ok-soft); color:var(--ok); }
    .pill.crit { background:var(--crit-soft); color:var(--crit); }
    .empty { color:var(--ink-3); padding:14px 15px; }
    .empty.pad { padding:22px 18px; }
    .empty.gone { color:var(--crit); }
    /* The tabs, a borderless link per view, its icon before its name, as a code host's are. The nav reaches 1px below
       the bar into its rule, and each tab stands 7px above the nav's floor, so its name sits on the brand's and the
       status's line, and the current tab's 2px accent line, along the floor, covers the rule beneath it: the tab's
       edge, as the selected row's left border is the row's. A tab is 26px tall, the bar's end's height, and hover lays
       the surface-3 ground under it, its corners rounded 6px, so the ground is a handle, not a container. Its icon
       is ink-3, a step quieter than its name, which is ink-2 at rest and ink when hovered or current, at weight 600
       when current. */
    .nav { display:flex; align-items:flex-end; gap:6px; flex:none; margin:0 0 -1px 18px; }
    .nav a { position:relative; display:inline-flex; align-items:center; gap:7px; height:26px; margin-bottom:7px;
      padding:0 8px; color:var(--ink-2); text-decoration:none; border-radius:6px; }
    .nav a::before { content:""; flex:none; width:14px; height:14px; background:var(--ink-3);
      -webkit-mask:var(--icon) center/contain no-repeat; mask:var(--icon) center/contain no-repeat; }
    .nav a:hover { background-color:var(--surface-3); color:var(--ink); }
    .nav a:hover::before { background:var(--ink-2); }
    .nav a.on { color:var(--ink); font-weight:600; }
    .nav a.on::after { content:""; position:absolute; left:0; right:0; bottom:-7px; height:2px;
      background:var(--accent); }
    /* The tabs keep their own paint in forced colours, in system colours, so the current one is told apart by more
       than its weight: a link at rest, its icon too, and the current tab in CanvasText over a Highlight line. */
    @media (forced-colors: active) {
      .nav a { forced-color-adjust:none; color:LinkText; background-color:Canvas; }
      .nav a::before, .nav a:hover::before { background:LinkText; }
      .nav a:hover { background-color:Canvas; text-decoration:underline; }
      .nav a.on { color:CanvasText; }
      .nav a.on::before { background:CanvasText; }
      .nav a.on::after { background:Highlight; }
      .nav a:focus-visible { outline-color:Highlight; }
    }
    /* The focus ring of every control but a list row and a tab, whose rings are inset (above). */
    .copy:focus-visible, .scrape-now:focus-visible, .close:focus-visible, .guide-toggle:focus-visible,
    .filters input:focus-visible, .ptable td.links a:focus-visible {
      outline:2px solid var(--accent); outline-offset:1px; }
    /* The table is the one region that can outgrow a narrow viewport, so it scrolls inside its own
       container rather than letting the page body scroll sideways. */
    .scroller { overflow-x:auto; background:var(--surface); }
    .ptable { width:100%; border-collapse:collapse; white-space:nowrap; }
    .ptable th { text-align:left; font-size:10px; letter-spacing:.09em; text-transform:uppercase;
      color:var(--ink-3); font-weight:600; padding:9px 15px; background:var(--surface-2);
      border-bottom:1px solid var(--line); position:sticky; top:0; }
    .ptable td { padding:8px 15px; border-bottom:1px solid var(--line-soft); }
    .ptable .num { text-align:right; font-variant-numeric:tabular-nums; }
    /* A column of marks, the Links column, centered under its header, which stands over the marks rather than at their
       left. */
    .ptable .center { text-align:center; }
    .ptable .dim, .scrape-result .dim { color:var(--ink-3); }
    .ptable tr:hover td { background:var(--surface-2); }
    .ptable tr.departed td:first-child { color:var(--crit); }
    /* The Activity table's Detail column, which holds a sentence, so it wraps where the rest of the row doesn't. */
    .ptable td.wrap { white-space:normal; min-width:280px; }
    /* The History and Links columns keep their content's width. A table in a window wider than it needs shares the
       spare width among its columns, in proportion to their content, and the strip's width gave History a large share,
       which opened a gap between the percentage and the links, centered in theirs; the other columns take it instead. */
    .ptable td.history, .ptable td.links { width:1%; }
    /* A path's links into other tools: the dimmed column's ink-3, underlined so they read as links, and ink on hover,
       as the nav's are. */
    .ptable td.links a { color:inherit; text-underline-offset:2px; }
    .ptable td.links a:hover { color:var(--ink); }
    /* A link drawn as its tool's brand mark (LinkIcon) rather than its name, which its title and aria-label carry: a
       24px target, as the icon buttons are, its 16px mark painted in the brand's colour (--brand) through the mark as
       a mask (--icon). The page's second stylesheet (linkIconCss) sets both per icon, so a push carries only the class.
       An icon stands 4px from its neighbours, where two names take a separator. */
    .ptable td.links a.icon { display:inline-flex; align-items:center; justify-content:center;
      width:24px; height:24px; margin:-4px 0; vertical-align:middle; border-radius:20px; text-decoration:none; }
    .ptable td.links a.icon + a, .ptable td.links a + a.icon { margin-left:4px; }
    .ptable td.links a.icon::before { content:""; width:16px; height:16px; background:var(--brand);
      -webkit-mask:var(--icon) center/contain no-repeat; mask:var(--icon) center/contain no-repeat; }
    /* Hover rings the target rather than filling it as the icon buttons do: on surface-3 the light theme's marks fall
       below 3:1 (2.90:1 and 2.75:1), where on surface-2, the hovered row's ground, they hold it. */
    .ptable td.links a.icon:hover { box-shadow:inset 0 0 0 1px var(--ink-3); }
    /* Forced colours replace the mark's paint, as they do the icon buttons', which would leave the link blank; LinkText
       keeps it visible and reading as a link. */
    @media (forced-colors: active) {
      .ptable td.links a.icon::before { forced-color-adjust:none; background:LinkText; } }
    /* An event's tag opens its cell, so it takes none of the gap that sets a tag apart from a name before it. */
    .ptable td.event .tag, .sr-head span.tag { margin-left:0; }
    /* The icon buttons: copy beside a path or target URL, scrape-now beside a path, and close on the scrape result.
       Their icons are CSS masks painted in currentColor rather than inline SVG: the table is pushed whole every
       refresh, and an SVG in every row would grow every frame. 24px meets the minimum target size, and the negative
       margins keep one from making the row taller. */
    .copy, .scrape-now, .close { display:inline-flex; align-items:center; justify-content:center; width:24px;
      height:24px; margin:-4px 0 -4px 6px; padding:0; vertical-align:middle; border:0; border-radius:20px;
      background:none; color:var(--ink-3); cursor:pointer; }
    .copy {
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.5' stroke-linejoin='round'><rect x='5.25' y='5.25' width='8.5' height='8.5' rx='1.5'/><path d='M10.75 5.25v-2a1 1 0 0 0-1-1h-6.5a1 1 0 0 0-1 1v6.5a1 1 0 0 0 1 1h2'/></svg>"); }
    .scrape-now {
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'><path d='M13 8a5 5 0 1 1-1.46-3.54'/><path d='M12.5 2.25v2.5H10'/></svg>"); }
    .copy::before, .scrape-now::before, .close::before, .guide-toggle::before, .logout button::before,
      .theme::before { content:"";
      width:13px; height:13px; background:currentColor; -webkit-mask:var(--icon) center/contain no-repeat;
      mask:var(--icon) center/contain no-repeat; }
    .copy:hover, .scrape-now:hover, .close:hover { background:var(--surface-3); color:var(--ink); }
    .copy.copied { color:var(--ok);
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.75' stroke-linecap='round' stroke-linejoin='round'><path d='M3 8.5l3.25 3.25L13 5'/></svg>"); }
    .copy.copy-failed { color:var(--crit); }
    /* A cross, for a copy that failed and for closing the scrape result. */
    .copy.copy-failed, .close {
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.75' stroke-linecap='round'><path d='M4 4l8 8M12 4l-8 8'/></svg>"); }
    /* Forced colours replace every background, the mask's paint included, so the icon would vanish. */
    @media (forced-colors: active) {
      .copy::before, .scrape-now::before, .close::before, .guide-toggle::before, .logout button::before,
      .theme::before {
        forced-color-adjust:none; background:ButtonText; } }
    /* A scrape-now's result, above the Paths view's table or list: a heading line, then the start of the body. The
       body is the one block of a target's own text on the page, in a bounded box that scrolls rather than pushing the
       table away. */
    .scrape-result { padding:10px 15px; background:var(--surface); border-bottom:1px solid var(--line); }
    .sr-head { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
    .sr-head .close { margin-left:auto; }
    .sr-path { font-weight:600; }
    .sr-failure { margin-top:7px; color:var(--crit); }
    .scrape-result pre { margin:9px 0 0; padding:8px 10px; max-height:240px; overflow:auto; font:inherit;
      font-size:11.5px; color:var(--ink-2); background:var(--surface-2); border:1px solid var(--line-soft);
      white-space:pre; }
    .sr-note { margin-top:5px; font-size:11.5px; }
    .tag.crit { background:var(--crit-soft); color:var(--crit); margin-left:7px; }
    /* The Paths and Activity views' search, on the recessed plane like the table header below it. The input holds
       content, so it is square; it takes focus as the rest of the page does. A checked box is neutral ink: the accent
       is kept for focus and the current row. */
    .filters { display:flex; flex-wrap:wrap; align-items:center; gap:16px; padding:9px 15px;
      background:var(--surface-2); border-bottom:1px solid var(--line); }
    .filters input[type=search] { font:inherit; color:var(--ink); background:var(--surface);
      border:1px solid var(--line); border-radius:0; padding:5px 9px; width:min(440px, 100%); }
    .filters input[type=search]::placeholder { color:var(--ink-3); }
    .filters label { display:inline-flex; align-items:center; gap:7px; color:var(--ink-2); cursor:pointer; }
    .filters input[type=checkbox] { accent-color:var(--ink-2); margin:0; }
    /* The column guide's button, at the search's right end: a question mark and its name, in the checkbox label's ink-2
       and ink on hover or while open, the accent kept for its focus ring. Named, since a bare "?" doesn't say what it
       opens. */
    .guide-toggle { display:inline-flex; align-items:center; gap:7px; min-height:24px; margin-left:auto; padding:0;
      font:inherit; color:var(--ink-2); background:none; border:0; cursor:pointer;
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'><circle cx='8' cy='8' r='6.25'/><path d='M6.3 6.2a1.75 1.75 0 1 1 2.45 1.6c-.45.2-.75.55-.75 1.05v.4'/><circle cx='8' cy='11.4' r='.9' fill='black' stroke='none'/></svg>"); }
    .guide-toggle:hover, .guide-toggle[aria-expanded="true"] { color:var(--ink); }
    /* The column guide, under the search: what each of the table's columns holds. On the reading plane, as the scrape
       result is, and a strip that pushes the table down rather than a layer over it, which would need a shadow and
       cover the columns it explains. Each name is in the header's own Label type, so it reads as the header it
       explains. */
    .column-guide { padding:10px 15px; background:var(--surface); border-bottom:1px solid var(--line); }
    .cg-head { display:flex; align-items:center; justify-content:space-between; }
    .cg-head span, .column-guide dt { font-size:10px; letter-spacing:.09em; text-transform:uppercase;
      color:var(--ink-3); font-weight:600; }
    .column-guide dl { display:grid; grid-template-columns:max-content 1fr; align-items:baseline; gap:6px 18px;
      margin-top:7px; }
    .column-guide dd { color:var(--ink-2); }
    .match-count { padding:7px 15px; color:var(--ink-3); font-size:11.5px; border-bottom:1px solid var(--line-soft); }
    /* A path's recent scrapes, oldest first. Each <i> is a run of like results and paints a 4px dot every 6px, so a
       healthy strip is one element however long it is. Round, as everything that reports state is. A full strip, at
       the default 20 scrapes, is 120px, and until a history fills, its first run is .slot (not .empty, the empty
       state's class): dots in the rule's colour for the scrapes still to come, so every row's strip is that wide. */
    .hist { display:inline-flex; vertical-align:middle; margin:-2px 7px 0 0; }
    .hist i { display:block; height:6px; width:calc(var(--n) * 6px);
      background:radial-gradient(circle at 3px 3px, var(--dot) 2px, transparent 2.5px) 0 0 / 6px 6px repeat-x; }
    .hist i.ok { --dot:var(--ok); } .hist i.crit { --dot:var(--crit); } .hist i.slot { --dot:var(--line); }
    /* Forced colours drop backgrounds, the dots included. */
    @media (forced-colors: active) { .hist i { forced-color-adjust:none; } }
    /* Narrow, the tabs take a row of their own, the bar's last, so they still stand on its rule; the brand and the
       status share the rows above, off the tabs' line, so they keep only the room their own rows need. */
    @media (max-width:720px) {
      .md { grid-template-columns:1fr; } .md-list { border-right:0; border-bottom:1px solid var(--line); }
      .bar { flex-wrap:wrap; row-gap:4px; }
      .brand, .bar-right { padding-bottom:6px; }
      .nav { order:1; flex-basis:100%; margin-left:0; }
    }
    /* Touch, not width: a 34px tab is a comfortable mouse target and a poor thumb one, and the two do
       not correlate with viewport size -- a tablet is wide and touch-driven. The dense bar is kept for
       pointer devices rather than spending vertical space everyone pays for. */
    @media (pointer: coarse) {
      .nav a { height:44px; }
      .theme { width:46px; height:46px; }
      .logout button { width:46px; }
    }
    """.trimIndent()

  // Each tab's icon (TabIcon), which its class gives it. Declared before CSS, as RULES is.
  private val TAB_ICONS =
    TabIcon.entries.joinToString("\n") { """.nav a.${it.cssClass} { --icon:url("data:image/svg+xml,${it.svg}"); }""" }

  /** The tokens, the rules, and the tabs' icons. */
  val CSS = TOKENS + "\n" + RULES + "\n" + TAB_ICONS
}
