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
//      diagram is an SVG, so it stays sharp at any size.
// Instant navigation swaps only the page's content, so the wrapper, and the listeners below, last from page to page.
// The styles are in stylesheets/diagrams.css, which every page lists: a style this added to the head would be gone
// after one.
(() => {
  // Each drawn diagram's shadow root, by its host, the div.mermaid on the page.
  const roots = new WeakMap()

  // The font the labels are set in, loaded once for every diagram on every page.
  let fonts
  const fontsLoaded = () =>
    (fonts ??= document.fonts
      .load(`1em ${getComputedStyle(document.body).fontFamily}`)
      .then(() => document.fonts.ready))

  const enlargeable = (root) => {
    roots.set(root.host, root)
    root.host.tabIndex = 0
    root.host.setAttribute("role", "button")
    root.host.setAttribute("aria-label", "Enlarge the diagram")
    root.host.title = "Click to enlarge"
  }

  const wrapRender = (mermaid) => {
    if (!mermaid || typeof mermaid.render !== "function" || mermaid.render.wrapped) return mermaid
    const render = mermaid.render.bind(mermaid)
    mermaid.render = async (...args) => {
      await fontsLoaded()
      const result = await render(...args)
      return {
        ...result,
        bindFunctions: (root) => {
          enlargeable(root)
          result.bindFunctions?.(root)
        },
      }
    }
    mermaid.render.wrapped = true
    return mermaid
  }

  // The theme loads Mermaid only on a page with a diagram, and the script it loads assigns window.mermaid, so catch the
  // assignment. Mermaid isn't defined yet when this runs, which the theme's loader checks for.
  let mermaid
  Object.defineProperty(window, "mermaid", {
    configurable: true,
    get: () => mermaid,
    set: (value) => {
      mermaid = wrapRender(value)
    },
  })

  // One dialog for every diagram, made on the first click. The diagram goes in a shadow root of its own, as the theme's
  // does, so the page's styles don't reach its labels, and its ids, which its own styles select, stay unique.
  let dialog
  const zoomDialog = () => {
    if (dialog) return dialog
    dialog = document.createElement("dialog")
    dialog.className = "diagram-zoom"
    dialog.setAttribute("aria-label", "Diagram")
    const close = document.createElement("button")
    close.type = "button"
    close.setAttribute("aria-label", "Close")
    close.textContent = "×"
    const view = document.createElement("div")
    view.className = "diagram-zoom-view"
    view.attachShadow({ mode: "open" })
    dialog.append(close, view)
    // Anywhere closes it, as the screenshots' lightbox does; Escape does too, as for any dialog.
    dialog.addEventListener("click", () => dialog.close())
    document.body.append(dialog)
    return dialog
  }

  const enlarge = (host) => {
    const svg = roots.get(host)?.querySelector("svg")
    if (!svg) return
    const zoom = zoomDialog()
    const copy = svg.cloneNode(true)
    // Mermaid caps the SVG at its natural width; here it fills the view instead, keeping its proportions.
    copy.removeAttribute("style")
    copy.setAttribute("width", "100%")
    copy.setAttribute("height", "100%")
    zoom.querySelector(".diagram-zoom-view").shadowRoot.replaceChildren(copy)
    zoom.showModal()
  }

  const diagramOf = (target) => target?.closest?.('.md-typeset div.mermaid[role="button"]')

  document.addEventListener("click", (event) => {
    const host = diagramOf(event.target)
    if (host) enlarge(host)
  })
  document.addEventListener("keydown", (event) => {
    const host = diagramOf(event.target)
    if (host && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault()
      enlarge(host)
    }
  })
})()
