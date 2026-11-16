"""Schwarz-rote Textur für die Steinsäge (stonecutter.png, 256x256, 176x166 genutzt) samt Rezept-/Scroll-Sprites.

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


# --- Steinsäge: Eingabe (20,33), Rezeptfeld 4x3 bei (52,14) je 16x18, Scrollleiste (119,15..), Ergebnis (143,33)
img, d = base()
slot(d, 19, 32)                                   # Eingabe
# Rezeptfeld: dunkle Mulde hinter den Rezeptknöpfen
d.rectangle([51, 13, 116, 68], fill=SLOT_DARK)
d.line([(51, 68), (116, 68)], fill=SLOT_LIGHT); d.line([(116, 13), (116, 68)], fill=SLOT_LIGHT)
# Scrollleiste
d.rectangle([118, 14, 131, 69], fill=SLOT_DARK)
d.line([(118, 69), (131, 69)], fill=SLOT_LIGHT); d.line([(131, 14), (131, 69)], fill=SLOT_LIGHT)
slot(d, 138, 28, 26)                              # großer Ergebnis-Slot
player_inventory(d)
img.save(os.path.join(outdir, "stonecutter.png"))

sprite_dir = os.path.join(outdir, "..", "sprites", "container", "stonecutter")
os.makedirs(sprite_dir, exist_ok=True)


def recipe(name, fill, edge):
    r = Image.new("RGBA", (16, 18), fill)
    rd = ImageDraw.Draw(r)
    rd.rectangle([0, 0, 15, 17], outline=edge)
    rd.line([(1, 16), (14, 16)], fill=SH)
    r.save(os.path.join(sprite_dir, name + ".png"))
    return r


sprites = [recipe("recipe", SLOT, SLOT_LIGHT), recipe("recipe_highlighted", HI, RED),
           recipe("recipe_selected", RED, (240, 120, 128, 255))]


def scroller(name, fill, edge):
    s = Image.new("RGBA", (12, 15), fill)
    sd = ImageDraw.Draw(s)
    sd.rectangle([0, 0, 11, 14], outline=edge)
    for yy in (5, 7, 9):
        sd.line([(3, yy), (8, yy)], fill=edge)
    s.save(os.path.join(sprite_dir, name + ".png"))
    return s


sprites += [scroller("scroller", RED, (240, 120, 128, 255)), scroller("scroller_disabled", SLOT_LIGHT, HI)]

# Vorschau 3x mit Sprites eingesetzt
pv = img.crop((0, 0, W, H)).copy()
for k in range(4):
    pv.paste(sprites[0], (52 + 16 * k, 14), sprites[0])
pv.paste(sprites[2], (52, 14), sprites[2]); pv.paste(sprites[1], (68, 14), sprites[1])
pv.paste(sprites[3], (119, 15), sprites[3])
pv.resize((W * 3, H * 3), Image.NEAREST).save(preview)
print("ok")
