"""Lädt die mit Ludo erzeugten Flügelpaare (freigestellt) und macht daraus Flügel-Texturen.

Aufruf: python3 tools/fetch_ludo_wings.py tools/ludo_wings.txt src/main/resources/assets/loothub/textures/entity
Jede Zeile: <stil> <url>   (z. B. "dragon https://…png") → wing_<stil>.png
Aus dem symmetrischen Paar wird die rechte Hälfte genommen: Flügelansatz am linken Rand,
Spitze rechts. Die Textur hat immer das Seitenverhältnis 0,9 (Breite/Höhe), wie Wings.ASPECT.
Läuft im CI, weil die Ludo-Links nur dort erreichbar sind und nach 7 Tagen ablaufen.
"""
import io, os, sys, urllib.request
from PIL import Image

H, W = 128, 115  # 115/128 ≈ 0,9

def to_wing(data: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(data)).convert("RGBA")
    bbox = img.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox()
    if bbox:
        img = img.crop(bbox)
    half = img.crop((img.width // 2, 0, img.width, img.height))
    hb = half.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox()
    if hb:
        half = half.crop(hb)  # Lücke zwischen den Flügeln weg → Ansatz am linken Rand
    scale = min(W / half.width, H / half.height)
    half = half.resize((max(1, round(half.width * scale)), max(1, round(half.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.paste(half, (0, (H - half.height) // 2), half)
    return out

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
        to_wing(data).save(os.path.join(out, "wing_" + name + ".png"))
        print("ok", name)

if __name__ == "__main__":
    main()
