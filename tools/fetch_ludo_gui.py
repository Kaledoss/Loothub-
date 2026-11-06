"""Lädt mit Ludo erzeugte GUI-Bilder (schon freigestellt), schneidet sie auf den Inhalt zu und speichert sie als PNG.

Aufruf: python3 tools/fetch_ludo_gui.py tools/ludo_gui.txt src/main/resources/assets/loothub/textures/gui
Jede Zeile: <name> <längste Seite in px> <url>   → <name>.png
Läuft im CI, weil die Ludo-Links nur dort erreichbar sind und nach 7 Tagen ablaufen.
"""
import io, os, sys, urllib.request
from PIL import Image

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
for line in open(src, encoding="utf-8"):
    parts = line.split()
    if len(parts) != 3 or line.startswith("#"):
        continue
    name, size, url = parts[0], int(parts[1]), parts[2]
    img = Image.open(io.BytesIO(urllib.request.urlopen(url, timeout=60).read())).convert("RGBA")
    bbox = img.getchannel("A").point(lambda a: 255 if a > 40 else 0).getbbox()
    if bbox:
        img = img.crop(bbox)
    s = size / max(img.width, img.height)
    img = img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.LANCZOS)
    a = img.getchannel("A").point(lambda v: 255 if v >= 100 else 0)  # harte Kante, keine Säume
    img.putalpha(a)
    img.save(os.path.join(out, name + ".png"))
    print(name, img.size)
