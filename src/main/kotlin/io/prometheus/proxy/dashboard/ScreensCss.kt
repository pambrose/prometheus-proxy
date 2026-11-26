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
    /* The tabs shrink and wrap rather than push the layout buttons off the bar. */
    body.screens .nav { flex:0 1 auto; flex-wrap:wrap; min-width:0; }
    /* A screen's tab: its status dot in place of a view's icon, ink-3 until the first check, then the state's colour,
       which the tab's text equivalent says in words. Hover keeps the state's colour. */
    .nav a.screen { white-space:nowrap; }
    .nav a.screen::before { width:7px; height:7px; border-radius:50%; background:var(--ink-3);
      -webkit-mask:none; mask:none; }
    .nav a.screen.up::before { background:var(--ok); }
    .nav a.screen.down::before { background:var(--crit); }
    /* The right pane's screen in Split: a quieter line than the current tab's accent. */
    .nav a.screen.second::after { content:""; position:absolute; left:0; right:0; bottom:-7px; height:2px;
      background:var(--ink-3); }
    @media (forced-colors: active) {
      .nav a.screen.up::before { background:LinkText; }
      .nav a.screen.down::before { background:Canvas; box-shadow:inset 0 0 0 1.5px LinkText; }
      .nav a.screen.second::after { background:CanvasText; }
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
    .stage.all { grid-template-columns:repeat(auto-fill, minmax(min(100%, 380px), 1fr)); align-content:start;
      overflow:auto; scrollbar-gutter:stable; }
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
    }
    """.trimIndent()
}
