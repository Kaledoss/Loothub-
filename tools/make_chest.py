"""Schwarz-rote Kisten-Textur generic_54.png (Kisten und Server-Menüs mit 1–6 Reihen).

Layout wie Vanilla: oben Kopf (17 px) + 6 Reihen (y 0–124), unten ab y=126 das Spieler-Inventar (96 px).
Minecraft schneidet den oberen Teil je nach Reihenzahl ab und setzt den unteren direkt darunter.
"""
import sys
from PIL import Image, ImageDraw
out, preview = sys.argv[1], sys.argv[2]
W = 176
img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
BG = (26, 16, 18, 255); HI = (92, 38, 44, 255); SH = (8, 4, 5, 255); OUT = (0, 0, 0, 255); RED = (176, 38, 46, 255)
SLOT = (40, 24, 28, 255); SLOT_DARK = (10, 5, 6, 255); SLOT_LIGHT = (78, 40, 46, 255)

def section(y0, h, top_round, bottom_round):
    d.rectangle([1, y0, W - 2, y0 + h - 1], fill=BG)
    d.line([(0, y0 + (2 if top_round else 0)), (0, y0 + h - 1 - (2 if bottom_round else 0))], fill=OUT)
    d.line([(W - 1, y0 + (2 if top_round else 0)), (W - 1, y0 + h - 1 - (2 if bottom_round else 0))], fill=OUT)
    d.line([(1, y0 + (2 if top_round else 0)), (1, y0 + h - 1 - (2 if bottom_round else 0))], fill=HI)
    d.line([(W - 2, y0 + (2 if top_round else 0)), (W - 2, y0 + h - 1 - (2 if bottom_round else 0))], fill=SH)
    d.line([(2, y0 + (1 if top_round else 0)), (2, y0 + h - 1 - (1 if bottom_round else 0))], fill=HI)
    d.line([(W - 3, y0 + (1 if top_round else 0)), (W - 3, y0 + h - 1 - (1 if bottom_round else 0))], fill=SH)
    if top_round:
        d.line([(2, y0), (W - 3, y0)], fill=OUT); img.putpixel((1, y0 + 1), OUT); img.putpixel((W - 2, y0 + 1), OUT)
        d.line([(2, y0 + 1), (W - 3, y0 + 1)], fill=HI)
        d.line([(3, y0 + 2), (W - 4, y0 + 2)], fill=RED)
    if bottom_round:
        yb = y0 + h - 1
        d.line([(2, yb), (W - 3, yb)], fill=OUT); img.putpixel((1, yb - 1), OUT); img.putpixel((W - 2, yb - 1), OUT)
        d.line([(3, yb - 1), (W - 3, yb - 1)], fill=SH)

def slot(x, y):
    d.rectangle([x, y, x + 17, y + 17], fill=SLOT)
    d.line([(x, y), (x + 16, y)], fill=SLOT_DARK); d.line([(x, y), (x, y + 16)], fill=SLOT_DARK)
    d.line([(x + 1, y + 17), (x + 17, y + 17)], fill=SLOT_LIGHT); d.line([(x + 17, y + 1), (x + 17, y + 17)], fill=SLOT_LIGHT)

# Oberer Teil: Kopf + 6 Reihen (Items bei 8+18j, 18+18i)
section(0, 126, True, False)
for i in range(6):
    for j in range(9):
        slot(7 + 18 * j, 17 + 18 * i)
# Unterer Teil: Spieler-Inventar (Items bei 14+18i bzw. 72 relativ zum Anschluss)
section(126, 96, False, True)
for i in range(3):
    for j in range(9):
        slot(7 + 18 * j, 126 + 13 + 18 * i)
d.line([(7, 126 + 67), (168, 126 + 67)], fill=RED)
for j in range(9):
    slot(7 + 18 * j, 126 + 71)
img.save(out)

# Vorschau: so wie Minecraft ein 6-Reihen-Menü zusammensetzt
rows = 6
h_top = rows * 18 + 17
comp = Image.new("RGBA", (W, h_top + 96), (0, 0, 0, 0))
comp.paste(img.crop((0, 0, W, h_top)), (0, 0))
comp.paste(img.crop((0, 126, W, 222)), (0, h_top))
big = Image.new("RGBA", (W * 3 + 20, (h_top + 96) * 3 + 20), (60, 60, 60, 255))
c3 = comp.resize((W * 3, (h_top + 96) * 3), Image.NEAREST)
big.paste(c3, (10, 10), c3)
big.save(preview)
print("ok")
