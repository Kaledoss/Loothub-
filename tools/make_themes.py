"""Farbvarianten des schwarz-roten Pakets: Rot-Akzente werden in eine andere Farbe gedreht.

Aufruf: python3 make_themes.py <resourcepacks-ordner>
Erzeugt dark_inventory_<name>/ neben dark_inventory/ (gleiche Dateien, andere Akzentfarbe).
"""
import colorsys, os, shutil, sys
from PIL import Image

root = sys.argv[1]
src = os.path.join(root, "dark_inventory")
THEMES = {"blue": 0.60, "green": 0.36, "purple": 0.78, "gold": 0.12, "cyan": 0.50}
NAMES = {"blue": "Blau", "green": "Grün", "purple": "Lila", "gold": "Gold", "cyan": "Türkis"}

def recolor(img, hue):
    img = img.convert("RGBA")
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
            if s > 0.15 and (h < 0.08 or h > 0.92):
                nr, ng, nb = colorsys.hls_to_rgb(hue, l, s)
                px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return img

for key, hue in THEMES.items():
    dst = os.path.join(root, "dark_inventory_" + key)
    if os.path.exists(dst):
        shutil.rmtree(dst)
    for dirpath, _, files in os.walk(src):
        rel = os.path.relpath(dirpath, src)
        os.makedirs(os.path.join(dst, rel), exist_ok=True)
        for f in files:
            sp = os.path.join(dirpath, f)
            dp = os.path.join(dst, rel, f)
            if f.endswith(".png"):
                recolor(Image.open(sp), hue).save(dp)
            elif f == "pack.mcmeta":
                txt = open(sp, encoding="utf-8").read().replace("Schwarz-Rot", "Schwarz-" + NAMES[key])
                open(dp, "w", encoding="utf-8").write(txt)
            else:
                shutil.copy(sp, dp)
print("ok")
