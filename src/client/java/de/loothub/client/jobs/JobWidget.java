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
	private static final int PADDING = 6;
	private static final int LINE_H = 10;
	private static final int JOB_ROW_H = 17;
	private static final int ICON_W = 18;

	private static final int BACKGROUND = 0xC0120A0C;
	private static final int BORDER = 0xFFB3262E;
	private static final int EDGE = 0xE0000000;
	private static final int BEVEL_HI = 0x905C262C;
	private static final int BEVEL_SH = 0x90080405;
	private static final int LABEL = 0xFFE07A7A;
	private static final int VALUE = 0xFFFFD66B;
	private static final int JOB = 0xFFFFAA33;
	private static final int MUTED = 0xFFAAAAAA;

	/** Zeilen-IDs in Anzeigereihenfolge; Sprachschlüssel: loothub.job.row.&lt;id&gt;. In den Einstellungen einzeln schaltbar. */
	public static final List<String> ROW_IDS = List.of("job", "level", "progress", "time", "money", "money_rate", "xp", "xp_rate",
			"money_total", "xp_total", "treasure", "inv_market", "inv_shards");

	/** Eine Zeile des Widgets. */
	public record Row(String label, String value, int color) {
	}

	/** Fertig aufbereitete Anzeige – einmal berechnet, damit Größe und Zeichnen übereinstimmen. */
	public record Layout(boolean showJob, ItemStack icon, String job, String state, List<Row> rows, int labelW, int width, int height) {
	}

	/** Platzhalter, solange in dieser Sitzung noch keine Job-Aktion erkannt wurde. */
	private static final JobTracker.Snapshot EMPTY = new JobTracker.Snapshot("–", -1, Double.NaN, 0, 0,
			Double.NaN, Double.NaN, -1, 0, false, false, 0, 0);

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
			snap = EMPTY; // immer sichtbar – ohne Job-Aktion mit leeren Werten
		}
		Layout layout = layout(mc.font, snap);
		if (!layout.showJob() && layout.rows().isEmpty()) {
			return; // alle Zeilen ausgeschaltet
		}
		int[] pos = position(g.guiWidth(), g.guiHeight(), layout);
		drawScaled(g, mc.font, layout, pos[0], pos[1], scale());
	}

	public static final double MIN_SCALE = 0.5;
	public static final double MAX_SCALE = 2.5;

	public static double clampScale(double s) {
		return Double.isNaN(s) || s <= 0 ? 1.0 : Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
	}

	public static double scale() {
		return clampScale(WidgetConfig.get().jobWidgetScale);
	}

	/** Breite/Höhe auf dem Bildschirm (mit Größe). */
	public static int scaledWidth(Layout l) {
		return (int) Math.ceil(l.width() * scale());
	}

	public static int scaledHeight(Layout l) {
		return (int) Math.ceil(l.height() * scale());
	}

	/** Zeichnet das Widget mit der eingestellten Größe; x/y ist die linke obere Ecke auf dem Bildschirm. */
	public static void drawScaled(GuiGraphics g, Font font, Layout l, int x, int y, double scale) {
		if (Math.abs(scale - 1.0) < 0.001) {
			draw(g, font, l, x, y);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale((float) scale, (float) scale);
		draw(g, font, l, 0, 0);
		g.pose().popMatrix();
	}

	private static String tr(String key) {
		return Component.translatable(key).getString();
	}

	private static String orDash(double v, String unit) {
		return Double.isNaN(v) ? "–" : Fmt.exact(v) + unit;
	}

	public static Layout layout(Font font, JobTracker.Snapshot s) {
		String state = "";
		if (s.demo()) {
			state = tr("loothub.job.demo");
		}
		// In der Pause bleibt nur die Tracking-Zeit stehen, kein Hinweis „(Pause)“

		WidgetConfig c = WidgetConfig.get();
		boolean showJob = c.jobRowVisible("job");
		List<Row> rows = new ArrayList<>();
		addRow(rows, c, "level", s.level() >= 0 ? String.valueOf(s.level()) : "–");
		if (c.jobRowVisible("progress")) {
			rows.add(new Row(tr("loothub.job.row.progress"),
					Double.isNaN(s.progress()) ? "–" : Fmt.exact(s.progress()) + " %", progressColor(s.progress())));
		}
		addRow(rows, c, "time", Fmt.clock(s.trackingMs()));
		addRow(rows, c, "money", Fmt.exact(s.sessionMoney()) + " $");
		addRow(rows, c, "money_rate", orDash(s.moneyPerHour(), " $"));
		addRow(rows, c, "xp", Fmt.exact(s.sessionXp()) + " XP");
		addRow(rows, c, "xp_rate", orDash(s.xpPerHour(), " XP"));
		addRow(rows, c, "money_total", Fmt.exact(s.totalMoney()) + " $");
		addRow(rows, c, "xp_total", Fmt.exact(s.totalXp()) + " XP");
		if (c.jobRowVisible("treasure")) {
			rows.add(new Row(tr("loothub.job.row.treasure"), Fmt.whole((long) c.treasureXp) + " / "
					+ Fmt.whole((long) JobTracker.TREASURE_STEP) + " XP · " + c.treasuresFound + "× gefunden"
					+ (TreasureWatcher.recentlyFound() ? " ✦" : ""), TreasureWatcher.recentlyFound() ? 0xFFFFE860 : 0xFFFFC040));
		}
		if (c.jobRowVisible("inv_market")) {
			rows.add(new Row(tr("loothub.job.row.inv_market"), Fmt.exact(de.loothub.client.quick.InventoryValue.market()) + " $", 0xFF55E06A));
		}
		if (c.jobRowVisible("inv_shards")) {
			rows.add(new Row(tr("loothub.job.row.inv_shards"), Fmt.exact(de.loothub.client.quick.InventoryValue.shards()) + " OPShards", 0xFF55D5E0));
		}

		int labelW = showJob ? font.width(tr("loothub.job.row.job")) : 0;
		for (Row r : rows) {
			labelW = Math.max(labelW, font.width(r.label()));
		}
		labelW += 4;

		int contentW = showJob ? labelW + ICON_W + font.width(s.job()) + font.width(state) : 40;
		for (Row r : rows) {
			contentW = Math.max(contentW, labelW + font.width(r.value()));
		}
		int width = contentW + 2 * PADDING;
		int height = PADDING + (showJob ? JOB_ROW_H : 0) + rows.size() * LINE_H + PADDING - 1;
		ItemStack icon = new ItemStack(JobHistoryScreen.jobIcon(s.job()));
		return new Layout(showJob, icon, s.job(), state, rows, labelW, width, height);
	}

	/** Farbe für den Fortschritt: 0 % orange → 50 % gelb → 100 % grün, fließend. */
	static int progressColor(double percent) {
		if (Double.isNaN(percent)) {
			return VALUE;
		}
		double t = Math.max(0, Math.min(100, percent)) / 100.0;
		int[] orange = {0xFF, 0x8A, 0x2A};
		int[] yellow = {0xFF, 0xE0, 0x40};
		int[] green = {0x55, 0xE0, 0x6A};
		int[] a = t < 0.5 ? orange : yellow;
		int[] b = t < 0.5 ? yellow : green;
		double k = t < 0.5 ? t / 0.5 : (t - 0.5) / 0.5;
		int r = (int) Math.round(a[0] + (b[0] - a[0]) * k);
		int g = (int) Math.round(a[1] + (b[1] - a[1]) * k);
		int bl = (int) Math.round(a[2] + (b[2] - a[2]) * k);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	private static void addRow(List<Row> rows, WidgetConfig c, String id, String value) {
		if (c.jobRowVisible(id)) {
			rows.add(new Row(tr("loothub.job.row." + id), value, VALUE));
		}
	}

	/** Bildschirmposition aus der relativen Position in der Config. */
	public static int[] position(int screenW, int screenH, Layout layout) {
		WidgetConfig c = WidgetConfig.get();
		int x = (int) Math.round(c.jobWidgetX * Math.max(0, screenW - scaledWidth(layout)));
		int y = (int) Math.round(c.jobWidgetY * Math.max(0, screenH - scaledHeight(layout)));
		return new int[]{x, y};
	}

	/**
	 * Rahmen im Stil des Ressourcenpakets: abgerundete Ecken, dunkle Außenlinie, weiche Innenkante
	 * und ein schmaler roter Streifen oben.
	 */
	private static void frame(GuiGraphics g, int x, int y, int w, int h) {
		int r = x + w;
		int b = y + h;
		// Fläche ohne die Eckpixel
		g.fill(x + 1, y, r - 1, b, de.loothub.client.Theme.bg(BACKGROUND));
		g.fill(x, y + 1, x + 1, b - 1, de.loothub.client.Theme.bg(BACKGROUND));
		g.fill(r - 1, y + 1, r, b - 1, de.loothub.client.Theme.bg(BACKGROUND));
		// Außenlinie, Ecken frei → rund
		g.fill(x + 2, y, r - 2, y + 1, de.loothub.client.Theme.bg(EDGE));
		g.fill(x + 2, b - 1, r - 2, b, de.loothub.client.Theme.bg(EDGE));
		g.fill(x, y + 2, x + 1, b - 2, de.loothub.client.Theme.bg(EDGE));
		g.fill(r - 1, y + 2, r, b - 2, de.loothub.client.Theme.bg(EDGE));
		g.fill(x + 1, y + 1, x + 2, y + 2, de.loothub.client.Theme.bg(EDGE));
		g.fill(r - 2, y + 1, r - 1, y + 2, de.loothub.client.Theme.bg(EDGE));
		g.fill(x + 1, b - 2, x + 2, b - 1, de.loothub.client.Theme.bg(EDGE));
		g.fill(r - 2, b - 2, r - 1, b - 1, de.loothub.client.Theme.bg(EDGE));
		// Innenkante: oben/links etwas heller, unten/rechts dunkler
		g.fill(x + 2, y + 1, r - 2, y + 2, de.loothub.client.Theme.bg(BEVEL_HI));
		g.fill(x + 1, y + 2, x + 2, b - 2, de.loothub.client.Theme.bg(BEVEL_HI));
		g.fill(x + 2, b - 2, r - 2, b - 1, de.loothub.client.Theme.bg(BEVEL_SH));
		g.fill(r - 2, y + 2, r - 1, b - 2, de.loothub.client.Theme.bg(BEVEL_SH));
		// roter Streifen oben, mit weichen Enden
		g.fill(x + 4, y + 2, r - 4, y + 3, de.loothub.client.LoothubScreen.RED);
		g.fill(x + 3, y + 2, x + 4, y + 3, 0x80B3262E);
		g.fill(r - 4, y + 2, r - 3, y + 3, 0x80B3262E);
	}

	public static void draw(GuiGraphics g, Font font, Layout l, int x, int y) {
		frame(g, x, y, l.width(), l.height());

		int lx = x + PADDING;
		int vx = lx + l.labelW();
		int cy = y + PADDING;

		// Job-Zeile mit Symbol
		if (l.showJob()) {
			g.drawString(font, tr("loothub.job.row.job"), lx, cy + 4, LABEL, true);
			g.renderItem(l.icon(), vx, cy - 1);
			g.drawString(font, l.job(), vx + ICON_W, cy + 4, JOB, true);
			if (!l.state().isEmpty()) {
				g.drawString(font, l.state(), vx + ICON_W + font.width(l.job()), cy + 4, MUTED, true);
			}
			cy += JOB_ROW_H;
		}

		for (Row r : l.rows()) {
			g.drawString(font, r.label(), lx, cy, LABEL, true);
			g.drawString(font, r.value(), vx, cy, r.color(), true);
			cy += LINE_H;
		}
	}
}
