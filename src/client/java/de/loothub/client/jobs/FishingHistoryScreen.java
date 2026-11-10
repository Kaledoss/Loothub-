package de.loothub.client.jobs;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import de.loothub.client.ConfirmScreen;
import de.loothub.client.LoothubScreen;

/**
 * Reiter „Angelverlauf“: alle Fänge eines Zeitraums (Tag, Woche, Monat, alles) mit ◀/▶ zum Blättern, Kennzahlen,
 * Verteilung nach Seltenheit und darunter die Liste – einzeln mit Uhrzeit oder zusammengefasst nach Fisch.
 */
public class FishingHistoryScreen extends LoothubScreen {
	private static final int CARD = 0xE01C1315;
	private static final int CARD_EDGE = 0xFF3A1F23;
	private static final int GOLD = 0xFFFFC040;
	private static final int ROW_H = 12;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss");
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN);

	private enum Period { DAY, WEEK, MONTH, ALL }

	private static Period period = Period.DAY;
	private static int offset;
	private static boolean byFish;

	private int scroll;
	private int navY;
	private int kpiY;
	private int barY;
	private int listY;
	private int seenVersion = -1;
	private List<FishingLog.Catch> shown = new ArrayList<>();
	private List<Group> groups = new ArrayList<>();
	private final List<Button> periodButtons = new ArrayList<>();
	private Button prev, next, now, single, grouped;

	/** Zusammenfassung je Fisch. */
	private static final class Group {
		String fish;
		int fishColor = -1;
		int count;
		String best = "";
		int bestColor = -1;
		long last;
	}

	public FishingHistoryScreen() {
		super(Page.FISHING);
	}

	@Override
	protected void initPage() {
		int y = ct;
		String[] keys = {"loothub.fishing.period.day", "loothub.fishing.period.week", "loothub.fishing.period.month",
				"loothub.fishing.period.all"};
		int pw = 60;
		periodButtons.clear();
		for (int i = 0; i < keys.length; i++) {
			Period p = Period.values()[i];
			periodButtons.add(addRenderableWidget(Button.builder(Component.translatable(keys[i]), b -> {
				period = p;
				offset = 0;
				scroll = 0;
				refresh();
			}).bounds(cl + i * (pw + 4), y, pw, 16).build()));
		}
		int vw = 72;
		single = addRenderableWidget(Button.builder(Component.translatable("loothub.fishing.view.single"), b -> {
			byFish = false;
			scroll = 0;
			refresh();
		}).bounds(cr - 2 * vw - 4, y, vw, 16).build());
		grouped = addRenderableWidget(Button.builder(Component.translatable("loothub.fishing.view.byfish"), b -> {
			byFish = true;
			scroll = 0;
			refresh();
		}).bounds(cr - vw, y, vw, 16).build());

		navY = y + 20;
		prev = addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
			offset--;
			scroll = 0;
			refresh();
		}).bounds(cl, navY, 20, 16).build());
		next = addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
			offset = Math.min(0, offset + 1);
			scroll = 0;
			refresh();
		}).bounds(cl + 24 + 150 + 4, navY, 20, 16).build());
		now = addRenderableWidget(Button.builder(Component.translatable("loothub.history.now"), b -> {
			offset = 0;
			scroll = 0;
			refresh();
		}).bounds(cl + 24 + 150 + 28, navY, 50, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.history.clear"), b ->
				minecraft.setScreen(new ConfirmScreen(this, Component.translatable("loothub.fishing.clear"),
						Component.translatable("loothub.fishing.clear.question"), () -> {
							FishingLog.clear();
							refresh();
						})))
				.bounds(cr - 110, navY, 110, 16).build());

		kpiY = navY + 22;
		barY = kpiY + 30 + 6;
		listY = barY + 26;
		refresh();
	}

	@Override
	public void tick() {
		super.tick();
		if (seenVersion != FishingLog.version()) {
			refresh();
		}
	}

	// ------------------------------------------------------------------ Daten

	/** Start und Ende des gewählten Zeitraums (Millisekunden), ALL = alles. */
	private long[] range() {
		ZoneId zone = ZoneId.systemDefault();
		LocalDate today = LocalDate.now(zone);
		LocalDate from;
		LocalDate to;
		switch (period) {
			case DAY -> {
				from = today.plusDays(offset);
				to = from.plusDays(1);
			}
			case WEEK -> {
				from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset);
				to = from.plusWeeks(1);
			}
			case MONTH -> {
				from = today.withDayOfMonth(1).plusMonths(offset);
				to = from.plusMonths(1);
			}
			default -> {
				return new long[]{Long.MIN_VALUE, Long.MAX_VALUE};
			}
		}
		return new long[]{from.atStartOfDay(zone).toInstant().toEpochMilli(), to.atStartOfDay(zone).toInstant().toEpochMilli()};
	}

	private String rangeLabel() {
		ZoneId zone = ZoneId.systemDefault();
		long[] r = range();
		LocalDate from = Instant.ofEpochMilli(r[0]).atZone(zone).toLocalDate();
		return switch (period) {
			case DAY -> offset == 0 ? Component.translatable("loothub.fishing.today").getString() : DATE.format(from);
			case WEEK -> DATE.format(from) + " – " + DATE.format(from.plusDays(6));
			case MONTH -> MONTH.format(from);
			case ALL -> Component.translatable("loothub.fishing.period.all").getString();
		};
	}

	private void refresh() {
		seenVersion = FishingLog.version();
		long[] r = range();
		shown = new ArrayList<>();
		for (FishingLog.Catch c : FishingLog.all()) {
			if (c.t >= r[0] && c.t < r[1]) {
				shown.add(c);
			}
		}
		shown.sort(Comparator.comparingLong((FishingLog.Catch c) -> c.t).reversed());
		Map<String, Group> map = new LinkedHashMap<>();
		for (FishingLog.Catch c : shown) {
			Group g = map.computeIfAbsent(c.fish.toLowerCase(Locale.ROOT), k -> {
				Group n = new Group();
				n.fish = c.fish;
				n.fishColor = c.fishColor;
				return n;
			});
			g.count++;
			g.last = Math.max(g.last, c.t);
			if (g.best.isEmpty() || FishingLog.rank(c.rarity) > FishingLog.rank(g.best)) {
				g.best = c.rarity;
				g.bestColor = c.rarityColor;
			}
		}
		groups = new ArrayList<>(map.values());
		groups.sort(Comparator.comparingInt((Group g) -> g.count).reversed());
		for (int i = 0; i < periodButtons.size(); i++) {
			periodButtons.get(i).active = Period.values()[i] != period;
		}
		boolean nav = period != Period.ALL;
		prev.visible = nav;
		next.visible = nav;
		now.visible = nav;
		next.active = offset < 0;
		now.active = offset < 0;
		single.active = byFish;
		grouped.active = !byFish;
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		if (period != Period.ALL) {
			String label = rangeLabel();
			g.drawCenteredString(font, label, cl + 24 + 75, navY + 4, TEXT);
		}
		renderKpis(g);
		renderRarities(g);
		renderList(g, mouseX, mouseY);
	}

	private static int argb(int rgb, int fallback) {
		return rgb < 0 ? fallback : 0xFF000000 | rgb;
	}

	private void card(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, CARD);
		g.fill(x, y, x + w, y + 1, CARD_EDGE);
	}

	private void renderKpis(GuiGraphics g) {
		int w = (cw - 12) / 3;
		int h = 30;
		// Fänge
		card(g, cl, kpiY, w, h);
		g.drawString(font, Component.translatable("loothub.fishing.kpi.catches"), cl + 6, kpiY + 5, MUTED, false);
		g.drawString(font, String.valueOf(shown.size()), cl + 6, kpiY + 17, GOLD, false);
		// Arten
		int x2 = cl + w + 6;
		card(g, x2, kpiY, w, h);
		g.drawString(font, Component.translatable("loothub.fishing.kpi.kinds"), x2 + 6, kpiY + 5, MUTED, false);
		g.drawString(font, String.valueOf(groups.size()), x2 + 6, kpiY + 17, GOLD, false);
		// Bester Fang
		int x3 = cl + 2 * (w + 6);
		card(g, x3, kpiY, cr - x3, h);
		g.drawString(font, Component.translatable("loothub.fishing.kpi.best"), x3 + 6, kpiY + 5, MUTED, false);
		FishingLog.Catch best = null;
		for (FishingLog.Catch c : shown) {
			if (best == null || FishingLog.rank(c.rarity) > FishingLog.rank(best.rarity)) {
				best = c; // bei Gleichstand der neueste (Liste ist neu → alt)
			}
		}
		if (best == null) {
			g.drawString(font, "–", x3 + 6, kpiY + 17, FAINT, false);
		} else {
			String r = best.rarity + " ";
			g.drawString(font, r, x3 + 6, kpiY + 17, argb(best.rarityColor, GOLD), false);
			g.drawString(font, ellipsize(best.fish, cr - x3 - 12 - font.width(r)), x3 + 6 + font.width(r), kpiY + 17,
					argb(best.fishColor, TEXT), false);
		}
	}

	/** Verteilung nach Seltenheit als farbiger Balken mit Beschriftung. */
	private void renderRarities(GuiGraphics g) {
		Map<String, int[]> counts = new LinkedHashMap<>(); // Seltenheit → {Anzahl, Farbe, Rang}
		for (FishingLog.Catch c : shown) {
			int[] v = counts.computeIfAbsent(c.rarity.toUpperCase(Locale.ROOT),
					k -> new int[]{0, c.rarityColor, FishingLog.rank(c.rarity)});
			v[0]++;
		}
		List<Map.Entry<String, int[]>> list = new ArrayList<>(counts.entrySet());
		list.sort(Comparator.comparingInt(e -> e.getValue()[2]));
		g.fill(cl, barY, cr, barY + 8, 0xFF2E1B1F);
		if (shown.isEmpty()) {
			return;
		}
		int x = cl;
		int lx = cl;
		for (int i = 0; i < list.size(); i++) {
			Map.Entry<String, int[]> e = list.get(i);
			int w = i == list.size() - 1 ? cr - x : Math.round((float) e.getValue()[0] / shown.size() * cw);
			int col = argb(e.getValue()[1], 0xFFB9A9AB);
			g.fill(x, barY, x + w, barY + 8, col);
			x += w;
			String label = e.getKey() + " " + e.getValue()[0];
			if (lx + font.width(label) <= cr) {
				g.drawString(font, label, lx, barY + 12, col, false);
				lx += font.width(label) + 10;
			}
		}
	}

	private void renderList(GuiGraphics g, int mx, int my) {
		int top = listY + ROW_H;
		int visible = Math.max(1, (cb - top) / ROW_H);
		int total = byFish ? groups.size() : shown.size();
		scroll = Mth.clamp(scroll, 0, Math.max(0, total - visible));
		// Spalten
		int c1 = cl + 4;
		int c2 = byFish ? cl + cw * 45 / 100 : cl + 92;
		int c3 = byFish ? cl + cw * 60 / 100 : cl + cw * 60 / 100;
		int c4 = byFish ? cl + cw * 80 / 100 : cl + cw * 78 / 100;
		if (byFish) {
			head(g, "loothub.fishing.col.fish", c1);
			head(g, "loothub.fishing.col.count", c2);
			head(g, "loothub.fishing.col.best", c3);
			head(g, "loothub.fishing.col.last", c4);
		} else {
			head(g, "loothub.fishing.col.time", c1);
			head(g, "loothub.fishing.col.fish", c2);
			head(g, "loothub.fishing.col.size", c3);
			head(g, "loothub.fishing.col.rarity", c4);
		}
		if (total == 0) {
			Component m = Component.translatable("loothub.fishing.empty");
			g.drawString(font, m, cl + (cw - font.width(m)) / 2, top + 14, FAINT, false);
			return;
		}
		ZoneId zone = ZoneId.systemDefault();
		for (int i = 0; i < visible && scroll + i < total; i++) {
			int y = top + i * ROW_H;
			if (mx >= cl && mx < cr && my >= y && my < y + ROW_H) {
				g.fill(cl, y, cr, y + ROW_H, 0x22FFFFFF);
			} else if (i % 2 == 1) {
				g.fill(cl, y, cr, y + ROW_H, 0x10FFFFFF);
			}
			if (byFish) {
				Group gr = groups.get(scroll + i);
				g.drawString(font, ellipsize(gr.fish, c2 - c1 - 6), c1, y + 2, argb(gr.fishColor, TEXT), false);
				g.drawString(font, gr.count + "×", c2, y + 2, GOLD, false);
				g.drawString(font, ellipsize(gr.best, c4 - c3 - 6), c3, y + 2, argb(gr.bestColor, MUTED), false);
				g.drawString(font, TIME.format(Instant.ofEpochMilli(gr.last).atZone(zone)), c4, y + 2, FAINT, false);
			} else {
				FishingLog.Catch c = shown.get(scroll + i);
				g.drawString(font, TIME.format(Instant.ofEpochMilli(c.t).atZone(zone)), c1, y + 2, FAINT, false);
				g.drawString(font, ellipsize(c.fish, c3 - c2 - 6), c2, y + 2, argb(c.fishColor, TEXT), false);
				g.drawString(font, ellipsize(c.size.isEmpty() ? "–" : c.size, c4 - c3 - 6), c3, y + 2,
						argb(c.sizeColor, MUTED), false);
				g.drawString(font, ellipsize(c.rarity, cr - c4 - 4), c4, y + 2, argb(c.rarityColor, MUTED), false);
			}
		}
		if (total > visible) {
			int trackH = cb - top;
			int barH = Math.max(12, trackH * visible / total);
			int by = top + (trackH - barH) * scroll / Math.max(1, total - visible);
			g.fill(cr + 1, top, cr + 3, cb, 0x40FFFFFF);
			g.fill(cr + 1, by, cr + 3, by + barH, RED);
		}
	}

	private void head(GuiGraphics g, String key, int x) {
		g.drawString(font, Component.translatable(key), x, listY + 2, MUTED, false);
	}

	private String ellipsize(String s, int max) {
		if (font.width(s) <= max) {
			return s;
		}
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end)) + font.width("…") > max) {
			end--;
		}
		return s.substring(0, end) + "…";
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= listY && my < cb) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical) * 3);
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
