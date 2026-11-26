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

import io.prometheus.common.dashboard.DashboardCss
import io.prometheus.proxy.dashboard.ScreensScript.FRAME_HEIGHT
import io.prometheus.proxy.dashboard.ScreensScript.FRAME_WIDTH
import io.prometheus.proxy.dashboard.ScreensScript.MENU_WIDTH

/**
 * The screens page's own styles ([ScreensHtml]), after the stylesheet every dashboard shares ([DashboardCss]), whose
 * tokens, bar, tabs, and theme button it reuses rather than restyles: each screen's tab is a dashboard tab whose 7px
 * status dot stands where a view's icon would.
 */
internal object ScreensCss {
  /** The page's rules. Every colour is a token, and every dark rule goes through [DashboardCss.darkRule]. */
  val CSS =
    """
    /* The page fills the window: the bar, then the stage, which takes what's left, so a frame is as tall as it can be. */
    html, body.screens { height:100%; }
    body.screens { min-height:0; }
    /* The tabs take the bar's free width, one row, which the script fits them to (fitTabs), hiding those that don't
       fit; More holds them. */
    body.screens .nav { flex:1 1 0; flex-wrap:nowrap; min-width:0; }
    .nav a.screen.out { display:none; }
    /* Where even the kept tabs and More are wider than the row, the tabs shrink, their names cut, rather than spill. */
    .nav.tight a.screen { min-width:0; }
    /* A browser without popovers would draw the menu open over the screens, and More couldn't open it: those browsers
       get the wrapping row, every tab shown, as before More. */
    @supports not selector(:popover-open) {
      .menu, .more { display:none; }
      body.screens .nav { flex-wrap:wrap; }
      .nav a.screen.out { display:inline-flex; }
    }
    /* A screen's tab: its status dot in place of a view's icon, ink-3 until the first check, then the state's colour,
       which the tab's text equivalent says in words. Hover keeps the state's colour. */
    .nav a.screen { white-space:nowrap; }
    .nav a.screen::before { width:7px; height:7px; border-radius:50%; background:var(--ink-3);
      -webkit-mask:none; mask:none; }
    .nav a.screen.up::before { background:var(--ok); }
    .nav a.screen.down::before { background:var(--crit); }
    /* A long name is cut, so one tab can't take the row; its tooltip gives it whole. */
    .nav a.screen .name { min-width:0; max-width:24ch; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    /* The right pane's screen in Split: a quieter line than the current tab's accent. */
    .nav a.screen.second::after { content:""; position:absolute; left:0; right:0; bottom:-7px; height:2px;
      background:var(--ink-3); }
    /* More: the tabs' last item while a tab doesn't fit the row, on the tabs' line and as quiet as the bar's end, how
       many of the screens it holds aren't answering in the crit colour. */
    .more { flex:none; height:26px; margin:0 0 7px; padding:0 8px; font:inherit; color:var(--ink-2);
      white-space:nowrap; background:none; border:1px solid var(--line); border-radius:6px; cursor:pointer; }
    /* The bar's end's hover ground, on which the count's crit colour holds 4.5:1, as it doesn't on surface-3. */
    .more:hover { color:var(--ink); background-color:var(--surface-2); }
    .more:focus-visible { outline:2px solid var(--accent); outline-offset:1px; }
    .more[hidden] { display:none; }
    .more .down-count { color:var(--crit); }
    /* The More menu: a search, then every screen with its dot, under the button, where the script places it. */
    .menu { position:fixed; inset:auto; margin:0; padding:8px; box-sizing:border-box; overflow:auto;
      width:min(${MENU_WIDTH}px, calc(100vw - 32px)); max-height:min(60vh, 480px); color:var(--ink);
      background:var(--surface); border:1px solid var(--line); border-radius:6px; }
    .menu input { box-sizing:border-box; width:100%; font:inherit; color:var(--ink); background:var(--surface-2);
      border:1px solid var(--line); border-radius:3px; padding:5px 9px; }
    .menu input::placeholder { color:var(--ink-3); }
    .menu input:focus-visible { outline:2px solid var(--accent); outline-offset:1px; }
    .menu .entries { list-style:none; margin:6px 0 0; padding:0; }
    .menu a.entry { display:flex; align-items:center; gap:8px; padding:4px 8px; color:var(--ink-2);
      text-decoration:none; border-radius:3px; }
    .menu a.entry::before { content:""; flex:none; width:7px; height:7px; border-radius:50%; background:var(--ink-3); }
    .menu a.entry.up::before { background:var(--ok); }
    .menu a.entry.down::before { background:var(--crit); }
    .menu a.entry:hover, .menu a.entry:focus-visible { color:var(--ink); background-color:var(--surface-3); }
    .menu a.entry:focus-visible { outline:2px solid var(--accent); outline-offset:-2px; }
    .menu a.entry.on { color:var(--ink); font-weight:600; }
    /* The right pane's screen in Split, as its tab's quieter line. */
    .menu a.entry.second { box-shadow:inset 2px 0 0 var(--ink-3); }
    .menu a.entry .name { min-width:0; overflow-wrap:anywhere; }
    @media (forced-colors: active) {
      .nav a.screen.up::before { background:LinkText; }
      .nav a.screen.down::before { background:Canvas; box-shadow:inset 0 0 0 1.5px LinkText; }
      .nav a.screen.second::after { background:CanvasText; }
      .menu a.entry { forced-color-adjust:none; color:LinkText; background-color:Canvas; }
      .menu a.entry.on { color:CanvasText; }
      .menu a.entry.up::before { background:LinkText; }
      .menu a.entry.down::before { background:Canvas; box-shadow:inset 0 0 0 1.5px LinkText; }
      .menu a.entry.second { box-shadow:inset 2px 0 0 CanvasText; }
    }
    /* One, Split, or All: a segmented control, quiet as the bar's end is, the layout in force on the surface-2 ground. */
    .layouts { display:flex; flex:none; margin:0 0 6px auto; }
    .layouts button { height:26px; padding:0 10px; font:inherit; font-size:11.5px; color:var(--ink-2);
      background:none; border:1px solid var(--line); cursor:pointer; }
    .layouts button + button { border-left:0; }
    .layouts button:first-child { border-radius:3px 0 0 3px; }
    .layouts button:last-child { border-radius:0 3px 3px 0; }
    .layouts button:hover { color:var(--ink); background-color:var(--surface-2); }
    .layouts button[aria-pressed="true"] { color:var(--ink); background-color:var(--surface-2); font-weight:600; }
    .layouts button:focus-visible { outline:2px solid var(--accent); outline-offset:-2px; }
    @media (forced-colors: active) {
      .layouts button[aria-pressed="true"] { forced-color-adjust:none; color:HighlightText; background:Highlight; }
    }
    /* What the page says when a link went to the copy already open, and this one couldn't close itself. */
    .handoff { display:flex; flex-wrap:wrap; align-items:center; gap:10px; padding:9px 15px; color:var(--ink-2);
      background:var(--surface-2); border-bottom:1px solid var(--line); }
    .handoff button { height:26px; padding:0 10px; font:inherit; color:var(--ink); background:var(--surface);
      border:1px solid var(--line); border-radius:3px; cursor:pointer; }
    .handoff[hidden] { display:none; }
    /* The stage: one screen, two side by side, or every screen in a grid of tiles. */
    .stage { flex:1; min-height:0; display:grid; gap:10px; padding:10px; }
    .stage.one { grid-template-columns:1fr; }
    .stage.split { grid-template-columns:1fr 1fr; }
    /* All's rows are as tall as their tiles, and the grid scrolls: a pane is a scroll container (overflow:hidden), whose
       automatic minimum height is 0, so auto rows shrank to share the stage's height and drew each tile's bar alone. */
    .stage.all { grid-template-columns:repeat(auto-fill, minmax(min(100%, 380px), 1fr)); align-content:start;
      grid-auto-rows:max-content; overflow:auto; scrollbar-gutter:stable; }
    /* A pane is shown or hidden, never moved in the page, which would reload its frame. */
    .pane { display:none; flex-direction:column; min-width:0; min-height:0; background:var(--surface);
      border:1px solid var(--line); border-radius:3px; overflow:hidden; }
    .pane.shown { display:flex; }
    .stage.one .pane.shown, .stage.split .pane.shown { grid-row:1; }
    .pane-bar { display:flex; align-items:center; gap:9px; min-height:30px; padding:2px 10px;
      border-bottom:1px solid var(--line-soft); font-size:11.5px; color:var(--ink-2); }
    .pane-bar .name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:var(--ink); font-weight:600; }
    /* The screen picker, in Split only, where it changes the pane's screen. */
    .pane-bar select { display:none; max-width:100%; font:inherit; color:var(--ink); background:var(--surface-2);
      border:1px solid var(--line); border-radius:3px; padding:1px 4px; }
    .stage.split .pane-bar select { display:block; }
    .stage.split .pane-bar .name { display:none; }
    .pane-bar .open { margin-left:auto; flex:none; color:var(--ink-2); text-underline-offset:2px; }
    .pane-bar .open:hover { color:var(--ink); }
    .handoff button:focus-visible, .pane-bar select:focus-visible, .pane-bar .open:focus-visible {
      outline:2px solid var(--accent); outline-offset:1px; }
    /* In All, a tile's bar opens it in One. */
    .stage.all .pane-bar { cursor:pointer; }
    .stage.all .pane-bar:hover { background:var(--surface-2); }
    .view { position:relative; flex:1; min-height:0; overflow:hidden; background:var(--bg); }
    .view iframe { position:absolute; inset:0; width:100%; height:100%; border:0; background:var(--bg); }
    /* All: each screen lays out at ${FRAME_WIDTH}x$FRAME_HEIGHT, a desktop's layout, and is drawn at the tile's scale, --s, which
       the page's script measures. */
    .stage.all .view { flex:none; height:calc($FRAME_HEIGHT * var(--s, .3) * 1px); }
    .stage.all .view iframe { right:auto; bottom:auto; width:${FRAME_WIDTH}px; height:${FRAME_HEIGHT}px;
      transform:scale(var(--s, .3)); transform-origin:0 0; }
    /* A screen that isn't answering says so over its frame, which reloads once it answers again. Inside the view only:
       a tab's state class is down too. */
    .view .down { position:absolute; inset:0; display:none; place-content:center; gap:4px; padding:22px;
      text-align:center; color:var(--ink-2); background:var(--bg); }
    .pane.is-down .view .down { display:grid; }
    .view .down strong { color:var(--crit); font-weight:600; }
    /* Narrow, Split stacks its panes, each still at its own narrow layout. */
    @media (max-width:720px) {
      .stage.split { grid-template-columns:1fr; grid-template-rows:1fr 1fr; }
      .stage.split .pane.shown { grid-row:auto; grid-column:auto !important; }
      body.screens .nav { flex-basis:100%; }
    }
    @media (pointer: coarse) {
      .layouts button, .handoff button { height:46px; }
      .more, .menu input, .menu a.entry { min-height:46px; }
    }
    """.trimIndent()
}
