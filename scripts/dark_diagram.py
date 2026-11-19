#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy>=2.5", "pillow>=13.0", "scipy>=1.18"]
# ///
#
# Make the dark-mode variant of the README's architecture diagram: docs/prometheus-proxy.png to
# docs/prometheus-proxy-dark.png, which the README's <picture> serves to dark themes. Used by
# `make readme-diagram-dark`; run it again after editing the diagram.
#
# The diagram is black ink (arrows, labels, dotted callouts) on a transparent background, which vanishes on a dark
# page, with opaque pastel boxes, the firewall bar, and the Prometheus logo on a white square, which read fine on one.
# Each box is kept as it is, its own border included; every other visible pixel takes GitHub's dark-mode text color
# and keeps its alpha, so the antialiasing survives.

import sys

import numpy as np
from PIL import Image
from scipy import ndimage

DEFAULT_SOURCE = "docs/prometheus-proxy.png"
DEFAULT_TARGET = "docs/prometheus-proxy-dark.png"

FOREGROUND = (230, 237, 243)  # GitHub's dark-mode text color, #e6edf3
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


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SOURCE
    target = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_TARGET

    with Image.open(source) as image:
        rgba = np.array(image.convert("RGBA"))

    ink = ~box_mask(rgba) & (rgba[..., 3] > 0)
    rgba[ink, :3] = FOREGROUND
    Image.fromarray(rgba).save(target, optimize=True)
    print(f"Wrote {target}: recolored {int(ink.sum())} pixels of {source}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
