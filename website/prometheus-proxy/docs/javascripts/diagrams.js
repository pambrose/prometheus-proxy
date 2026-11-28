// The site's Mermaid diagrams: drawn once the page's font has loaded, and opened full size with a click.
//
// The theme draws each diagram with mermaid.render into a closed shadow root as soon as Mermaid loads, which can be
// before the page's font has, on a first visit. Mermaid then sizes every label for the fallback font, and the real one,
// wider, overflows the boxes: a subgraph's title is cut short, and an edge's label runs into the box beside it. This
// script loads before the theme's bundle (overrides/main.html) and wraps mermaid.render, the moment the theme's loader
// defines Mermaid, so that it
//   1. waits for the page's font before drawing, and
//   2. hands the theme a bindFunctions that keeps the diagram's shadow root, which the theme calls with it once the
//      diagram is on the page, and marks the diagram as a button that shows it in a dialog filling the window. A
//      diagram is an SVG, so it stays sharp at any size, and
//   3. gives the diagram its text equivalent, the accTitle and accDescr its source declares, which Mermaid writes
//      into the SVG as its title and desc. The closed shadow root hides those from a screen reader, and from
//      aria-describedby, which can't reach into one, so the button takes the title in its name, and a visually hidden
//      paragraph after it, which the button names as its description, holds the description. And
//   4. says under it, on a touch screen, that a tap enlarges it.
// Instant navigation swaps only the page's content, so the wrapper, and the listeners below, last from page to page.
// The styles are in stylesheets/diagrams.css, which every page lists: a style this added to the head would be gone
// after one.
(() => {
  // Each drawn diagram's shadow root, by its host, the div.mermaid on the page.
  const roots = new WeakMap();

  // The font the labels are set in, loaded once for every diagram on every page.
  let fonts;
  const fontsLoaded = () =>
    (fonts ??= document.fonts
      .load(`1em ${getComputedStyle(document.body).fontFamily}`)
      .then(() => document.fonts.ready));

  // The text of the drawn SVG's title or desc, or "" for a diagram whose source declares none.
  const svgText = (root, tag) => root.querySelector(`svg > ${tag}`)?.textContent.trim() ?? "";

  // The visually hidden paragraph after the diagram that holds its description: the one a drawing before this one
  // left, or a new one.
  let descriptions = 0;
  const descriptionOf = (host) => {
    const next = host.nextElementSibling;
    if (next?.classList.contains("diagram-desc")) {
      return next;
    }
    const desc = document.createElement("p");
    descriptions += 1;
    desc.id = `diagram-desc-${descriptions}`;
    desc.className = "diagram-desc";
    host.after(desc);
    return desc;
  };

  const describe = (host, text) => {
    if (text) {
      const desc = descriptionOf(host);
      desc.textContent = text;
      host.setAttribute("aria-describedby", desc.id);
    }
  };

  // The line under the diagram that says it opens larger, which only a touch screen shows (diagrams.css), as it has no
  // zoom-in cursor to say so. It goes after the description, if there's one, and a drawing before this one left it.
  // The button's name says so already, so a screen reader skips it.
  const isA = (node, name) => node?.classList.contains(name) ?? false;

  const addHint = (node) => {
    const hint = document.createElement("p");
    hint.className = "diagram-hint";
    hint.setAttribute("aria-hidden", "true");
    hint.textContent = "Tap the diagram to enlarge it.";
    node.after(hint);
    return hint;
  };

  const hintAfter = (node) => {
    const next = node.nextElementSibling;
    if (isA(next, "diagram-desc")) {
      return hintAfter(next);
    }
    return isA(next, "diagram-hint") ? next : addHint(node);
  };

  // A sequence diagram's boxes, the two sides of the firewall, outlined as the flowcharts' zones are. Mermaid's own
  // style, in the shadow root, outlines them in its theme's purple, which no page style can reach, so the diagram gets
  // a style of its own, once.
  const boxStyle = () => {
    const style = document.createElement("style");
    style.className = "diagram-boxes";
    style.textContent = "g rect.rect { stroke: var(--md-default-fg-color--lighter) !important; }";
    return style;
  };

  const styleBoxes = (root) => {
    if (!root.querySelector("style.diagram-boxes")) {
      root.append(boxStyle());
    }
  };

  const enlargeable = (root) => {
    const host = root.host;
    styleBoxes(root);
    const title = svgText(root, "title");
    roots.set(host, root);
    host.dataset.title = title;
    host.tabIndex = 0;
    host.setAttribute("role", "button");
    host.setAttribute("aria-label", title ? `Enlarge the diagram: ${title}` : "Enlarge the diagram");
    host.title = "Click to enlarge";
    describe(host, svgText(root, "desc"));
    hintAfter(host);
  };

  // Whether [mermaid] has a render this script hasn't wrapped yet.
  const unwrapped = (mermaid) => typeof mermaid?.render === "function" && !mermaid.render.wrapped;

  const wrapRender = (mermaid) => {
    if (!unwrapped(mermaid)) {
      return mermaid;
    }
    const render = mermaid.render.bind(mermaid);
    mermaid.render = async (...args) => {
      await fontsLoaded();
      const result = await render(...args);
      const bindFunctions = result.bindFunctions;
      result.bindFunctions = (root) => {
        enlargeable(root);
        bindFunctions?.(root);
      };
      return result;
    };
    mermaid.render.wrapped = true;
    return mermaid;
  };

  // The theme loads Mermaid only on a page with a diagram, and the script it loads assigns window.mermaid, so catch the
  // assignment. Mermaid isn't defined yet when this runs, which the theme's loader checks for.
  let mermaid;
  Object.defineProperty(window, "mermaid", {
    configurable: true,
    get: () => mermaid,
    set: (value) => {
      mermaid = wrapRender(value);
    },
  });

  // One dialog for every diagram, made on the first click. The diagram goes in a shadow root of its own, as the theme's
  // does, so the page's styles don't reach its labels, and its ids, which its own styles select, stay unique.
  let dialog;
  const zoomDialog = () => {
    if (dialog) {
      return dialog;
    }
    dialog = document.createElement("dialog");
    dialog.className = "diagram-zoom";
    dialog.tabIndex = -1;
    dialog.setAttribute("aria-label", "Diagram");
    const close = document.createElement("button");
    close.type = "button";
    close.setAttribute("aria-label", "Close");
    close.textContent = "×";
    const view = document.createElement("div");
    view.className = "diagram-zoom-view";
    view.attachShadow({ mode: "open" });
    dialog.append(close, view);
    // Anywhere closes it, as the screenshots' lightbox does; Escape does too, as for any dialog.
    dialog.addEventListener("click", () => dialog.close());
    document.body.append(dialog);
    return dialog;
  };

  // Whether the view shows the whole diagram at least as large as the page does.
  const fitsWhole = (onPage, view) =>
    Math.min(view.clientWidth / onPage.width, view.clientHeight / onPage.height) >= 1;

  // The copy's size: filling the view, keeping its proportions, or, for a diagram so tall that the view could show it
  // whole only smaller than the page does, up to twice its size on the page, as wide as the view allows, through
  // which the dialog scrolls (diagrams.css), where it was drawn smaller than on the page.
  const sizeCopy = (copy, onPage, view) => {
    const fits = fitsWhole(onPage, view);
    copy.style.display = "block";
    copy.style.margin = "0 auto";
    copy.style.width = fits ? "100%" : `${Math.min(view.clientWidth, onPage.width * 2)}px`;
    copy.style.height = fits ? "100%" : "auto";
    return fits;
  };

  // A dialog that scrolls takes the focus itself, so the arrow keys and Page Down scroll it, where they went to the
  // page behind it from its Close button, which stays in view (position: fixed); Tab still reaches Close.
  const scrollable = (zoom, scrolls) => {
    zoom.classList.toggle("scrolls", scrolls);
    if (scrolls) {
      zoom.focus();
    }
  };

  const enlarge = (host) => {
    const svg = roots.get(host)?.querySelector("svg");
    if (!svg) {
      return;
    }
    const onPage = svg.getBoundingClientRect();
    const zoom = zoomDialog();
    const view = zoom.querySelector(".diagram-zoom-view");
    zoom.classList.remove("scrolls");
    zoom.setAttribute("aria-label", host.dataset.title || "Diagram");
    zoom.showModal();
    // Mermaid caps the SVG at its natural width, which the copy sets aside for the size the view gives it.
    const copy = svg.cloneNode(true);
    copy.removeAttribute("style");
    const fits = sizeCopy(copy, onPage, view);
    view.shadowRoot.replaceChildren(copy, boxStyle());
    scrollable(zoom, !fits);
  };

  const diagramOf = (target) => target?.closest?.(".md-typeset div.mermaid[role=button]");

  // The keys that press a button.
  const presses = (event) => event.key === "Enter" || event.key === " ";

  document.addEventListener("click", (event) => {
    const host = diagramOf(event.target);
    if (host) {
      enlarge(host);
    }
  });
  document.addEventListener("keydown", (event) => {
    const host = diagramOf(event.target);
    if (host && presses(event)) {
      event.preventDefault();
      enlarge(host);
    }
  });
})();
