"""Makes the animated stickers for the Homebase chat (static/stickers/*.gif) from emoji glyphs: a bounce, a pulse, a shake, a spin.
Run it again after changing STICKERS. Needs Pillow and the Windows 'Segoe UI Emoji' font."""
import math
import os

from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static", "stickers")
FONT = r"C:\Windows\Fonts\seguiemj.ttf"
SIZE, FRAMES, MS = 160, 16, 66

# name -> (emoji, how it moves). Every move is a function of t in [0, 1) that loops cleanly.
STICKERS = {
    "heart": ("\U0001F496", "pulse"), "party": ("\U0001F389", "wiggle"), "thumbs-up": ("\U0001F44D", "bounce"), "laugh": ("\U0001F602", "shake"),
    "wow": ("\U0001F62E", "zoom"), "thanks": ("\U0001F64F", "nod"), "sleepy": ("\U0001F634", "float"), "fire": ("\U0001F525", "flicker"),
    "clap": ("\U0001F44F", "clap"), "rocket": ("\U0001F680", "fly"), "pizza": ("\U0001F355", "spin"), "wave": ("\U0001F44B", "wave"),
    "yum": ("\U0001F60B", "bounce"), "cool": ("\U0001F60E", "slide"), "cake": ("\U0001F382", "wiggle"), "home": ("\U0001F3E0", "pulse"),
}


def glyph(emoji):
    font = ImageFont.truetype(FONT, 109)
    layer = Image.new("RGBA", (140, 140), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((70, 70), emoji, font=font, embedded_color=True, anchor="mm")
    box = layer.getbbox()
    return layer.crop(box) if box else layer


def pose(kind, t):
    """(scale, rotation in degrees, dx, dy) at time t."""
    s = math.sin(2 * math.pi * t)
    c = math.cos(2 * math.pi * t)
    if kind == "pulse":
        return 1 + 0.12 * s, 0, 0, 0
    if kind == "wiggle":
        return 1 + 0.04 * c, 12 * s, 0, -4 * abs(s)
    if kind == "bounce":
        return 1 + 0.05 * (1 - abs(s)), 0, 0, -22 * abs(s)
    if kind == "shake":
        return 1.0, 9 * math.sin(4 * math.pi * t), 3 * math.sin(4 * math.pi * t), 0
    if kind == "zoom":
        return 1 + 0.22 * (0.5 - 0.5 * c), 0, 0, 0
    if kind == "nod":
        return 1.0, -12 + 18 * (0.5 - 0.5 * c), 0, 4 * s
    if kind == "float":
        return 1 + 0.03 * s, 4 * s, 0, -10 * s
    if kind == "flicker":
        return 1 + 0.05 * math.sin(6 * math.pi * t), 3 * math.sin(4 * math.pi * t), 0, -3 * abs(math.sin(4 * math.pi * t))
    if kind == "clap":
        return 1 + 0.16 * abs(math.sin(4 * math.pi * t)), 0, 0, 0
    if kind == "fly":
        return 1.0, 0, 8 * s, -8 * s
    if kind == "spin":
        return 1.0, 360 * t, 0, 0
    if kind == "wave":
        return 1.0, 22 * math.sin(4 * math.pi * t), 0, 0
    if kind == "slide":
        return 1.0, 0, 14 * s, 0
    return 1.0, 0, 0, 0


def make(name, emoji, kind):
    base = glyph(emoji)
    scale0 = 104 / max(base.size)                                      # fit the glyph in the middle of the frame, leaving room to move
    base = base.resize((max(1, int(base.width * scale0)), max(1, int(base.height * scale0))), Image.LANCZOS)
    frames = []
    for i in range(FRAMES):
        sc, rot, dx, dy = pose(kind, i / FRAMES)
        g = base.resize((max(1, int(base.width * sc)), max(1, int(base.height * sc))), Image.LANCZOS)
        if rot:
            g = g.rotate(rot, resample=Image.BICUBIC, expand=True)
        frame = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        frame.alpha_composite(g, (int(SIZE / 2 - g.width / 2 + dx), int(SIZE / 2 - g.height / 2 + dy)))
        frames.append(frame)
    path = os.path.join(OUT, name + ".gif")
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=MS, loop=0, disposal=2, optimize=False)
    return os.path.getsize(path)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for n, (e, k) in STICKERS.items():
        print(f"{n:10s} {make(n, e, k) // 1024:4d} KB")
