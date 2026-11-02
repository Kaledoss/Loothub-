"""Erzeugt die schwarz-rote Inventar-Textur (256x256, Layout wie Vanilla inventory.png, 176x166 genutzt)."""
import sys
from PIL import Image, ImageDraw

out = sys.argv[1]
W, H = 176, 166
img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

BG = (26, 16, 18, 255)          # Hintergrund
HI = (92, 38, 44, 255)          # helle Kante oben/links
SH = (8, 4, 5, 255)             # dunkle Kante unten/rechts
OUT = (0, 0, 0, 255)            # Außenlinie
RED = (176, 38, 46, 255)        # Akzent
SLOT = (40, 24, 28, 255)        # Slot-Innenfläche
SLOT_DARK = (10, 5, 6, 255)
SLOT_LIGHT = (78, 40, 46, 255)

def px(x, y, c):
    img.putpixel((x, y), c)

# Grundfläche mit abgerundeten Ecken (wie Vanilla: 1 px Ecke frei)
d.rectangle([1, 1, W - 2, H - 2], fill=BG)
d.line([(2, 0), (W - 4, 0)], fill=OUT); d.line([(2, H - 1), (W - 3, H - 1)], fill=OUT)
d.line([(0, 2), (0, H - 4)], fill=OUT); d.line([(W - 1, 3), (W - 1, H - 3)], fill=OUT)
px(1, 1, OUT); px(W - 3, 1, OUT); px(W - 2, 2, OUT); px(1, H - 3, OUT); px(2, H - 2, OUT); px(W - 2, H - 2, OUT)
# Bevel
d.line([(2, 1), (W - 4, 1)], fill=HI); d.line([(1, 2), (1, H - 4)], fill=HI)
d.line([(2, 2), (W - 5, 2)], fill=HI); d.line([(2, 2), (2, H - 5)], fill=HI)
d.line([(3, H - 2), (W - 3, H - 2)], fill=SH); d.line([(W - 2, 3), (W - 2, H - 3)], fill=SH)
d.line([(4, H - 3), (W - 3, H - 3)], fill=SH); d.line([(W - 3, 4), (W - 3, H - 3)], fill=SH)
# roter Akzentstreifen oben
d.line([(3, 3), (W - 4, 3)], fill=RED)

def slot(x, y, size=18):
    """Slot-Rahmen wie Vanilla: dunkel oben/links, hell unten/rechts. (x,y) = linke obere Ecke des Rahmens."""
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=SLOT)
    d.line([(x, y), (x + size - 2, y)], fill=SLOT_DARK)
    d.line([(x, y), (x, y + size - 2)], fill=SLOT_DARK)
    d.line([(x + 1, y + size - 1), (x + size - 1, y + size - 1)], fill=SLOT_LIGHT)
    d.line([(x + size - 1, y + 1), (x + size - 1, y + size - 1)], fill=SLOT_LIGHT)

# Rüstung (Items bei x=8, y=8+18i → Rahmen 1 px davor)
for i in range(4):
    slot(7, 7 + 18 * i)
# Spieler-Vorschau (Vanilla: 26..75 x 8..78)
d.rectangle([25, 7, 76, 78], fill=(0, 0, 0, 255))
d.line([(25, 7), (75, 7)], fill=SLOT_DARK); d.line([(25, 7), (25, 77)], fill=SLOT_DARK)
d.line([(26, 78), (76, 78)], fill=SLOT_LIGHT); d.line([(76, 8), (76, 78)], fill=SLOT_LIGHT)
# Zweithand (Item bei 77,62)
slot(76, 61)
# Handwerk 2x2 (Items bei 98+18j, 18+18i)
for i in range(2):
    for j in range(2):
        slot(97 + 18 * j, 17 + 18 * i)
# Pfeil zwischen Handwerksfeld (endet bei x=133) und Ergebnis (beginnt bei x=149)
ax, ay = 138, 30
d.rectangle([ax, ay + 4, ax + 5, ay + 8], fill=SLOT_LIGHT)
d.polygon([(ax + 6, ay), (ax + 11, ay + 6), (ax + 6, ay + 12)], fill=SLOT_LIGHT)
# Ergebnis (Item bei 154,28): normaler Slot wie im Vanilla-Inventar
slot(153, 27)
# Überschrift „Loothub+“ schreibt die Mod selbst (InventoryScreenMixin), kein Schild mehr
# Inventar 3x9 (Items bei 8+18j, 84+18i) und Schnellleiste (y=142)
for i in range(3):
    for j in range(9):
        slot(7 + 18 * j, 83 + 18 * i)
d.line([(7, 138), (168, 138)], fill=RED)
for j in range(9):
    slot(7 + 18 * j, 141)

img.save(out)

# Vorschau (4x, mit Rahmen) für die Abstimmung
prev = img.crop((0, 0, W, H)).resize((W * 4, H * 4), Image.NEAREST)
canvas = Image.new("RGBA", (W * 4 + 40, H * 4 + 40), (40, 40, 40, 255))
canvas.paste(prev, (20, 20), prev)
canvas.save(sys.argv[2])
print("ok")
