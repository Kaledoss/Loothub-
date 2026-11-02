package de.loothub.client.jobs;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import de.loothub.client.Fmt;

/**
 * Das Job-Widget im HUD. Position und Sichtbarkeit kommen aus {@link WidgetConfig};
 * verschoben wird es im {@link JobWidgetEditScreen} (Einstellungen /loothub → „Position ändern“).
 */
public final class JobWidget {
	private static final int MIN_WIDTH = 150;
	private static final int PADDING = 5;
	private static final int LINE_H = 10;
	private static final int BAR_H = 5;

	private static final int BACKGROUND = 0xC0120A0C;
	private static final int BORDER = 0xFFB3262E;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFAAAAAA;
	private static final int BAR_BG = 0x60FFFFFF;
	private static final int BAR_FG = 0xFFE23B3B;

	/** Fertig aufbereitete Zeilen – einmal berechnet, damit Größe und Zeichnen übereinstimmen. */
	public record Layout(Component title, String progressText, double progress, List<Component> lines, int width, int height) {
	}

	private JobWidget() {
	}

	/** HUD-Einstieg (über HudElementRegistry registriert). */
	public static void renderHud(GuiGraphics g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (!WidgetConfig.get().jobWidgetEnabled || mc.options.hideGui || mc.player == null) {
			return;
		}
		if (mc.screen instanceof JobWidgetEditScreen) {
			return; // der Editor zeichnet das Widget selbst
		}
		JobTracker.Snapshot snap = JobTracker.snapshot();
		if (snap == null) {
			return; // noch keine Job-Aktion in dieser Sitzung
		}
		Layout layout = layout(mc.font, snap);
		int[] pos = position(g.guiWidth(), g.guiHeight(), layout);
		draw(g, mc.font, layout, pos[0], pos[1]);
	}

	public static Layout layout(Font font, JobTracker.Snapshot s) {
		Component title = Component.literal(s.job()).withStyle(ChatFormatting.GOLD);
		if (s.level() >= 0) {
			title = Component.empty().append(title)
					.append(Component.translatable("loothub.job.level", s.level()).withStyle(ChatFormatting.WHITE));
		}
		if (s.paused()) {
			title = Component.empty().append(title)
					.append(Component.translatable("loothub.job.paused").withStyle(ChatFormatting.GRAY));
		}
		if (s.demo()) {
			title = Component.empty().append(title)
					.append(Component.translatable("loothub.job.demo").withStyle(ChatFormatting.GRAY));
		}

		String progressText = Double.isNaN(s.progress()) ? "" : Fmt.compact(s.progress()) + " %";

		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("loothub.job.session",
				Fmt.compact(s.sessionXp()), Fmt.compact(s.sessionMoney())).withStyle(ChatFormatting.WHITE));
		String xpRate = Double.isNaN(s.xpPerHour()) ? "–" : Fmt.compact(s.xpPerHour());
		String moneyRate = Double.isNaN(s.moneyPerHour()) ? "–" : Fmt.compact(s.moneyPerHour());
		lines.add(Component.translatable("loothub.job.rate", xpRate, moneyRate).withStyle(ChatFormatting.WHITE));
		String eta = s.etaMs() < 0 ? "–" : "~" + Fmt.duration(s.etaMs());
		lines.add(Component.translatable("loothub.job.eta", eta).withStyle(ChatFormatting.GREEN));

		int width = MIN_WIDTH;
		width = Math.max(width, font.width(title) + 2 * PADDING);
		for (Component line : lines) {
			width = Math.max(width, font.width(line) + 2 * PADDING);
		}
		int height = PADDING + LINE_H + (BAR_H + 4) + lines.size() * LINE_H + PADDING - 2;
		return new Layout(title, progressText, s.progress(), lines, width, height);
	}

	/** Bildschirmposition aus der relativen Position in der Config. */
	public static int[] position(int screenW, int screenH, Layout layout) {
		WidgetConfig c = WidgetConfig.get();
		int x = (int) Math.round(c.jobWidgetX * Math.max(0, screenW - layout.width()));
		int y = (int) Math.round(c.jobWidgetY * Math.max(0, screenH - layout.height()));
		return new int[]{x, y};
	}

	public static void draw(GuiGraphics g, Font font, Layout l, int x, int y) {
		g.fill(x, y, x + l.width(), y + l.height(), BACKGROUND);
		g.fill(x, y, x + l.width(), y + 1, BORDER);

		int cy = y + PADDING;
		g.drawString(font, l.title(), x + PADDING, cy, TEXT, true);
		cy += LINE_H;

		// Fortschrittsbalken mit Prozentzahl
		int textW = l.progressText().isEmpty() ? 0 : font.width(l.progressText()) + 4;
		int barX = x + PADDING;
		int barW = l.width() - 2 * PADDING - textW;
		int barY = cy + 1;
		g.fill(barX, barY, barX + barW, barY + BAR_H, BAR_BG);
		if (!Double.isNaN(l.progress())) {
			int filled = (int) Math.round(barW * Math.max(0, Math.min(100, l.progress())) / 100.0);
			g.fill(barX, barY, barX + filled, barY + BAR_H, BAR_FG);
			g.drawString(font, l.progressText(), barX + barW + 4, cy, MUTED, true);
		}
		cy += BAR_H + 4;

		for (Component line : l.lines()) {
			g.drawString(font, line, x + PADDING, cy, TEXT, true);
			cy += LINE_H;
		}
	}
}
