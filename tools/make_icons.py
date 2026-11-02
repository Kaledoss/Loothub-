"""Kleine 16x16-Icons für die Befehls-Leiste: Pokal (Erfolge)."""
import sys
from PIL import Image, ImageDraw
out = sys.argv[1]
im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
G1 = (255, 214, 64, 255)   # Gold hell
G2 = (222, 160, 30, 255)   # Gold mittel
G3 = (150, 96, 14, 255)    # Gold dunkel
O = (40, 24, 6, 255)       # Kontur
# Schale
d.rectangle([4, 2, 11, 7], fill=G2)
d.rectangle([5, 2, 7, 6], fill=G1)
d.rectangle([5, 8, 10, 8], fill=G2)
d.rectangle([6, 9, 9, 9], fill=G3)
# Henkel
d.rectangle([2, 3, 3, 5], fill=G3); d.point((3, 6), fill=G3)
d.rectangle([12, 3, 13, 5], fill=G3); d.point((12, 6), fill=G3)
# Stiel und Fuß
d.rectangle([7, 10, 8, 11], fill=G2)
d.rectangle([5, 12, 10, 12], fill=G2)
d.rectangle([4, 13, 11, 14], fill=G3)
d.rectangle([5, 13, 7, 13], fill=G1)
# Kontur
for (x, y) in [(4,1),(5,1),(6,1),(7,1),(8,1),(9,1),(10,1),(11,1),(3,2),(12,2),(3,7),(12,7),(4,8),(11,8),(5,9),(10,9),(6,10),(9,10),(6,11),(9,11),(4,12),(11,12),(3,13),(12,13),(3,14),(12,14),(4,15),(5,15),(6,15),(7,15),(8,15),(9,15),(10,15),(11,15),(1,3),(1,4),(1,5),(2,2),(2,6),(14,3),(14,4),(14,5),(13,2),(13,6)]:
    if im.getpixel((x, y))[3] == 0:
        d.point((x, y), fill=O)
im.save(out)
big = im.resize((128, 128), Image.NEAREST); big.save(sys.argv[2])
print("ok")
