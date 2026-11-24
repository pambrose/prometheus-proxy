// A code block's lines out of the Tab order.
//
// The highlighter gives each line of a code block an empty link (anchor_linenums in zensical.toml), the target that
// the Select button's line ranges link to. Each was a stop for Tab with nothing to show, so tabbing past a block took
// a press for each of its lines. The links still work as targets; Tab skips them.

/* global document$ */ // The theme's bundle defines it: it emits for the first page and each one instant navigation shows

document$.subscribe(() => {
  for (const anchor of document.querySelectorAll(".md-typeset pre a[id^='__codelineno-']")) {
    anchor.tabIndex = -1;
  }
});
