"""Eigenes Himmel-Paket: leuchtende Sonne mit Korona, Mond mit Kratern und Schein (alle Phasen),
weichere Wolken und ein Nebel-End-Himmel. Alles prozedural erzeugt.

Aufruf: python3 make_sky.py <paket-ordner> <vorschau.png>
"""
import math, os, random, sys
from PIL import Image, ImageDraw, ImageFilter

out, preview = sys.argv[1], sys.argv[2]
env = os.path.join(out, "assets/minecraft/textures/environment")
cel = os.path.join(env, "celestial")
os.makedirs(os.path.join(cel, "moon"), exist_ok=True)
rnd = random.Random(7)

# ---------- Sonne (wird additiv gezeichnet: Schwarz = unsichtbar)
S = 128
sun = Image.new("RGB", (S, S), (0, 0, 0))
px = sun.load()
c = (S - 1) / 2
for y in range(S):
    for x in range(S):
        d = math.hypot(x - c, y - c) / c
        a = math.atan2(y - c, x - c)
        rays = 0.5 + 0.5 * math.cos(a * 12) * math.cos(a * 5 + 1)
        core = max(0.0, 1 - d / 0.32)
        glow = max(0.0, 1 - d) ** 2.2 * (0.75 + 0.25 * rays)
        v = min(1.0, core * 1.6 + glow)
        r = 255 * v
        g = 255 * min(1.0, core * 1.4 + glow * 0.78)
        b = 255 * min(1.0, core * 1.2 + glow * 0.35)
        px[x, y] = (int(r), int(g), int(b))
sun.save(os.path.join(env, "sun.png"))
sun.save(os.path.join(cel, "sun.png"))

# ---------- Mond: Scheibe mit Kratern, kühler Schein; Phasen über Schatten
M = 64
def moon_disc():
    img = Image.new("RGB", (M, M), (0, 0, 0))
    p = img.load()
    m = (M - 1) / 2
    craters = [(rnd.uniform(-0.5, 0.5), rnd.uniform(-0.5, 0.5), rnd.uniform(0.06, 0.18)) for _ in range(9)]
    for y in range(M):
        for x in range(M):
            dx, dy = (x - m) / m, (y - m) / m
            d = math.hypot(dx, dy)
            if d <= 0.62:
                shade = 0.82 + 0.18 * (1 - d / 0.62) - 0.12 * dx
                for cx, cy, cr in craters:
                    cd = math.hypot(dx - cx, dy - cy)
                    if cd < cr:
                        shade -= 0.18 * (1 - cd / cr)
                v = max(0.0, min(1.0, shade))
                p[x, y] = (int(205 * v), int(215 * v), int(240 * v))
            elif d < 1:
                gl = (1 - (d - 0.62) / 0.38) ** 2 * 0.45
                p[x, y] = (int(90 * gl), int(120 * gl), int(200 * gl))
    return img

base = moon_disc()
# Reihenfolge wie Vanilla: voll, abnehmend dreiviertel, letztes Viertel, abnehmende Sichel, neu, zunehmende Sichel, erstes Viertel, zunehmend dreiviertel
phases = [("full_moon", 1.0, 1), ("waning_gibbous", 0.5, 1), ("third_quarter", 0.0, 1), ("waning_crescent", -0.5, 1),
          ("new_moon", -1.0, 1), ("waxing_crescent", -0.5, -1), ("first_quarter", 0.0, -1), ("waxing_gibbous", 0.5, -1)]
sheet = Image.new("RGB", (M * 4, M * 2), (0, 0, 0))
for i, (name, k, side) in enumerate(phases):
    img = base.copy()
    p = img.load()
    m = (M - 1) / 2
    r = 0.62 * m
    for y in range(M):
        for x in range(M):
            dx, dy = x - m, y - m
            if math.hypot(dx, dy) > r:
                continue
            half = math.sqrt(max(0.0, r * r - dy * dy))
            edge = k * half  # Terminator
            lit = (dx * side) <= edge if k >= 0 else (dx * side) <= edge
            if not lit:
                rr, gg, bb = p[x, y]
                p[x, y] = (rr // 10, gg // 10, bb // 8)
    img.save(os.path.join(cel, "moon", name + ".png"))
    sheet.paste(img, ((i % 4) * M, (i // 4) * M))
sheet.save(os.path.join(env, "moon_phases.png"))

# ---------- Wolken: größere, weichere Haufen (Weiß mit Deckkraft)
C = 256
noise = Image.new("L", (C, C), 0)
dn = ImageDraw.Draw(noise)
for _ in range(28):
    x, y = rnd.randrange(C), rnd.randrange(C)
    w, h = rnd.randint(6, 18), rnd.randint(4, 12)
    for ox in (-C, 0, C):
        for oy in (-C, 0, C):
            dn.ellipse([x + ox - w, y + oy - h, x + ox + w, y + oy + h], fill=255)
noise = noise.filter(ImageFilter.GaussianBlur(2))
clouds = Image.new("RGBA", (C, C), (0, 0, 0, 0))
cp = clouds.load()
npx = noise.load()
for y in range(C):
    for x in range(C):
        v = npx[x, y]
        if v > 110:
            cp[x, y] = (255, 255, 255, 230)
clouds.save(os.path.join(env, "clouds.png"))

# ---------- End-Himmel: violetter Nebel mit Sternen
E = 128
end = Image.new("RGB", (E, E), (10, 6, 18))
ep = end.load()
for y in range(E):
    for x in range(E):
        n = (math.sin(x * 0.11) + math.sin(y * 0.09 + x * 0.04) + math.sin((x + y) * 0.07)) / 3
        v = max(0.0, n) ** 1.5
        ep[x, y] = (int(24 + 70 * v), int(10 + 20 * v), int(40 + 110 * v))
for _ in range(90):
    x, y = rnd.randrange(E), rnd.randrange(E)
    b = rnd.randint(150, 255)
    ep[x, y] = (b, b, min(255, b + 20))
end.save(os.path.join(env, "end_sky.png"))

# ---------- Vorschau
pv = Image.new("RGB", (560, 300), (14, 20, 48))
pv.paste(sun.resize((128, 128)), (10, 10))
pv.paste(sheet.resize((256, 128)), (150, 10))
pv.paste(end.resize((128, 128)), (420, 10))
cl = Image.new("RGB", (256, 140), (90, 140, 210))
cs = clouds.resize((256, 256), Image.NEAREST).crop((0, 0, 256, 140)); cl.paste(cs, (0, 0), cs)
pv.paste(cl, (10, 150))
pv.save(preview)
print("ok")
