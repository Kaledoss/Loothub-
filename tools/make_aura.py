"""Aura-Texturen (64x64, nahtlos kachelbar): nach oben züngelnde Energie-Streifen in mehreren Farben.
Eigenes Muster aus Rauschen – wird im Spiel nach oben verschoben, dadurch wirkt die Aura lodernd."""
import math, os, random, sys
from PIL import Image

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
COLORS = {"base": (255, 255, 255)}
          "purple": (190, 90, 255), "white": (235, 240, 255)}
N = 64
random.seed(7)
# kachelbar: senkrechte, wellige Flammenzungen (ganzzahlige Frequenzen), entlang y in Stücke gebrochen
def field(x, y):
    u, v = x / N, y / N
    sway = 0.06 * math.sin(2 * math.pi * (2 * v)) + 0.04 * math.sin(2 * math.pi * (3 * v) + 1.7)
    stripes = 0.5 + 0.5 * math.sin(2 * math.pi * (7 * u + sway))
    stripes2 = 0.5 + 0.5 * math.sin(2 * math.pi * (11 * u - sway * 1.5) + 2.1)
    breaks = 0.5 + 0.5 * math.sin(2 * math.pi * (2 * v + 3 * u) + 0.8)
    breaks2 = 0.5 + 0.5 * math.sin(2 * math.pi * (3 * v - 5 * u) + 2.4)
    return (0.65 * stripes + 0.35 * stripes2) * (0.45 + 0.55 * breaks) * (0.6 + 0.4 * breaks2)
vals = [[field(x, y) for x in range(N)] for y in range(N)]
lo = min(min(r) for r in vals); hi = max(max(r) for r in vals)
for name, (r, g, b) in COLORS.items():
    img = Image.new("RGBA", (N, N))
    px = img.load()
    for y in range(N):
        for x in range(N):
            t = (vals[y][x] - lo) / (hi - lo)
            # schmale helle Flammenzungen, dazwischen fast durchsichtig
            s = max(0.0, (t - 0.42) / 0.58) ** 1.3
            a = int(255 * min(1.0, s * 1.4))
            k = 0.65 + 0.35 * s
            px[x, y] = (min(255, int(r * k + 60 * s)), min(255, int(g * k + 60 * s)), min(255, int(b * k + 60 * s)), a)
    img.save(os.path.join(out, f"aura_{name}.png"))
print("ok", list(COLORS))
