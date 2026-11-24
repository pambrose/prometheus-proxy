// The screenshots' lightbox: each opens as large as the window allows, but never larger than its pixels.
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
document.addEventListener(
  "click",
  (event) => {
    if (!(event.target instanceof Element) || !event.target.closest("a.glightbox")) return;
    for (const link of document.querySelectorAll('a.glightbox[data-type="image"]:not([data-srcset])')) {
      link.dataset.srcset = `${link.href} 2x`;
      // GLightbox sets the srcset only with sizes, which a density descriptor ignores.
      link.dataset.sizes = "100vw";
    }
  },
  { capture: true },
);
