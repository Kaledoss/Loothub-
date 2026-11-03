"""Lädt die mit Ludo erzeugten Kugel-Bilder (freigestellt) und macht daraus 16x16-Symbole.

Aufruf: python3 tools/fetch_ludo_orbs.py tools/ludo_orbs.txt src/main/resources/assets/loothub/textures/gui
Jede Zeile in der Liste: <name> <url>   (z. B. "orb_red https://…png")
Wird im CI ausgeführt, weil die Ludo-Links nur dort erreichbar sind und nach 7 Tagen ablaufen.
"""
import io, os, sys, urllib.request
from PIL import Image

SIZE = 16

def to_icon(data: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(data)).convert("RGBA")
    bbox = img.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox()
    if bbox:
        img = img.crop(bbox)
    # quadratisch mittig einpassen
    side = max(img.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(img, ((side - img.width) // 2, (side - img.height) // 2))
    # erst grob verkleinern (Box-Filter mittelt sauber), dann Alpha hart machen → scharfe Pixelkanten
    icon = sq.resize((SIZE, SIZE), Image.BOX)
    px = icon.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = px[x, y]
            if a < 100:
                px[x, y] = (0, 0, 0, 0)
            else:
                px[x, y] = (r, g, b, 255)
    return icon

def main():
    listing, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for line in open(listing, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, url = line.split(None, 1)
        with urllib.request.urlopen(url, timeout=60) as r:
            data = r.read()
        to_icon(data).save(os.path.join(out, name + ".png"))
        print("ok", name)

if __name__ == "__main__":
    main()
