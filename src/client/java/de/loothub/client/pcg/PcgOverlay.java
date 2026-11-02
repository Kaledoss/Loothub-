package de.loothub.client.pcg;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import de.loothub.client.Fmt;
import de.loothub.client.jobs.WidgetConfig;

/** HUD oben rechts: Name des wilden Pokémon und verbleibende Fangzeit (bzw. Ergebnis). */
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
		if (!WidgetConfig.get().pcgEnabled || mc.options.hideGui) {
			return;
		}
		PcgState.Spawn s = PcgState.current();
		if (s == null) {
			return;
		}
		Font font = mc.font;
		String title = "Wildes Pokémon";
		String time;
		int timeColor;
		if (s.result() != null) {
			time = s.result().equals("gefangen") ? "Gefangen!" : "Entkommen";
			timeColor = s.result().equals("gefangen") ? TIME_OK : TIME_LOW;
		} else {
			long ms = s.remainingMs();
			time = ms <= 0 ? "Zeit um" : Fmt.clock(ms);
			timeColor = ms <= 15_000 ? TIME_LOW : TIME_OK;
		}
		int w = Math.max(font.width(title) + 8 + font.width(time), font.width(s.name())) + 14;
		int h = 32;
		int x = g.guiWidth() - w - 4;
		int y = 4;

		g.fill(x + 1, y, x + w - 1, y + h, BG);
		g.fill(x, y + 1, x + 1, y + h - 1, BG);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, BG);
		g.fill(x + 2, y, x + w - 2, y + 1, EDGE);
		g.fill(x + 2, y + h - 1, x + w - 2, y + h, EDGE);
		g.fill(x + 4, y + 2, x + w - 4, y + 3, RED);

		g.drawString(font, title, x + 7, y + 7, LABEL, true);
		g.drawString(font, time, x + w - 7 - font.width(time), y + 7, timeColor, true);
		g.drawString(font, s.name(), x + 7, y + 19, NAME, true);

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
