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

	/** Deckkraft der drei Glüh-Ringe von innen nach außen. */
	private static final int[] GLOW_ALPHA = {0xC8, 0x5A, 0x22};

	/**
	 * Weicher Schein außen um das Rechteck (x, y, w, h): drei Ringe mit abnehmender Deckkraft und
	 * abgerundeten Ecken. Sollte vor dem Inhalt gezeichnet werden, damit z. B. Rezeptbuch-Reiter darüber liegen.
	 */
	public static void outline(GuiGraphics g, int x, int y, int w, int h) {
		int rgb = color() & 0xFFFFFF;
		for (int i = 0; i < GLOW_ALPHA.length; i++) {
			int d = i + 1;
			int c = (GLOW_ALPHA[i] << 24) | rgb;
			// Ecken auslassen → runde Kanten wie bei den Texturen
			g.fill(x - d + 1, y - d, x + w + d - 1, y - d + 1, c);
			g.fill(x - d + 1, y + h + d - 1, x + w + d - 1, y + h + d, c);
			g.fill(x - d, y - d + 1, x - d + 1, y + h + d - 1, c);
			g.fill(x + w + d - 1, y - d + 1, x + w + d, y + h + d - 1, c);
		}
	}
}
