"""Schwarz-rote Texturen für das Rezeptbuch: Hintergrund (recipe_book.png) und Sprites (Reiter, Buch-Knopf)."""
import os, sys
from PIL import Image, ImageDraw

root = sys.argv[1]       # .../resourcepacks/dark_inventory/assets/minecraft/textures/gui
preview = sys.argv[2]

BG = (26, 16, 18, 255); HI = (92, 38, 44, 255); SH = (8, 4, 5, 255); OUT = (0, 0, 0, 255)
RED = (176, 38, 46, 255); INNER = (18, 10, 12, 255); EDGE_D = (10, 5, 6, 255); EDGE_L = (78, 40, 46, 255)

def panel(img, x0, y0, w, h, inner=None):
    d = ImageDraw.Draw(img)
    d.rectangle([x0 + 1, y0 + 1, x0 + w - 2, y0 + h - 2], fill=BG)
    d.line([(x0 + 2, y0), (x0 + w - 3, y0)], fill=OUT); d.line([(x0 + 2, y0 + h - 1), (x0 + w - 3, y0 + h - 1)], fill=OUT)
    d.line([(x0, y0 + 2), (x0, y0 + h - 3)], fill=OUT); d.line([(x0 + w - 1, y0 + 2), (x0 + w - 1, y0 + h - 3)], fill=OUT)
    for (px, py) in [(x0 + 1, y0 + 1), (x0 + w - 2, y0 + 1), (x0 + 1, y0 + h - 2), (x0 + w - 2, y0 + h - 2)]:
        img.putpixel((px, py), OUT)
    d.line([(x0 + 2, y0 + 1), (x0 + w - 3, y0 + 1)], fill=HI); d.line([(x0 + 1, y0 + 2), (x0 + 1, y0 + h - 3)], fill=HI)
    d.line([(x0 + 2, y0 + h - 2), (x0 + w - 3, y0 + h - 2)], fill=SH); d.line([(x0 + w - 2, y0 + 2), (x0 + w - 2, y0 + h - 3)], fill=SH)
    d.line([(x0 + 3, y0 + 2), (x0 + w - 4, y0 + 2)], fill=RED)
    if inner:
        ix, iy, iw, ih = inner
        d.rectangle([x0 + ix, y0 + iy, x0 + ix + iw - 1, y0 + iy + ih - 1], fill=INNER)
        d.line([(x0 + ix, y0 + iy), (x0 + ix + iw - 1, y0 + iy)], fill=EDGE_D)
        d.line([(x0 + ix, y0 + iy), (x0 + ix, y0 + iy + ih - 1)], fill=EDGE_D)
        d.line([(x0 + ix + 1, y0 + iy + ih - 1), (x0 + ix + iw - 1, y0 + iy + ih - 1)], fill=EDGE_L)
        d.line([(x0 + ix + iw - 1, y0 + iy + 1), (x0 + ix + iw - 1, y0 + iy + ih - 1)], fill=EDGE_L)

# 1) Hintergrund: Buch 147x166 bei (1,1) in einer 256x256-Textur
book = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
panel(book, 1, 1, 147, 166, inner=(7, 7, 133, 152))
os.makedirs(root, exist_ok=True)
book.save(os.path.join(root, "recipe_book.png"))

sprites = os.path.join(root, "sprites", "recipe_book")
os.makedirs(sprites, exist_ok=True)

# 2) Reiter links am Buch (35x27); ausgewählt etwas heller + roter Rand links
def tab(selected):
    im = Image.new("RGBA", (35, 27), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    body = (40, 22, 26, 255) if selected else BG
    d.rectangle([1, 1, 34, 25], fill=body)
    d.line([(2, 0), (34, 0)], fill=OUT); d.line([(2, 26), (34, 26)], fill=OUT); d.line([(0, 2), (0, 24)], fill=OUT)
    im.putpixel((1, 1), OUT); im.putpixel((1, 25), OUT)
    d.line([(2, 1), (34, 1)], fill=HI); d.line([(1, 2), (1, 24)], fill=HI)
    d.line([(2, 25), (34, 25)], fill=SH)
    if selected:
        d.line([(1, 3), (1, 23)], fill=RED); d.line([(2, 3), (2, 23)], fill=RED)
    return im
tab(False).save(os.path.join(sprites, "tab.png"))
tab(True).save(os.path.join(sprites, "tab_selected.png"))

# 3) Buch-Knopf im Inventar (20x18): rotes Buch
def book_button(highlight):
    im = Image.new("RGBA", (20, 18), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cover = (200, 52, 60, 255) if highlight else (160, 34, 42, 255)
    dark = (90, 16, 22, 255)
    pages = (232, 220, 214, 255)
    d.rectangle([3, 2, 16, 15], fill=cover)          # Einband
    d.rectangle([3, 2, 4, 15], fill=dark)             # Buchrücken
    d.rectangle([14, 3, 16, 14], fill=pages)          # Seiten
    d.line([(14, 6), (16, 6)], fill=(200, 188, 182, 255)); d.line([(14, 10), (16, 10)], fill=(200, 188, 182, 255))
    d.rectangle([7, 6, 11, 9], fill=(240, 200, 90, 255))  # goldenes Schild
    d.rectangle([2, 1, 17, 16], outline=OUT)
    if highlight:
        d.rectangle([1, 0, 18, 17], outline=(255, 255, 255, 255))
    return im
book_button(False).save(os.path.join(sprites, "button.png"))
book_button(True).save(os.path.join(sprites, "button_highlighted.png"))

# Vorschau
prev = Image.new("RGBA", (147 * 4 + 35 * 4 + 60, 166 * 4 + 40), (40, 40, 40, 255))
b = book.crop((1, 1, 148, 167)).resize((147 * 4, 166 * 4), Image.NEAREST)
prev.paste(b, (35 * 4 + 40, 20), b)
for i, sel in enumerate([True, False, False]):
    t = tab(sel).resize((35 * 4, 27 * 4), Image.NEAREST)
    prev.paste(t, (20 + (0 if sel else 8), 60 + i * 120), t)
bb = book_button(False).resize((80, 72), Image.NEAREST)
prev.paste(bb, (40, 520), bb)
prev.save(preview)
print("ok")
