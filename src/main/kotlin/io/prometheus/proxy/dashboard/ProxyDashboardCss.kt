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

/**
 * The dashboard's stylesheet, inlined into the page's `<style>` by [ProxyDashboardHtml].
 *
 * Apart from the markup because it answers to a different contract: `docs/DESIGN.md` records the tokens, type
 * roles, and components these rules implement, and the contrast each color pairing was measured at.
 */
internal object ProxyDashboardCss {
  val CSS =
    """
    /* Every quiet colour is set so its *worst* pairing still clears 4.5:1, not just its pairing with
       --surface. --ink-3 is the binding case: it lands on --surface-2 under the table and section
       headers, which is a full step closer than the reading plane. */
    :root {
      --bg:#f2f5f8; --surface:#fff; --surface-2:#e9edf2; --surface-3:#dde3ea;
      --line:#cfd7e0; --line-soft:#e3e9ef; --ink:#131820; --ink-2:#48525f; --ink-3:#626b77;
      --accent:#c8461f; --ok:#1a7a42; --ok-soft:#e3f4ea; --warn:#8a5d08; --warn-soft:#f9f0da;
      --crit:#c02a20; --crit-soft:#fbe6e4;
      --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg:#0d1015; --surface:#151a21; --surface-2:#1c222b; --surface-3:#242c37;
        --line:#2b333f; --line-soft:#212934; --ink:#e7ebf1; --ink-2:#a4aebc; --ink-3:#858f9d;
        --accent:#ff7043; --ok:#3fb950; --ok-soft:#12251a; --warn:#d29922; --warn-soft:#2a2110;
        --crit:#f85149; --crit-soft:#2d1512;
      }
    }
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
    /* The tabs stand on the bar's bottom rule, so the bar has no bottom padding and sets its items on its floor; the
       brand and the status carry their own, which puts their text on the tabs' line. One row above 720px: the status
       wraps inside itself, so it never pushes the tabs off the rule. */
    .bar { display:flex; align-items:flex-end; gap:10px; padding:8px 15px 0; border-bottom:1px solid var(--line);
           background:var(--surface); }
    .brand { font-weight:600; flex:none; padding-bottom:6px; }
    .bar-right { display:flex; align-items:center; justify-content:flex-end; flex-wrap:wrap; gap:2px 14px;
      margin-left:auto; min-width:0; padding-bottom:6px; color:var(--ink-3); font-size:11.5px; }
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
    .md-row { display:block; width:100%; text-align:left; font:inherit; cursor:pointer; background:none;
      border:0; border-bottom:1px solid var(--line-soft); border-left:3px solid transparent;
      padding:10px 15px; color:var(--ink); }
    .md-row:hover { background:var(--surface-3); }
    /* Inside the row, and inside a tab, which meets the strip below it. */
    .md-row:focus-visible, .nav a:focus-visible { outline:2px solid var(--accent); outline-offset:-2px; }
    .md-row[aria-current="true"] { background:var(--surface); border-left-color:var(--accent); }
    /* 11.5px, matching .bar-right and .hero-meta: this is the same job -- quiet supporting metadata --
       and a separate 11px step for it was a size the hierarchy never used. */
    .md-row .host { display:block; color:var(--ink-3); font-size:11.5px; margin-top:2px; }
    .md-row .tag { margin-left:7px; }
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
    /* Folder tabs. The nav reaches 1px below the bar into its rule, so a tab's bottom edge lies on the rule: a tab
       at rest leaves it showing through its transparent border, and the current tab paints it the strip's colour, so
       the tab opens into the strip below it. Every view's first strip is --surface-2 (the search, or the agent list
       under the Agents tab). Rectangles, as everything that holds content is, with the current item's accent edge
       on top, as the current agent row has it on the left. */
    .nav { display:flex; align-items:flex-end; gap:2px; flex:none; margin:0 0 -1px 18px; }
    .nav a { color:var(--ink-3); text-decoration:none; padding:5px 14px 6px; border:1px solid transparent;
      border-top-width:3px; background-clip:padding-box; }
    .nav a:hover { background-color:var(--surface-2); color:var(--ink); }
    .nav a.on { background-color:var(--surface-2); color:var(--ink); font-weight:600; border-color:var(--line);
      border-top-color:var(--accent); border-bottom-color:var(--surface-2); }
    /* Forced colours paint every border, the transparent ones too, which boxed every tab alike and left the current
       one told apart by its weight alone. Here the tabs keep their own paint, in system colours: a link at rest, and
       the current tab outlined, its top edge in Highlight and its bottom open onto the content. */
    @media (forced-colors: active) {
      .nav a { forced-color-adjust:none; color:LinkText; background-color:Canvas; border-color:transparent; }
      .nav a:hover { background-color:Canvas; text-decoration:underline; }
      .nav a.on { color:CanvasText; background-color:Canvas; border-color:CanvasText; border-top-color:Highlight;
        border-bottom-color:Canvas; }
      .nav a:focus-visible { outline-color:Highlight; }
    }
    /* The focus ring of every control but a list row and a tab, whose rings are inset (above). */
    .copy:focus-visible, .scrape-now:focus-visible, .close:focus-visible,
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
    .ptable .dim, .scrape-result .dim { color:var(--ink-3); }
    .ptable tr:hover td { background:var(--surface-2); }
    .ptable tr.departed td:first-child { color:var(--crit); }
    /* The Activity table's Detail column, which holds a sentence, so it wraps where the rest of the row doesn't. */
    .ptable td.wrap { white-space:normal; min-width:280px; }
    /* A path's links into other tools: the dimmed column's ink-3, underlined so they read as links, and ink on hover,
       as the nav's are. */
    .ptable td.links a { color:inherit; text-underline-offset:2px; }
    .ptable td.links a:hover { color:var(--ink); }
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
    .copy::before, .scrape-now::before, .close::before { content:""; width:13px; height:13px; background:currentColor;
      -webkit-mask:var(--icon) center/contain no-repeat; mask:var(--icon) center/contain no-repeat; }
    .copy:hover, .scrape-now:hover, .close:hover { background:var(--surface-3); color:var(--ink); }
    .copy.copied { color:var(--ok);
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.75' stroke-linecap='round' stroke-linejoin='round'><path d='M3 8.5l3.25 3.25L13 5'/></svg>"); }
    .copy.copy-failed { color:var(--crit); }
    /* A cross, for a copy that failed and for closing the scrape result. */
    .copy.copy-failed, .close {
      --icon:url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='black' stroke-width='1.75' stroke-linecap='round'><path d='M4 4l8 8M12 4l-8 8'/></svg>"); }
    /* Forced colours replace every background, the mask's paint included, so the icon would vanish. */
    @media (forced-colors: active) {
      .copy::before, .scrape-now::before, .close::before { forced-color-adjust:none; background:ButtonText; } }
    /* A scrape-now's result, above the Paths table: a heading line, then the start of the body. The body is the one
       block of a target's own text on the page, in a bounded box that scrolls rather than pushing the table away. */
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
    /* The Paths view's search, on the recessed plane like the table header below it. The input holds content, so it
       is square; it takes focus as the rest of the page does. A checked box is neutral ink: the accent is kept for
       focus and the current row. */
    .filters { display:flex; flex-wrap:wrap; align-items:center; gap:16px; padding:9px 15px;
      background:var(--surface-2); border-bottom:1px solid var(--line); }
    .filters input[type=search] { font:inherit; color:var(--ink); background:var(--surface);
      border:1px solid var(--line); border-radius:0; padding:5px 9px; width:min(440px, 100%); }
    .filters input[type=search]::placeholder { color:var(--ink-3); }
    .filters label { display:inline-flex; align-items:center; gap:7px; color:var(--ink-2); cursor:pointer; }
    .filters input[type=checkbox] { accent-color:var(--ink-2); margin:0; }
    .match-count { padding:7px 15px; color:var(--ink-3); font-size:11.5px; border-bottom:1px solid var(--line-soft); }
    /* A path's recent scrapes, oldest first. Each <i> is a run of like results and paints a 4px dot every 6px, so a
       healthy strip is one element however long it is. Round, as everything that reports state is. A full strip, at
       the default 20 scrapes, is 120px. */
    .hist { display:inline-flex; vertical-align:middle; margin:-2px 7px 0 0; }
    .hist i { display:block; height:6px; width:calc(var(--n) * 6px);
      background:radial-gradient(circle at 3px 3px, var(--dot) 2px, transparent 2.5px) 0 0 / 6px 6px repeat-x; }
    .hist i.ok { --dot:var(--ok); } .hist i.crit { --dot:var(--crit); }
    /* Forced colours drop backgrounds, the dots included. */
    @media (forced-colors: active) { .hist i { forced-color-adjust:none; } }
    /* Narrow, the tabs take a row of their own, the bar's last, so they still stand on its rule and open into the strip
       below; the brand and the status share the rows above. */
    @media (max-width:720px) {
      .md { grid-template-columns:1fr; } .md-list { border-right:0; border-bottom:1px solid var(--line); }
      .bar { flex-wrap:wrap; row-gap:4px; }
      .nav { order:1; flex-basis:100%; margin-left:0; }
    }
    /* Touch, not width: a 34px tab is a comfortable mouse target and a poor thumb one, and the two do
       not correlate with viewport size -- a tablet is wide and touch-driven. The dense bar is kept for
       pointer devices rather than spending vertical space everyone pays for. */
    @media (pointer: coarse) {
      .nav a { min-height:44px; display:inline-flex; align-items:center; padding-top:0; padding-bottom:0; }
    }
    """.trimIndent()
}
