package de.loothub.client;

import java.util.List;
import java.util.Locale;

import de.loothub.client.util.Perf;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Leistung: welche Loothub-Funktion wie viel Zeit kostet (Durchschnitt der letzten 10 Sekunden). Hilft beim Finden von
 * Rucklern – und zeigt, ob Loothub überhaupt der Grund ist.
 */
public class PerfScreen extends Screen {
	private final Screen parent;

	public PerfScreen(Screen parent) {
		super(Component.translatable("loothub.perf.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(width / 2 - 50, height - 26, 100, 20).build());
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float d) {
		super.render(g, mx, my, d);
		g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
		List<Perf.Entry> list = Perf.snapshot();
		double total = 0;
		for (Perf.Entry e : list) {
			total += e.msPerSec();
		}
		int fps = Math.max(1, minecraft.getFps());
		double frameMs = 1000.0 / fps;
		// Anteil: Loothub-Zeit je Sekunde im Verhältnis zur ganzen Sekunde
		g.drawCenteredString(font, Component.translatable("loothub.perf.summary", fps,
				String.format(Locale.ROOT, "%.1f", total), String.format(Locale.ROOT, "%.1f", total / 10.0)), width / 2, 24, 0xFFF2C14E);
		g.drawCenteredString(font, Component.translatable("loothub.perf.info"), width / 2, 36, 0xFF8A8A8A);
		int x = Math.max(10, width / 2 - 200), y = 54;
		g.drawString(font, Component.translatable("loothub.perf.col.name"), x, y, 0xFFB0B0B0);
		g.drawString(font, Component.translatable("loothub.perf.col.sec"), x + 220, y, 0xFFB0B0B0);
		g.drawString(font, Component.translatable("loothub.perf.col.max"), x + 300, y, 0xFFB0B0B0);
		y += 12;
		if (list.isEmpty()) {
			g.drawString(font, Component.translatable("loothub.perf.wait"), x, y, 0xFF8A8A8A);
			return;
		}
		for (Perf.Entry e : list) {
			if (y > height - 40) {
				break;
			}
			int color = e.msPerSec() > 20 ? 0xFFFF6666 : e.msPerSec() > 5 ? 0xFFF2C14E : 0xFFFFFFFF;
			g.drawString(font, font.plainSubstrByWidth(e.name(), 215), x, y, color);
			g.drawString(font, String.format(Locale.ROOT, "%.2f ms", e.msPerSec()), x + 220, y, color);
			// langsamster Einzelaufruf: über einem Bild (Frame) wäre ein spürbarer Ruckler
			int mc = e.maxMs() > frameMs ? 0xFFFF6666 : 0xFFB0B0B0;
			g.drawString(font, String.format(Locale.ROOT, "%.1f ms", e.maxMs()), x + 300, y, mc);
			y += 11;
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
