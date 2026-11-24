// The reference tables' identifiers break only where they divide: after a dot, an underscore, or a slash.
//
// A table's flags, config keys, and environment variables (--agent_port, proxy.admin.debugEnabled, PROXY_CONFIG) are
// long single words, and the CLI Reference's five columns of them don't fit the page's column. The theme broke them at
// any letter to fit, as --agent_por|t or proxy.admin.enabl|ed; held on one line instead, they squeezed each table's
// description column to a word wide. This splits each word of a table's code after each dot, underscore, and slash
// that a letter or digit follows, holds each piece on one line (stylesheets/extra.css), and puts a break opportunity, a
// <wbr>, between the pieces, so a key wraps as proxy.admin. / debugEnabled, and a flag never after its dashes, as
// --conf, - / c. The spaces between words still break. A <wbr> adds no character, so copied text is unchanged.

/* global document$ */ // The theme's bundle defines it: it emits for the first page and each one instant navigation shows

// A word of code's pieces, split after each dot, underscore, and slash that a letter or digit follows
const pieces = (word) => word.split(/(?<=[._/])(?=[A-Za-z0-9])/);

// A word of code as its pieces, each held on one line, with a break opportunity between each two
const breakableWord = (word) => {
  const fragment = document.createDocumentFragment();
  pieces(word).forEach((piece, i) => {
    if (i > 0) {
      fragment.append(document.createElement("wbr"));
    }
    const span = document.createElement("span");
    span.className = "md-table-key";
    span.textContent = piece;
    fragment.append(span);
  });
  return fragment;
};

// A text node of code, its words made breakable, and the spaces between them left as they are
const makeBreakable = (text) => {
  const fragment = document.createDocumentFragment();
  for (const word of text.data.split(/(\s+)/)) {
    fragment.append(word.trim() ? breakableWord(word) : word);
  }
  text.replaceWith(fragment);
};

// An element's text nodes, all found before any is replaced
const textNodes = (element) => {
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) {
    nodes.push(walker.currentNode);
  }
  return nodes;
};

document$.subscribe(() => {
  for (const code of document.querySelectorAll(".md-typeset table:not([class]) code:not([data-breaks])")) {
    code.dataset.breaks = "";
    textNodes(code).forEach(makeBreakable);
  }
});
