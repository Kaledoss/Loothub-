"""Schwarz-rote Textur für den Trichter (hopper.png, 256x256, 176x133 genutzt) – im Stil der übrigen Menüs.

Slot-Positionen wie Vanilla: 5 Trichter-Slots ab (44, 20), Inventar ab (8, 51), Schnellleiste bei (8, 109).
Aufruf: python3 make_hopper.py <ausgabeordner>
"""
import os
import sys
from PIL import Image, ImageDraw

outdir = sys.argv[1]
W, H = 176, 133
BG = (26, 16, 18, 255); HI = (92, 38, 44, 255); SH = (8, 4, 5, 255); OUT = (0, 0, 0, 255)
RED = (176, 38, 46, 255); SLOT = (40, 24, 28, 255); SLOT_DARK = (10, 5, 6, 255); SLOT_LIGHT = (78, 40, 46, 255)

img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
px = img.putpixel
d.rectangle([1, 1, W - 2, H - 2], fill=BG)
d.line([(2, 0), (W - 4, 0)], fill=OUT); d.line([(2, H - 1), (W - 3, H - 1)], fill=OUT)
d.line([(0, 2), (0, H - 4)], fill=OUT); d.line([(W - 1, 3), (W - 1, H - 3)], fill=OUT)
px((1, 1), OUT); px((W - 3, 1), OUT); px((W - 2, 2), OUT); px((1, H - 3), OUT); px((2, H - 2), OUT); px((W - 2, H - 2), OUT)
d.line([(2, 1), (W - 4, 1)], fill=HI); d.line([(1, 2), (1, H - 4)], fill=HI)
d.line([(2, 2), (W - 5, 2)], fill=HI); d.line([(2, 2), (2, H - 5)], fill=HI)
d.line([(3, H - 2), (W - 3, H - 2)], fill=SH); d.line([(W - 2, 3), (W - 2, H - 3)], fill=SH)
d.line([(4, H - 3), (W - 3, H - 3)], fill=SH); d.line([(W - 3, 4), (W - 3, H - 3)], fill=SH)
d.line([(3, 3), (W - 4, 3)], fill=RED)


def slot(x, y, size=18):
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=SLOT)
    d.line([(x, y), (x + size - 2, y)], fill=SLOT_DARK); d.line([(x, y), (x, y + size - 2)], fill=SLOT_DARK)
    d.line([(x + 1, y + size - 1), (x + size - 1, y + size - 1)], fill=SLOT_LIGHT)
    d.line([(x + size - 1, y + 1), (x + size - 1, y + size - 1)], fill=SLOT_LIGHT)


for i in range(5):
    slot(43 + 18 * i, 19)
for r in range(3):
    for j in range(9):
        slot(7 + 18 * j, 50 + 18 * r)
d.line([(7, 105), (168, 105)], fill=RED)
for j in range(9):
    slot(7 + 18 * j, 108)
img.save(os.path.join(outdir, "hopper.png"))
