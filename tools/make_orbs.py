"""Eigene Kugel-Symbole (16x16) für die Wurf-/Kauf-Knöpfe: schlichte, glänzende Kugeln in drei Farben."""
import os, sys
from PIL import Image

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
COLORS = {"orb_red": (214, 52, 52), "orb_blue": (60, 120, 220), "orb_gold": (232, 186, 48)}
for name, (r, g, b) in COLORS.items():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    cx = cy = 7.5
    for y in range(16):
        for x in range(16):
            dx, dy = x - cx, y - cy
            d = (dx * dx + dy * dy) ** 0.5
            if d > 7.2:
                continue
            # Licht von oben links
            light = max(0.0, 1 - ((x - 5) ** 2 + (y - 4.5) ** 2) ** 0.5 / 11)
            f = 0.55 + 0.6 * light
            c = (min(255, int(r * f)), min(255, int(g * f)), min(255, int(b * f)), 255)
            if d > 6.3:  # dunkler Rand
                c = (int(r * 0.35), int(g * 0.35), int(b * 0.35), 255)
            img.putpixel((x, y), c)
    for (x, y) in ((4, 4), (5, 4), (4, 5)):  # Glanzpunkt
        img.putpixel((x, y), (255, 255, 255, 230))
    img.save(os.path.join(out, name + ".png"))
print("ok")
