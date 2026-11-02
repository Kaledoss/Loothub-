"""Schwarz-rote Texturen für Werkbank (crafting_table.png) und Amboss (anvil.png), 256x256, 176x166 genutzt.

Slot-Positionen wie Vanilla, damit die Items richtig sitzen. Überschriften schreibt Minecraft dunkelgrau,
die Mod färbt sie in diesen Menüs hell (ContainerLabelMixin).
Aufruf: python3 make_workstations.py <ausgabeordner> <vorschau.png>
"""
import os
import sys
from PIL import Image, ImageDraw

outdir, preview = sys.argv[1], sys.argv[2]
W, H = 176, 166
BG = (26, 16, 18, 255); HI = (92, 38, 44, 255); SH = (8, 4, 5, 255); OUT = (0, 0, 0, 255)
RED = (176, 38, 46, 255); SLOT = (40, 24, 28, 255); SLOT_DARK = (10, 5, 6, 255); SLOT_LIGHT = (78, 40, 46, 255)
PLATE = (206, 186, 190, 255)


def base():
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
    return img, d


def slot(d, x, y, size=18):
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=SLOT)
    d.line([(x, y), (x + size - 2, y)], fill=SLOT_DARK); d.line([(x, y), (x, y + size - 2)], fill=SLOT_DARK)
    d.line([(x + 1, y + size - 1), (x + size - 1, y + size - 1)], fill=SLOT_LIGHT)
    d.line([(x + size - 1, y + 1), (x + size - 1, y + size - 1)], fill=SLOT_LIGHT)


def plate(d, x1, y1, x2, y2):
    """Früher helles Schild hinter Überschriften – entfällt, die Mod färbt die Überschriften hell (ContainerLabelMixin)."""


def arrow(d, x, y):
    """Pfeil 22x15 (wie Vanilla-Größe), linke obere Ecke (x, y)."""
    d.rectangle([x, y + 5, x + 13, y + 9], fill=SLOT_LIGHT)
    d.polygon([(x + 13, y), (x + 21, y + 7), (x + 13, y + 14)], fill=SLOT_LIGHT)


def player_inventory(d):
    plate(d, 6, 71, 80, 79)                       # Überschrift „Inventar“ bei (8, 72)
    for i in range(3):
        for j in range(9):
            slot(d, 7 + 18 * j, 83 + 18 * i)
    d.line([(7, 138), (168, 138)], fill=RED)
    for j in range(9):
        slot(d, 7 + 18 * j, 141)


# --- Werkbank: Raster-Items bei (30+18j, 17+18i), Ergebnis bei (124, 35)
img, d = base()
plate(d, 27, 5, 110, 14)                          # Titel bei (29, 6)
for i in range(3):
    for j in range(3):
        slot(d, 29 + 18 * j, 16 + 18 * i)
arrow(d, 90, 35)
slot(d, 119, 30, 26)                              # großer Ergebnis-Slot
player_inventory(d)
crafting = img
crafting.save(os.path.join(outdir, "crafting_table.png"))

# --- Amboss: Eingaben (27,47) und (76,47), Ergebnis (134,47); Titel bei (60,6); Namensfeld (59,20) als eigenes Sprite
img, d = base()
plate(d, 58, 5, 170, 14)
# kleiner Amboss als Symbol oben links
d.rectangle([14, 14, 41, 18], fill=SLOT_LIGHT)    # Amboss-Platte
d.rectangle([22, 19, 33, 24], fill=HI)            # Hals
d.rectangle([17, 25, 38, 29], fill=SLOT_LIGHT)    # Fuß
d.line([(14, 14), (41, 14)], fill=RED)
slot(d, 26, 46); slot(d, 75, 46); slot(d, 133, 46)
# Plus zwischen den Eingaben
d.rectangle([54, 53, 66, 55], fill=SLOT_LIGHT); d.rectangle([59, 48, 61, 60], fill=SLOT_LIGHT)
arrow(d, 102, 47)
player_inventory(d)
anvil = img
anvil.save(os.path.join(outdir, "anvil.png"))

# Namensfeld-Sprites (110x16)
sprite_dir = os.path.join(outdir, "..", "..", "sprites", "container", "anvil")
os.makedirs(sprite_dir, exist_ok=True)
for name, edge in (("text_field", RED), ("text_field_disabled", SLOT_LIGHT)):
    f = Image.new("RGBA", (110, 16), SLOT_DARK)
    fd = ImageDraw.Draw(f)
    fd.rectangle([0, 0, 109, 15], outline=edge)
    f.save(os.path.join(sprite_dir, name + ".png"))

# Vorschau nebeneinander, 3x
canvas = Image.new("RGBA", (W * 6 + 30, H * 3 + 20), (60, 60, 60, 255))
for k, im in enumerate((crafting, anvil)):
    c = im.crop((0, 0, W, H)).resize((W * 3, H * 3), Image.NEAREST)
    canvas.paste(c, (10 + k * (W * 3 + 10), 10), c)
canvas.save(preview)
print("ok")
