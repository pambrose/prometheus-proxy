// The screenshots' lightbox: each opens as large as the window allows, but never larger than its pixels, and as a
// dialog that a keyboard and a screen reader can use.
//
// GLightbox, which the theme opens an image in, takes an image's pixels for CSS pixels: it fits the image to the window
// up to that size, and a click on the image zooms it to that size. The demo screenshots are taken at three times the
// pixel density of the window they show (demos/*/bin/screenshots.sh), so on a Retina screen, at two device pixels to
// the CSS pixel, the lightbox stretched every image pixel over as many as two device pixels on a large window, and over
// exactly two in the zoom, which blurred the text. A srcset declaring each image at twice the density halves the size
// GLightbox takes it for, so a Retina screen draws an image pixel at most a device pixel wide, in the window and in the
// zoom, which shows the window at one and a half times its size; a screen at one device pixel to the CSS pixel, which
// takes the image's own size, does too.
//
// GLightbox reads a slide's srcset and sizes from its link's data attributes, and the theme has it read them again on
// every click on a link, in a listener of its own on the link. This one, on the document, which sees the click first,
// adds them to every image link the page has, so the slides the lightbox's arrows step to have them too. Instant
// navigation swaps only the page's content, so it lasts from page to page.

// The link whose screenshot the lightbox opened, which gets the focus back when it closes
let opener = null;

document.addEventListener(
  "click",
  (event) => {
    const link = event.target instanceof Element ? event.target.closest("a.glightbox") : null;
    if (!link) {
      return;
    }
    opener = link;
    for (const image of document.querySelectorAll("a.glightbox[data-type=image]:not([data-srcset])")) {
      image.dataset.srcset = `${image.href} 2x`;
      // GLightbox sets the srcset only with sizes, which a density descriptor ignores.
      image.dataset.sizes = "100vw";
    }
  },
  { capture: true },
);

// GLightbox marks its box a dialog, but it left the page behind it readable to a screen reader, named neither the box
// nor its image, whose caption it shows apart from it, and left the focus on the page, so Tab and a screen reader went
// on through the page behind, and closing it lost the reader's place. While it's open, the box is modal and named,
// each slide's image is named by its caption, the focus starts on its Close button, and Tab and Shift-Tab step through
// the buttons that work and wrap, where GLightbox's own order stepped onto a disabled arrow and then off the box. Once
// it closes, the focus is back on the screenshot's link.

// A slide's image, unnamed as GLightbox leaves it, named by the caption the slide shows below it
const nameImage = (slide) => {
  const image = slide.querySelector("img:not([alt]), img[alt='']");
  const caption = slide.querySelector(".gslide-title");
  if (image && caption) {
    image.alt = caption.textContent.trim();
  }
};

const nameImages = (box) => box.querySelectorAll(".gslide").forEach(nameImage);

const opened = (box) => {
  box.setAttribute("aria-modal", "true");
  box.setAttribute("aria-label", "Screenshot");
  nameImages(box);
  // The slides the arrows step to are added as they're shown
  new MutationObserver(() => nameImages(box)).observe(box, { childList: true, subtree: true });
  const close = box.querySelector(".gclose");
  if (close) {
    close.focus();
  }
};

const closed = () => {
  if (opener && opener.isConnected) {
    opener.focus();
  }
  opener = null;
};

// The box's next working button after the focused one, or before it going back, wrapping around; the first if the
// focus is elsewhere. With no working button to step to, the focus stays where it is, rather than the handler throwing.
const stepFocus = (box, back) => {
  const buttons = [...box.querySelectorAll(".gbtn:not(.disabled)")];
  if (buttons.length === 0) {
    return;
  }
  const at = buttons.indexOf(document.activeElement);
  const next = at < 0 ? 0 : (at + (back ? -1 : 1) + buttons.length) % buttons.length;
  buttons.at(next).focus();
};

// Run before GLightbox's own Tab handling, which it replaces
document.addEventListener(
  "keydown",
  (event) => {
    const box = document.querySelector(".glightbox-container");
    if (event.key !== "Tab" || !box) {
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    stepFocus(box, event.shiftKey);
  },
  { capture: true },
);

const isLightbox = (node) => node instanceof Element && node.classList.contains("glightbox-container");

new MutationObserver((records) => {
  for (const record of records) {
    [...record.addedNodes].filter(isLightbox).forEach(opened);
    if ([...record.removedNodes].some(isLightbox)) {
      closed();
    }
  }
}).observe(document.body, { childList: true });
