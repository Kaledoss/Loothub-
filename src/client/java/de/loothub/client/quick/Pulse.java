package de.loothub.client.quick;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/** Langsam pulsierender roter Rand (ca. 2,4 s pro Puls), abschaltbar in den Einstellungen. */
public final class Pulse {
	private static final int DARK = 0x5A1018;
	private static final int BRIGHT = 0xFF3040;

	private Pulse() {
	}

	/** Aktuelle Randfarbe (ARGB). Ohne Pulsieren: festes Dunkelrot. */
	public static int color() {
		if (!WidgetConfig.get().pulseBorders) {
			return LoothubScreen.RED_DARK;
		}
		double t = (Math.sin(System.currentTimeMillis() / 380.0) + 1) / 2;
		int r = (int) Mth.lerp(t, (DARK >> 16) & 0xFF, (BRIGHT >> 16) & 0xFF);
		int gr = (int) Mth.lerp(t, (DARK >> 8) & 0xFF, (BRIGHT >> 8) & 0xFF);
		int b = (int) Mth.lerp(t, DARK & 0xFF, BRIGHT & 0xFF);
		return 0xFF000000 | (r << 16) | (gr << 8) | b;
	}

	/** 1 px breiter Rahmen direkt außen um das Rechteck (x, y, w, h). */
	public static void outline(GuiGraphics g, int x, int y, int w, int h) {
		int c = color();
		g.fill(x - 1, y - 1, x + w + 1, y, c);
		g.fill(x - 1, y + h, x + w + 1, y + h + 1, c);
		g.fill(x - 1, y, x, y + h, c);
		g.fill(x + w, y, x + w + 1, y + h, c);
	}
}
