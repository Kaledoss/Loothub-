"""Lädt die mit Ludo erzeugten Flügelpaare (freigestellt) und macht daraus hochauflösende Flügel-Texturen.

Aufruf: python3 tools/fetch_ludo_wings.py tools/ludo_wings.txt src/main/resources/assets/loothub/textures/entity
Jede Zeile: <stil> <url>   (z. B. "dragon https://…png")
Erzeugt je Stil (264×512, Seitenverhältnis 0,52 wie Wings.ASPECT, Ansatz oben links):
  wing_<stil>.png            Originalfarben
  wing_<stil>_gray.png       helle Graustufen (zum Einfärben)
  wing_<stil>_in.png / _out.png             Fläche bzw. Knochen+Rand in Originalfarben
  wing_<stil>_in_gray.png / _out_gray.png   dieselben Teile in Graustufen
Läuft im CI, weil die Ludo-Links nur dort erreichbar sind und nach 7 Tagen ablaufen.
"""
import io, os, sys, urllib.request
from PIL import Image, ImageOps, ImageFilter

H, W = 512, 264


def wing(data: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(data)).convert("RGBA")
    bbox = img.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox()
    if bbox:
        img = img.crop(bbox)
    half = img.crop((img.width // 2, 0, img.width, img.height))  # rechter Flügel, Ansatz links
    hb = half.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox()
    if hb:
        half = half.crop(hb)
    scale = min(W / half.width, H / half.height)
    half = half.resize((max(1, round(half.width * scale)), max(1, round(half.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.paste(half, (0, 0), half)
    # harte Kante für Cutout-Darstellung (keine halbdurchsichtigen Säume)
    a = out.getchannel("A").point(lambda v: 255 if v >= 110 else 0)
    out.putalpha(a)
    return out


def variants(w: Image.Image):
    a = w.getchannel("A")
    rgb = w.convert("RGB")
    gray = ImageOps.autocontrast(ImageOps.grayscale(rgb), cutoff=1).point(lambda v: int(70 + v * 0.73))
    lum = ImageOps.grayscale(rgb)
    px_a, px_l = a.load(), lum.load()
    vals = sorted(px_l[x, y] for y in range(H) for x in range(W) if px_a[x, y] > 0)
    thr = vals[int(len(vals) * 0.32)] if vals else 0
    # Rand: 3 px breite Kante an der Außenkontur
    edge = a.filter(ImageFilter.MinFilter(7))
    pe = edge.load()
    m_out = Image.new("L", (W, H), 0)
    pm = m_out.load()
    for y in range(H):
        for x in range(W):
            if px_a[x, y] > 0 and (pe[x, y] == 0 or px_l[x, y] < thr):
                pm[x, y] = 255
    m_in = Image.new("L", (W, H), 0)
    pi = m_in.load()
    for y in range(H):
        for x in range(W):
            if px_a[x, y] > 0 and pm[x, y] == 0:
                pi[x, y] = 255
    g_rgba = Image.merge("RGBA", (gray, gray, gray, a))
    g_in = gray.point(lambda v: min(255, int(v * 1.1 + 20)))
    res = {"": w, "_gray": g_rgba}
    for name, mask, src in (("_in", m_in, w), ("_out", m_out, w)):
        part = src.copy()
        part.putalpha(mask)
        res[name] = part
    res["_in_gray"] = Image.merge("RGBA", (g_in, g_in, g_in, m_in))
    res["_out_gray"] = Image.merge("RGBA", (gray, gray, gray, m_out))
    return res


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
        for suffix, img in variants(wing(data)).items():
            img.save(os.path.join(out, f"wing_{name}{suffix}.png"))
        print("ok", name)


if __name__ == "__main__":
    main()
