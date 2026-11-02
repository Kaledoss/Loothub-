"""Schwarz-roter Hintergrund für die Effekt-Kästchen neben dem Inventar (32x32, 9-Slice mit 4 px Rand)."""
import json, os, sys
from PIL import Image, ImageDraw
out_dir = sys.argv[1]; preview = sys.argv[2]
S = 32
im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
OUT = (0, 0, 0, 255); BG = (28, 18, 20, 255); HI = (92, 38, 44, 255); SH = (10, 5, 6, 255); RED = (176, 38, 46, 255)
d.rectangle([1, 1, S - 2, S - 2], fill=BG)
d.line([(2, 0), (S - 3, 0)], fill=OUT); d.line([(2, S - 1), (S - 3, S - 1)], fill=OUT)
d.line([(0, 2), (0, S - 3)], fill=OUT); d.line([(S - 1, 2), (S - 1, S - 3)], fill=OUT)
for p in [(1, 1), (S - 2, 1), (1, S - 2), (S - 2, S - 2)]:
    im.putpixel(p, OUT)
d.line([(2, 1), (S - 3, 1)], fill=RED)            # rote Oberkante
d.line([(1, 2), (1, S - 3)], fill=HI)              # helle linke Kante
d.line([(2, S - 2), (S - 3, S - 2)], fill=SH)      # dunkle Unterkante
d.line([(S - 2, 2), (S - 2, S - 3)], fill=SH)
os.makedirs(out_dir, exist_ok=True)
meta = {"gui": {"scaling": {"type": "nine_slice", "width": S, "height": S, "border": 4}}}
for name in ["effect_background", "effect_background_large", "effect_background_small"]:
    im.save(os.path.join(out_dir, name + ".png"))
    with open(os.path.join(out_dir, name + ".png.mcmeta"), "w") as f:
        json.dump(meta, f, indent=2)
# Vorschau: auf 140x32 gedehnt (wie ein Effekt mit langem Namen), 4-fach
prev = Image.new("RGBA", (140, 32), (0, 0, 0, 0))
b = 4
def part(x0, y0, x1, y1):
    return im.crop((x0, y0, x1, y1))
W, H = 140, 32
prev.paste(part(0, 0, b, b), (0, 0)); prev.paste(part(S - b, 0, S, b), (W - b, 0))
prev.paste(part(0, S - b, b, S), (0, H - b)); prev.paste(part(S - b, S - b, S, S), (W - b, H - b))
prev.paste(part(b, 0, S - b, b).resize((W - 2 * b, b)), (b, 0)); prev.paste(part(b, S - b, S - b, S).resize((W - 2 * b, b)), (b, H - b))
prev.paste(part(0, b, b, S - b).resize((b, H - 2 * b)), (0, b)); prev.paste(part(S - b, b, S, S - b).resize((b, H - 2 * b)), (W - b, b))
prev.paste(part(b, b, S - b, S - b).resize((W - 2 * b, H - 2 * b)), (b, b))
big = Image.new("RGBA", (W * 4 + 20, H * 4 + 20), (60, 70, 60, 255))
big.paste(prev.resize((W * 4, H * 4), Image.NEAREST), (10, 10), prev.resize((W * 4, H * 4), Image.NEAREST))
big.save(preview)
print("ok")
