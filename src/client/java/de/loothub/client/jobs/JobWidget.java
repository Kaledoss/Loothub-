package de.loothub.client.jobs;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.Fmt;

/**
 * Das Job-Widget im HUD: eine Liste „Bezeichnung: Wert“.
 * Position und Sichtbarkeit kommen aus {@link WidgetConfig};
 * verschoben wird es im {@link JobWidgetEditScreen} (Einstellungen /loothub → „Position ändern“).
 */
public final class JobWidget {
	private static final int PADDING = 5;
	private static final int LINE_H = 10;
	private static final int JOB_ROW_H = 17;
	private static final int ICON_W = 18;

	private static final int BACKGROUND = 0xC0120A0C;
	private static final int BORDER = 0xFFB3262E;
	private static final int LABEL = 0xFFE07A7A;
	private static final int VALUE = 0xFFFFD66B;
	private static final int JOB = 0xFFFFAA33;
	private static final int MUTED = 0xFFAAAAAA;

	/** Eine Zeile des Widgets. */
	public record Row(String label, String value, int color) {
	}

	/** Fertig aufbereitete Anzeige – einmal berechnet, damit Größe und Zeichnen übereinstimmen. */
	public record Layout(ItemStack icon, String job, String state, List<Row> rows, int labelW, int width, int height) {
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

	private static String tr(String key) {
		return Component.translatable(key).getString();
	}

	private static String orDash(double v) {
		return Double.isNaN(v) ? "–" : Fmt.exact(v);
	}

	public static Layout layout(Font font, JobTracker.Snapshot s) {
		String state = "";
		if (s.demo()) {
			state = tr("loothub.job.demo");
		} else if (s.paused()) {
			state = tr("loothub.job.paused");
		}

		List<Row> rows = new ArrayList<>();
		rows.add(new Row(tr("loothub.job.row.level"), s.level() >= 0 ? String.valueOf(s.level()) : "–", VALUE));
		rows.add(new Row(tr("loothub.job.row.progress"),
				Double.isNaN(s.progress()) ? "–" : Fmt.exact(s.progress()) + " %", VALUE));
		rows.add(new Row(tr("loothub.job.row.time"), Fmt.clock(s.trackingMs()), VALUE));
		rows.add(new Row(tr("loothub.job.row.money"), Fmt.exact(s.sessionMoney()), VALUE));
		rows.add(new Row(tr("loothub.job.row.money_rate"), orDash(s.moneyPerHour()), VALUE));
		rows.add(new Row(tr("loothub.job.row.xp"), Fmt.exact(s.sessionXp()), VALUE));
		rows.add(new Row(tr("loothub.job.row.xp_rate"), orDash(s.xpPerHour()), VALUE));

		int labelW = font.width(tr("loothub.job.row.job"));
		for (Row r : rows) {
			labelW = Math.max(labelW, font.width(r.label()));
		}
		labelW += 4;

		int contentW = labelW + ICON_W + font.width(s.job()) + font.width(state);
		for (Row r : rows) {
			contentW = Math.max(contentW, labelW + font.width(r.value()));
		}
		int width = contentW + 2 * PADDING;
		int height = PADDING + JOB_ROW_H + rows.size() * LINE_H + PADDING - 1;
		ItemStack icon = new ItemStack(JobHistoryScreen.jobIcon(s.job()));
		return new Layout(icon, s.job(), state, rows, labelW, width, height);
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
		g.fill(x, y, x + 1, y + l.height(), BORDER);
		g.fill(x, y, x + l.width(), y + 1, BORDER);

		int lx = x + PADDING;
		int vx = lx + l.labelW();
		int cy = y + PADDING;

		// Job-Zeile mit Symbol
		g.drawString(font, tr("loothub.job.row.job"), lx, cy + 4, LABEL, true);
		g.renderItem(l.icon(), vx, cy - 1);
		g.drawString(font, l.job(), vx + ICON_W, cy + 4, JOB, true);
		if (!l.state().isEmpty()) {
			g.drawString(font, l.state(), vx + ICON_W + font.width(l.job()), cy + 4, MUTED, true);
		}
		cy += JOB_ROW_H;

		for (Row r : l.rows()) {
			g.drawString(font, r.label(), lx, cy, LABEL, true);
			g.drawString(font, r.value(), vx, cy, r.color(), true);
			cy += LINE_H;
		}
	}
}
