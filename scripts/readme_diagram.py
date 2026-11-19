#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy>=2.5", "pillow>=13.0", "scipy>=1.18"]
# ///
#
# Make the README's two versions of the architecture diagram from docs/prometheus-proxy.png: a light one,
# docs/prometheus-proxy-light.png, and a dark one, docs/prometheus-proxy-dark.png, which the README's <picture>
# serves to dark themes. Used by `make readme-diagram`; run it again after editing the diagram.
#
# The diagram is black ink (arrows, labels, dotted callouts) on a transparent background, which vanishes on a dark
# page, with opaque pastel boxes, the firewall bar, and the Prometheus logo on a white square, which read fine on one.
# For the dark version each box is kept as it is, its own border included, and every other visible pixel takes GitHub's
# dark-mode text color and keeps its alpha, so the antialiasing survives.
#
# Each version is drawn on an opaque rounded card in its theme's page color. The browser picks the <picture> source
# from the color scheme the OS or browser prefers, which needn't match the page's theme, so a page can get the other
# version; on its card that version still reads, where bare ink the color of the page would vanish.

import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

DEFAULT_SOURCE = "docs/prometheus-proxy.png"
DEFAULT_LIGHT_TARGET = "docs/prometheus-proxy-light.png"
DEFAULT_DARK_TARGET = "docs/prometheus-proxy-dark.png"

FOREGROUND = (230, 237, 243)  # GitHub's dark-mode text color, #e6edf3
LIGHT_CARD = (255, 255, 255)  # GitHub's light-mode page color, #ffffff
DARK_CARD = (13, 17, 23)  # GitHub's dark-mode page color, #0d1117
CARD_PAD = 10  # added around the diagram's own margin
CARD_RADIUS = 12
SUPERSAMPLE = 4  # the card's corners are drawn this much larger, then reduced, to antialias them

FILL_LUMINANCE = 190  # an opaque pixel this light belongs to a box's fill
MIN_FILL_PIXELS = 400  # a box's fill, not a stray light pixel
BORDER_PAD = 3  # a box's border sits just outside its fill


def box_mask(rgba: np.ndarray) -> np.ndarray:
    """The pixels of each box: the bounding rectangle of every large opaque light region, padded to its border."""
    rgb, alpha = rgba[..., :3].astype(float), rgba[..., 3]
    luminance = 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]
    labels, _ = ndimage.label((alpha == 255) & (luminance > FILL_LUMINANCE), structure=np.ones((3, 3)))

    mask = np.zeros(alpha.shape, dtype=bool)
    height, width = alpha.shape
    for index, (rows, cols) in enumerate(ndimage.find_objects(labels), start=1):
        if (labels[rows, cols] == index).sum() < MIN_FILL_PIXELS:
            continue
        mask[
            max(rows.start - BORDER_PAD, 0) : min(rows.stop + BORDER_PAD, height),
            max(cols.start - BORDER_PAD, 0) : min(cols.stop + BORDER_PAD, width),
        ] = True
    return mask


def on_card(diagram: Image.Image, color: tuple[int, int, int]) -> Image.Image:
    """The diagram on an opaque card of the given color, with rounded corners outside which the image is transparent."""
    width, height = diagram.width + 2 * CARD_PAD, diagram.height + 2 * CARD_PAD
    corners = Image.new("L", (width * SUPERSAMPLE, height * SUPERSAMPLE), 0)
    ImageDraw.Draw(corners).rounded_rectangle(
        (0, 0, width * SUPERSAMPLE - 1, height * SUPERSAMPLE - 1), CARD_RADIUS * SUPERSAMPLE, fill=255
    )
    card = Image.new("RGBA", (width, height), color)
    card.putalpha(corners.resize((width, height), Image.Resampling.LANCZOS))
    card.alpha_composite(diagram, (CARD_PAD, CARD_PAD))
    return card


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SOURCE
    light_target = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_LIGHT_TARGET
    dark_target = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_DARK_TARGET

    with Image.open(source) as image:
        rgba = np.array(image.convert("RGBA"))

    on_card(Image.fromarray(rgba), LIGHT_CARD).save(light_target, optimize=True)
    print(f"Wrote {light_target}")

    ink = ~box_mask(rgba) & (rgba[..., 3] > 0)
    rgba[ink, :3] = FOREGROUND
    on_card(Image.fromarray(rgba), DARK_CARD).save(dark_target, optimize=True)
    print(f"Wrote {dark_target}: recolored {int(ink.sum())} pixels of {source}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
