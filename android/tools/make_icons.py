#!/usr/bin/env python3
"""Generate the Relay Dashboard launcher icons.

Run from the android/ folder:  python3 tools/make_icons.py
Requires Pillow. Output: res/mipmap-*/ic_launcher.png (+ a 512px icon.png
in tools/ for store listings / README previews).
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)

SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

TOP = (139, 92, 246)     # purple  #8B5CF6
BOTTOM = (34, 211, 238)  # cyan    #22D3EE


def rounded_mask(size, radius_ratio=0.22):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    r = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return mask


def glyph(size):
    """White 'relay' mark: a bolt made of three stacked chevrons."""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    s = size / 48.0  # design on a 48x48 grid

    bolt = [
        (27.5, 6.0), (14.0, 26.0), (22.0, 26.0), (19.5, 42.0),
        (34.0, 21.0), (25.5, 21.0), (29.0, 6.0),
    ]
    d.polygon([(x * s, y * s) for x, y in bolt], fill=(255, 255, 255, 255))

    # small node dots either side, suggesting a relayed signal
    r = 2.1 * s
    for cx, cy in ((9.0, 14.5), (39.0, 33.5)):
        d.ellipse([cx * s - r, cy * s - r, cx * s + r, cy * s + r],
                  fill=(255, 255, 255, 200))
    return layer


def render(size):
    grad = Image.new("RGB", (size, size))
    gd = ImageDraw.Draw(grad)
    for y in range(size):
        t = y / max(1, size - 1)
        gd.line(
            [(0, y), (size, y)],
            fill=tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)),
        )
    icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    icon.paste(grad, (0, 0), rounded_mask(size))
    icon = Image.alpha_composite(icon, glyph(size))
    return icon


def main():
    for density, px in SIZES.items():
        out_dir = os.path.join(ANDROID, "res", "mipmap-" + density)
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, "ic_launcher.png")
        render(px).save(path)
        print("wrote", os.path.relpath(path, ANDROID), px, "px")
    render(512).save(os.path.join(HERE, "icon-512.png"))
    print("wrote tools/icon-512.png 512 px")


if __name__ == "__main__":
    main()
