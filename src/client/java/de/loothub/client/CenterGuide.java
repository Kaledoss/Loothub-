package de.loothub.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Kreuz über dem ganzen Fenster beim Verschieben von HUDs: zeigt die waagerechte und senkrechte Mitte (gestrichelt),
 * damit man Anzeigen genau mittig setzen kann. Liegt etwas genau auf der Mitte, leuchtet die Linie auf.
 */
public final class CenterGuide {
	/** Bis zu so vielen Pixeln wird beim Ziehen auf die Mitte eingerastet. */
	public static final int SNAP = 4;

	private CenterGuide() {
	}

	public static void draw(GuiGraphics g, int width, int height, boolean snapX, boolean snapY) {
		int cx = width / 2, cy = height / 2;
		int colX = snapX ? 0xE0FFD040 : 0x60FFFFFF;
		int colY = snapY ? 0xE0FFD040 : 0x60FFFFFF;
		for (int y = 0; y < height; y += 6) {
			g.fill(cx, y, cx + 1, Math.min(height, y + 3), colX);
		}
		for (int x = 0; x < width; x += 6) {
			g.fill(x, cy, Math.min(width, x + 3), cy + 1, colY);
		}
	}

	public static void draw(GuiGraphics g, int width, int height) {
		draw(g, width, height, false, false);
	}

	/** Linke/obere Kante so verschieben, dass die Mitte der Anzeige auf der Fenstermitte einrastet (sonst unverändert). */
	public static double snap(double pos, int size, int total) {
		double center = total / 2.0 - size / 2.0;
		return Math.abs(pos - center) <= SNAP ? center : pos;
	}

	public static boolean onCenter(int pos, int size, int total) {
		return Math.abs(pos + size / 2.0 - total / 2.0) <= 1.0;
	}
}
