package de.loothub.client.pcg;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import de.loothub.client.Fmt;
import de.loothub.client.jobs.WidgetConfig;

/** HUD (Standard oben rechts, verschiebbar): Name des wilden Pokémon und verbleibende Fangzeit (bzw. Ergebnis). */
public final class PcgOverlay {
	private static final int BG = 0xC0120A0C;
	private static final int EDGE = 0xE0000000;
	private static final int RED = 0xFFB3262E;
	private static final int LABEL = 0xFFE07A7A;
	private static final int NAME = 0xFFFFD66B;
	private static final int TIME_OK = 0xFF55E06A;
	private static final int TIME_LOW = 0xFFFF5050;

	private PcgOverlay() {
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!WidgetConfig.get().pcgEnabled || mc.options.hideGui || mc.screen instanceof PcgOverlayEditScreen) {
			return;
		}
		PcgState.Spawn s = PcgState.current();
		if (s == null) {
			return;
		}
		int[] size = size(mc.font, s);
		int[] pos = position(g.guiWidth(), g.guiHeight(), size[0], size[1]);
		draw(g, mc.font, s, pos[0], pos[1]);
	}

	private static String title() {
		return "Wildes Pokémon";
	}

	private static String time(PcgState.Spawn s) {
		if (s.result() != null) {
			return s.result().equals("gefangen") ? "Gefangen!" : "Entkommen";
		}
		long ms = s.remainingMs();
		return ms <= 0 ? "Zeit um" : Fmt.clock(ms);
	}

	/** Breite und Höhe der Anzeige. */
	public static int[] size(Font font, PcgState.Spawn s) {
		int w = Math.max(font.width(title()) + 8 + font.width("Entkommen"), font.width(GermanNames.of(s.name()))) + 14;
		return new int[]{w, 32};
	}

	/** Bildschirmposition aus der relativen Position in der Config (Standard: oben rechts). */
	public static int[] position(int screenW, int screenH, int w, int h) {
		WidgetConfig c = WidgetConfig.get();
		int x = (int) Math.round(c.pcgOverlayX * Math.max(0, screenW - w - 8)) + 4;
		int y = (int) Math.round(c.pcgOverlayY * Math.max(0, screenH - h - 8)) + 4;
		return new int[]{x, y};
	}

	public static void draw(GuiGraphics g, Font font, PcgState.Spawn s, int x, int y) {
		int[] size = size(font, s);
		int w = size[0];
		int h = size[1];
		String time = time(s);
		int timeColor;
		if (s.result() != null) {
			timeColor = s.result().equals("gefangen") ? TIME_OK : TIME_LOW;
		} else {
			timeColor = s.remainingMs() <= 15_000 ? TIME_LOW : TIME_OK;
		}

		g.fill(x + 1, y, x + w - 1, y + h, BG);
		g.fill(x, y + 1, x + 1, y + h - 1, BG);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, BG);
		g.fill(x + 2, y, x + w - 2, y + 1, EDGE);
		g.fill(x + 2, y + h - 1, x + w - 2, y + h, EDGE);
		g.fill(x + 4, y + 2, x + w - 4, y + 3, RED);

		g.drawString(font, title(), x + 7, y + 7, LABEL, true);
		g.drawString(font, time, x + w - 7 - font.width(time), y + 7, timeColor, true);
		g.drawString(font, GermanNames.of(s.name()), x + 7, y + 19, NAME, true);

		// Restzeit-Balken unten
		if (s.result() == null) {
			long total = Math.max(1, s.end() - s.start());
			int bw = w - 14;
			int filled = (int) (bw * s.remainingMs() / total);
			g.fill(x + 7, y + h - 4, x + 7 + bw, y + h - 3, 0x40FFFFFF);
			g.fill(x + 7, y + h - 4, x + 7 + filled, y + h - 3, timeColor);
		}
	}
}
