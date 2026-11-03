"""Rezeptbuch-Slots (25x25) in Schwarz-Rot.

Aufruf: python3 make_extras.py <dark_inventory-sprites-ordner> <vorschau.png>
"""
import os
import sys
from PIL import Image, ImageDraw

inv_sprites, preview = sys.argv[1], sys.argv[2]

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

# Vorschau
canvas = Image.new("RGBA", (360, 100), (60, 60, 60, 255))
for i, s in enumerate(slots):
    big = s.resize((75, 75), Image.NEAREST)
    canvas.paste(big, (10 + i * 85, 10), big)
canvas.save(preview)
print("ok")
