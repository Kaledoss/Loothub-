"""Rezeptbuch-Slots (25x25) in Schwarz-Rot und Bossleisten-Füllung als Farbverlauf (182x5).

Aufruf: python3 make_extras.py <dark_inventory-sprites-ordner> <progress_bars-sprites-ordner> <vorschau.png>
"""
import os
import sys
from PIL import Image, ImageDraw

inv_sprites, bar_sprites, preview = sys.argv[1], sys.argv[2], sys.argv[3]

# ---------------------------------------------------------------- Rezeptbuch-Slots
RED = (200, 44, 54, 255); RED_DIM = (110, 28, 34, 255)
BG = (40, 24, 28, 255); BG_NO = (52, 20, 24, 255); SH = (12, 6, 7, 255)
os.makedirs(os.path.join(inv_sprites, "recipe_book"), exist_ok=True)
slots = []


def slot(name, edge, fill, many):
    img = Image.new("RGBA", (25, 25), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if many:  # zweite Karte dahinter, leicht versetzt
        d.rectangle([3, 0, 24, 21], fill=SH, outline=edge)
        d.rectangle([0, 3, 21, 24], fill=fill, outline=edge)
        d.line([(1, 23), (20, 23)], fill=SH); d.line([(20, 4), (20, 23)], fill=SH)
    else:
        d.rectangle([0, 0, 24, 24], fill=fill, outline=edge)
        d.line([(1, 23), (23, 23)], fill=SH); d.line([(23, 1), (23, 23)], fill=SH)
    img.save(os.path.join(inv_sprites, "recipe_book", name + ".png"))
    slots.append(img)


slot("slot_craftable", RED, BG, False)
slot("slot_many_craftable", RED, BG, True)
slot("slot_uncraftable", RED_DIM, BG_NO, False)
slot("slot_many_uncraftable", RED_DIM, BG_NO, True)

# ---------------------------------------------------------------- Bossleisten
# Verlauf über die Breite: Rot → Orange (47 %) → Gelb (bis 94 %) → Grün (letzte 6 %)
W, H = 182, 5
STOPS = [(0.0, (224, 48, 48)), (0.47, (255, 138, 42)), (0.935, (255, 224, 64))]
GREEN = (80, 216, 80)


def color_at(t):
    if t >= 0.94:
        return GREEN
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(round(c0[i] + (c1[i] - c0[i]) * k) for i in range(3))
    return STOPS[-1][1]


bar = Image.new("RGBA", (W, H))
for x in range(W):
    c = color_at(x / (W - 1))
    for y in range(H):
        f = 1.25 if y == 0 else (0.6 if y == H - 1 else 1.0)
        bar.putpixel((x, y), tuple(min(255, round(v * f)) for v in c) + (255,))
bar_dir = os.path.join(bar_sprites, "boss_bar")
os.makedirs(bar_dir, exist_ok=True)
for col in ("pink", "blue", "red", "green", "yellow", "purple", "white"):
    bar.save(os.path.join(bar_dir, col + "_progress.png"))

# Vorschau
canvas = Image.new("RGBA", (W * 3 + 20, 140), (60, 60, 60, 255))
for i, s in enumerate(slots):
    big = s.resize((75, 75), Image.NEAREST)
    canvas.paste(big, (10 + i * 85, 10), big)
b3 = bar.resize((W * 3, H * 3), Image.NEAREST)
canvas.paste(b3, (10, 110))
canvas.save(preview)
print("ok")
