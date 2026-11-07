#!/usr/bin/env python3
"""Generate the README hero banner for CameraRail.

Run with the bundled Python (Pillow + numpy):
    python docs/branding/generate.py

The left half of the banner is the opaque light page, so only the right half needs
a photo.  The photo is a 1.25:1 crop from the in-game shot (source x=600..1600,
y=140..940) placed at x=800..1600; that keeps much more of the scene vertically
than a 2.5:1 crop would, and the crop's right edge avoids the grass blocks at the
far right of the source.  Fonts: Bahnschrift, Noto Sans SC, Cascadia Mono; set
FONT_DIR when they live elsewhere.
"""

from __future__ import annotations

import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.environ.get("FONT_DIR", r"C:\Windows\Fonts")
W, H = 1600, 640
SS = 2

SHOT = os.path.join(HERE, "shots", "2026-11-07_13.45.59.png")
PANEL_X = 800
PANEL_CROP = (600, 140, 1600, 940)  # 1000x800 (1.25:1), right edge clear of grass

INK = (19, 26, 31)
INK_SOFT = (57, 69, 77)
INK_MUTED = (107, 119, 127)
INK_FAINT = (138, 149, 157)
TEAL = (28, 140, 163)
PAGE = (248, 249, 250)
PAGE_BOTTOM = (237, 240, 242)

FEATURES = "多种非线性曲线算法 · 长焦镜头 FOV 效果 · 游戏内 Dialog 全流程编辑"
CHIPS = ("Minecraft 26.1.2+", "Java 25", "PacketEvents 虚拟实体")


def load_font(name, size, variation=None):
    f = ImageFont.truetype(os.path.join(FONTS, name), int(round(size * SS)))
    if variation:
        try:
            f.set_variation_by_name(variation)
        except Exception:
            pass
    return f


def load_fonts():
    return dict(
        mono=load_font("CascadiaMono.ttf", 16, "Regular"),
        mono_small=load_font("CascadiaMono.ttf", 13, "Regular"),
        display=load_font("bahnschrift.ttf", 126, "Bold"),
        zh=load_font("NotoSansSC-VF.ttf", 31, "Medium"),
        zh_small=load_font("NotoSansSC-VF.ttf", 20, "Regular"),
        chip=load_font("NotoSansSC-VF.ttf", 16, "Regular"),
    )


def text_w(s, font, tracking=0.0):
    return sum(font.getlength(ch) for ch in s) + tracking * max(0, len(s) - 1)


def draw_tracked(draw, xy, s, font, fill, tracking=0.0):
    x, y = xy
    for ch in s:
        draw.text((x, y), ch, font=font, fill=fill)
        x += font.getlength(ch) + tracking
    return x


def add_grain(img, strength=3.0, seed=7):
    rng = np.random.default_rng(seed)
    arr = np.asarray(img.convert("RGBA"), dtype=np.int16)
    noise = rng.normal(0.0, strength, arr.shape[:2])[..., None]
    arr[..., :3] = np.clip(arr[..., :3] + noise, 0, 255)
    return Image.fromarray(arr.astype(np.uint8), "RGBA")


def page():
    w, h = W * SS, H * SS
    t = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    arr = np.array(PAGE, np.float32) * (1 - t) + np.array(PAGE_BOTTOM, np.float32) * t
    arr = np.repeat(arr[:, None, :], w, axis=1)
    img = Image.fromarray(arr.astype(np.uint8), "RGB").convert("RGBA")
    grid = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(grid)
    for x in range(0, W, 64):
        d.line([(x * SS, 0), (x * SS, h)], fill=(20, 26, 30, 5), width=1)
    for y in range(0, H, 64):
        d.line([(0, y * SS), (w, y * SS)], fill=(20, 26, 30, 5), width=1)
    img.alpha_composite(grid)
    return img


def photo_panel():
    panel = Image.open(SHOT).convert("RGB").crop(PANEL_CROP)
    panel = panel.resize(((W - PANEL_X) * SS, H * SS), Image.LANCZOS)
    a = np.asarray(panel, dtype=np.float32)
    a = (a - 128) * 1.02 + 128
    a = a * 1.06
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).convert("RGBA")


def blend_panel(img):
    """Page-coloured fade over the photo's left edge, so the seam disappears."""
    yy, xx = np.mgrid[0:H * SS, 0:W * SS].astype(np.float32)
    alpha = np.interp(xx, [PANEL_X * SS, 850 * SS, 960 * SS, 1080 * SS],
                      [255, 240, 120, 0]).astype(np.float32)
    fade = np.zeros((H * SS, W * SS, 4), np.uint8)
    fade[..., 0], fade[..., 1], fade[..., 2] = PAGE
    fade[..., 3] = np.clip(alpha, 0, 255).astype(np.uint8)
    img.alpha_composite(Image.fromarray(fade, "RGBA"))


def draw_copy(img, f):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    d.rectangle([104 * SS, 98 * SS, 132 * SS, 100 * SS], fill=TEAL + (230,))
    draw_tracked(d, (104 * SS, 112 * SS), "SPECTATOR CAMERA TRACKS   ·   PAPER / FOLIA",
                 f["mono"], TEAL, 3.0 * SS)

    cam_w = f["display"].getlength("Camera")
    d.text((104 * SS, 272 * SS), "Camera", font=f["display"], fill=INK, anchor="ls")
    d.text(((104 + cam_w / SS) * SS, 272 * SS), "Rail", font=f["display"], fill=TEAL, anchor="ls")

    draw_tracked(d, (104 * SS, 330 * SS), "旁观者镜头轨道与过场动画", f["zh"], INK_SOFT)
    draw_tracked(d, (104 * SS, 370 * SS), FEATURES, f["zh_small"], INK_MUTED)
    d.line([(104 * SS, 414 * SS), (760 * SS, 414 * SS)], fill=(213, 219, 223, 200), width=1)

    cx = 104 * SS
    for text in CHIPS:
        w = text_w(text, f["chip"], 0.4 * SS)
        top, bottom = f["chip"].getbbox("Ag")[1], f["chip"].getbbox("Ag")[3]
        h = (bottom - top) + 16 * SS
        box = [cx, 442 * SS, cx + w + 26 * SS, 442 * SS + h]
        d.rounded_rectangle(box, radius=5 * SS, fill=(255, 255, 255, 210),
                            outline=(214, 220, 225), width=1)
        draw_tracked(d, (cx + 13 * SS, 442 * SS + 8 * SS - top), text, f["chip"], INK_MUTED, 0.4 * SS)
        cx = box[2] + 10 * SS

    draw_tracked(d, (104 * SS, 578 * SS), "github.com/RIA-AED/CameraRail", f["mono_small"], INK_FAINT, 1.2 * SS)
    mark = "RIA-AED"
    mw = text_w(mark, f["mono_small"], 2.0 * SS)
    draw_tracked(d, (760 * SS - mw, 578 * SS), mark, f["mono_small"], INK_FAINT, 2.0 * SS)

    img.alpha_composite(layer)


def build(out):
    img = page()
    img.alpha_composite(photo_panel(), (PANEL_X * SS, 0))
    blend_panel(img)
    draw_copy(img, load_fonts())
    img = add_grain(img, 3.0)
    img.convert("RGB").resize((W, H), Image.LANCZOS).save(out, optimize=True)
    print("wrote", out)


def main():
    build(os.path.join(HERE, "hero.png"))


if __name__ == "__main__":
    main()
