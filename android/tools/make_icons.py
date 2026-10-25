#!/usr/bin/env python3
"""Generate the Relay Dashboard launcher icons and the Android TV banner.

Run from the android/ folder:  python3 tools/make_icons.py
Requires Pillow. Output:
  res/mipmap-*/ic_launcher.png   launcher icon, phone + TV
  res/drawable-xhdpi/banner.png  Android TV home-screen banner (320x180)
  tools/icon-512.png             512 px icon for listings / README previews

The banner needs a real font to carry the app name; DejaVu is used when it is
installed, otherwise the banner is drawn without text.
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


FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
]


def load_font(size):
    from PIL import ImageFont
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                pass
    return None


def render_banner(width=320, height=180):
    """Android TV banner: 320x180, name on the left, bolt on the right."""
    grad = Image.new("RGB", (width, height))
    gd = ImageDraw.Draw(grad)
    for y in range(height):
        t = y / max(1, height - 1)
        gd.line([(0, y), (width, y)],
                fill=tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)))
    banner = grad.convert("RGBA")

    # soften the corners very slightly - TVs crop a few pixels
    mark = glyph(int(height * 0.52))
    banner.alpha_composite(mark, (int(width - mark.width - 14), int((height - mark.height) / 2)))

    title_font = load_font(30)
    sub_font = load_font(15)
    d = ImageDraw.Draw(banner)
    if title_font:
        d.text((20, 52), "Relay", font=title_font, fill=(255, 255, 255, 255))
        d.text((20, 88), "Dashboard", font=title_font, fill=(255, 255, 255, 235))
    if sub_font:
        d.text((21, 128), "console \u00b7 TV host", font=sub_font, fill=(255, 255, 255, 190))
    else:
        d.rectangle([20, 60, 160, 120], fill=(255, 255, 255, 230))
    return banner


def main():
    banner_dir = os.path.join(ANDROID, "res", "drawable-xhdpi")
    os.makedirs(banner_dir, exist_ok=True)
    banner_path = os.path.join(banner_dir, "banner.png")
    render_banner().save(banner_path)
    print("wrote", os.path.relpath(banner_path, ANDROID), "320x180")

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
