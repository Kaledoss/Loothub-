package de.loothub.client.stats;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import de.loothub.client.Fmt;

/**
 * Reiter „Verlauf“ der Statistik: Balkendiagramm über 7 Tage, 30 Tage oder 12 Wochen für Blöcke, Mobs, Spielzeit,
 * Strecke, Jobschätze oder Job-Geld. Oben Summe, bester Tag und Schnitt; Maus über einem Balken zeigt die Zahl, ein
 * Klick auf einen Tagesbalken öffnet diesen Tag in der passenden Liste. ‹ › blättert ganze Zeiträume zurück.
 */
final class StatsChart {
	enum Metric {
		BLOCKS, MOBS, PLAY, DISTANCE, TREASURES, MONEY
	}

	enum Period {
		DAYS7(7), DAYS30(30), WEEKS12(12);

		final int bars;

		Period(int bars) {
			this.bars = bars;
		}
	}

	private static Metric metric = Metric.BLOCKS;
	private static Period period = Period.DAYS7;
	/** So viele ganze Zeiträume zurückgeblättert (0 = bis heute). */
	private static int back;

	private static final int GOLD = 0xFFFFD66B, RED = 0xFFE23B3B, BAR = 0xFF7A2A31, TEXT = 0xFFE8D8DA, MUTED = 0xFFB9A9AB,
			FAINT = 0xFF7A6A6C, GRID = 0xFF2E2124, TILE = 0xFF241A1C;

	/** Gerechnete Werte (je Balken) und wofür sie gelten. */
	private static double[] values = new double[0];
	private static LocalDate[] starts = new LocalDate[0];
	private static String cacheKey = "";
	private static long cachedAt;

	private StatsChart() {
	}

	// ------------------------------------------------------------------ Knöpfe

	static void init(StatsScreen screen, int left, int right, int y) {
		Font font = Minecraft.getInstance().font;
		int x = left;
		for (Metric m : Metric.values()) {
			Component label = Component.translatable("loothub.stats.chart." + m.name().toLowerCase(Locale.ROOT));
			int w = font.width(label) + 12;
			Button b = screen.addButton(Button.builder(label, btn -> {
				metric = m;
				screen.rebuild();
			}).bounds(x, y, w, 16).build());
			b.active = m != metric;
			x += w + 4;
		}
		int px = right;
		for (int i = Period.values().length - 1; i >= 0; i--) {
			Period p = Period.values()[i];
			Component label = Component.translatable("loothub.stats.chart.period." + p.name().toLowerCase(Locale.ROOT));
			int w = font.width(label) + 12;
			px -= w;
			Button b = screen.addButton(Button.builder(label, btn -> {
				period = p;
				back = 0;
				screen.rebuild();
			}).bounds(px, y, w, 16).build());
			b.active = p != period;
			px -= 4;
		}
		Button next = screen.addButton(Button.builder(Component.literal("›"), b -> {
			back = Math.max(0, back - 1);
			screen.rebuild();
		}).bounds(px - 16, y, 16, 16).build());
		next.active = back > 0;
		screen.addButton(Button.builder(Component.literal("‹"), b -> {
			back++;
			screen.rebuild();
		}).bounds(px - 34, y, 16, 16).build());
	}

	// ------------------------------------------------------------------ Daten

	/** Erster Tag des Balkens {@code i} (älteste zuerst). */
	private static LocalDate start(int i) {
		LocalDate today = LocalDate.now();
		if (period == Period.WEEKS12) {
			LocalDate monday = today.with(DayOfWeek.MONDAY).minusWeeks((long) back * period.bars);
			return monday.minusWeeks(period.bars - 1 - i);
		}
		return today.minusDays((long) back * period.bars).minusDays(period.bars - 1 - i);
	}

	private static int days() {
		return period == Period.WEEKS12 ? 7 : 1;
	}

	private static void compute() {
		String key = metric + "|" + period + "|" + back + "|" + LocalDate.now();
		long now = System.currentTimeMillis();
		if (key.equals(cacheKey) && now - cachedAt < 2_000) {
			return; // höchstens alle 2 s neu rechnen
		}
		cacheKey = key;
		cachedAt = now;
		int n = period.bars;
		double[] v = new double[n];
		LocalDate[] s = new LocalDate[n];
		Map<LocalDate, Double> money = metric == Metric.MONEY ? moneyByDay() : null;
		for (int i = 0; i < n; i++) {
			s[i] = start(i);
			for (int d = 0; d < days(); d++) {
				LocalDate day = s[i].plusDays(d);
				if (day.isAfter(LocalDate.now())) {
					break;
				}
				v[i] += money != null ? money.getOrDefault(day, 0.0) : value(StatsStore.sumDay(day));
			}
		}
		values = v;
		starts = s;
	}

	private static double value(Map<String, Long> day) {
		return switch (metric) {
			case BLOCKS -> sum(day, "mined.");
			case MOBS -> sum(day, "killed.");
			case PLAY -> day.getOrDefault("general.play_time", 0L);
			case DISTANCE -> day.getOrDefault("general.walk", 0L) + day.getOrDefault("general.sprint", 0L)
					+ day.getOrDefault("general.crouch", 0L) + day.getOrDefault("general.swim", 0L)
					+ day.getOrDefault("general.elytra", 0L) + day.getOrDefault("general.fly", 0L)
					+ day.getOrDefault("general.ride", 0L);
			case TREASURES -> day.getOrDefault("general.treasures", 0L);
			case MONEY -> 0;
		};
	}

	private static long sum(Map<String, Long> day, String prefix) {
		long n = 0;
		for (var e : day.entrySet()) {
			if (e.getKey().startsWith(prefix)) {
				n += e.getValue();
			}
		}
		return n;
	}

	/** Job-Geld je Tag aus dem Jobverlauf (Sitzungen nach ihrem Starttag). */
	private static Map<LocalDate, Double> moneyByDay() {
		Map<LocalDate, Double> out = new java.util.HashMap<>();
		for (var session : de.loothub.client.jobs.JobHistory.all()) {
			LocalDate d = java.time.Instant.ofEpochMilli(session.start).atZone(ZoneId.systemDefault()).toLocalDate();
			out.merge(d, session.money(), Double::sum);
		}
		return out;
	}

	private static String format(double v) {
		return switch (metric) {
			case PLAY -> Fmt.longDuration((long) v);
			case DISTANCE -> StatsScreen.distance((long) v);
			case MONEY -> Fmt.compact(v) + " $";
			default -> Fmt.whole(Math.round(v));
		};
	}

	/** Kurze Achsen-Beschriftung (ohne Einheit bei großen Zahlen). */
	private static String axis(double v) {
		return switch (metric) {
			case PLAY -> Math.round(v / 3_600_000.0 * 10) / 10.0 + " h";
			case DISTANCE -> v >= 100_000 ? Fmt.compactPlain(v / 100_000.0) + " km" : Math.round(v / 100) + " m";
			default -> v >= 1000 ? Fmt.compactPlain(v) : String.valueOf(Math.round(v));
		};
	}

	private static String label(int i) {
		LocalDate d = starts[i];
		if (period == Period.WEEKS12) {
			return "KW " + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
		}
		if (period == Period.DAYS7) {
			boolean de = Minecraft.getInstance().getLanguageManager().getSelected().startsWith("de");
			return d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, de ? Locale.GERMAN : Locale.ENGLISH)
					.replace(".", "");
		}
		return d.format(DateTimeFormatter.ofPattern("dd.MM."));
	}

	private static String longLabel(int i) {
		LocalDate d = starts[i];
		DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM.yyyy");
		if (period == Period.WEEKS12) {
			return "KW " + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + " (" + d.format(DateTimeFormatter.ofPattern("dd.MM."))
					+ " – " + d.plusDays(6).format(DateTimeFormatter.ofPattern("dd.MM.")) + ")";
		}
		return d.format(f);
	}

	// ------------------------------------------------------------------ Zeichnen

	/** Lage der Balken (für Zeichnen und Klicks). */
	private static int[] chartArea(int x, int y, int w, int h) {
		int top = y + 44, bottom = y + h - 18, left = x + 46, right = x + w - 10;
		return new int[]{left, top, right, bottom};
	}

	static void render(GuiGraphics g, Font font, int x, int y, int w, int h, int mx, int my) {
		compute();
		int n = values.length;
		if (n == 0) {
			return;
		}
		double sum = 0, best = 0;
		int bestI = -1, counted = 0;
		LocalDate today = LocalDate.now();
		for (int i = 0; i < n; i++) {
			if (starts[i].isAfter(today)) {
				continue;
			}
			sum += values[i];
			counted++;
			if (values[i] > best) {
				best = values[i];
				bestI = i;
			}
		}
		// Kacheln: Summe, bester Tag/Woche, Schnitt
		int tx = x + 8;
		tx = tile(g, font, tx, y + 6, Component.translatable("loothub.stats.chart.sum").getString(), format(sum), GOLD);
		tx = tile(g, font, tx, y + 6,
				Component.translatable(period == Period.WEEKS12 ? "loothub.stats.chart.best_week" : "loothub.stats.chart.best_day")
						.getString(), bestI < 0 ? "–" : label(bestI) + " · " + format(best), 0xFF7BD88F);
		tile(g, font, tx, y + 6, Component.translatable("loothub.stats.chart.avg").getString(),
				format(counted == 0 ? 0 : sum / counted), TEXT);
		String range = longLabel(0) + " – " + (period == Period.WEEKS12 ? starts[n - 1].plusDays(6).format(
				DateTimeFormatter.ofPattern("dd.MM.yyyy")) : longLabel(n - 1));
		g.drawString(font, range, x + w - 10 - font.width(range), y + 8, FAINT, false);

		int[] a = chartArea(x, y, w, h);
		double max = Math.max(best, 1);
		double step = niceStep(max / 3);
		double top = Math.ceil(max / step) * step;
		// Hilfslinien
		for (int k = 0; k <= 3; k++) {
			double v = top * k / 3;
			int ly = a[3] - (int) Math.round((a[3] - a[1]) * v / top);
			g.fill(a[0], ly, a[2], ly + 1, k == 0 ? 0xFF3A2A2D : GRID);
			String s = axis(v);
			g.drawString(font, s, a[0] - 4 - font.width(s), ly - 4, FAINT, false);
		}
		double slot = (a[2] - a[0]) / (double) n;
		int barW = (int) Math.max(2, Math.min(24, slot * 0.7));
		int hover = -1;
		int todayIdx = -1;
		for (int i = 0; i < n; i++) {
			if (!starts[i].isAfter(today) && !starts[i].plusDays(days() - 1).isBefore(today)) {
				todayIdx = i;
			}
		}
		for (int i = 0; i < n; i++) {
			int cx = a[0] + (int) Math.round(slot * i + slot / 2);
			int bh = (int) Math.round((a[3] - a[1]) * values[i] / top);
			int bx = cx - barW / 2;
			boolean over = mx >= cx - slot / 2 && mx < cx + slot / 2 && my >= a[1] && my <= a[3] + 14;
			if (over) {
				hover = i;
				g.fill((int) (cx - slot / 2), a[1], (int) (cx + slot / 2), a[3], 0x14FFFFFF);
			}
			int color = i == todayIdx ? GOLD : i == bestI ? RED : BAR;
			if (bh > 0) {
				g.fill(bx, a[3] - bh, bx + barW, a[3], color);
			}
			// Beschriftung unten: bei vielen Balken nur jede fünfte
			if (n <= 12 || i % 5 == (n - 1) % 5) {
				String l = label(i);
				g.drawString(font, l, cx - font.width(l) / 2, a[3] + 4, i == todayIdx ? GOLD : MUTED, false);
			}
		}
		if (hover >= 0) {
			java.util.List<Component> tip = java.util.List.of(Component.literal(longLabel(hover)),
					Component.literal(format(values[hover])).withColor(GOLD & 0xFFFFFF));
			g.setComponentTooltipForNextFrame(font, tip, mx, my);
		}
		if (best <= 0) {
			Component c = Component.translatable("loothub.stats.chart.empty");
			g.drawString(font, c, x + (w - font.width(c)) / 2, (a[1] + a[3]) / 2, FAINT, false);
		}
	}

	private static int tile(GuiGraphics g, Font font, int x, int y, String label, String value, int color) {
		int w = Math.max(font.width(label), font.width(value)) + 12;
		g.fill(x, y, x + w, y + 30, TILE);
		g.drawString(font, label, x + 6, y + 4, MUTED, false);
		g.drawString(font, value, x + 6, y + 17, color, false);
		return x + w + 6;
	}

	/** „Schöne“ Schrittweite für die Hilfslinien (1, 2, 5 × 10^n). */
	private static double niceStep(double raw) {
		if (raw <= 0) {
			return 1;
		}
		double p = Math.pow(10, Math.floor(Math.log10(raw)));
		double f = raw / p;
		return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
	}

	/**
	 * Klick auf einen Tagesbalken: {Kategorie-Index, Tage zurück} für die passende Liste; sonst null (Wochen,
	 * Job-Geld, Klick daneben).
	 */
	static int[] click(double mx, double my, int x, int y, int w, int h) {
		if (period == Period.WEEKS12 || metric == Metric.MONEY || values.length == 0) {
			return null;
		}
		int[] a = chartArea(x, y, w, h);
		if (my < a[1] || my > a[3] + 14 || mx < a[0] || mx >= a[2]) {
			return null;
		}
		double slot = (a[2] - a[0]) / (double) values.length;
		int i = (int) ((mx - a[0]) / slot);
		if (i < 0 || i >= starts.length || starts[i].isAfter(LocalDate.now())) {
			return null;
		}
		StatsScreen.Category cat = switch (metric) {
			case BLOCKS -> StatsScreen.Category.BLOCKS;
			case MOBS -> StatsScreen.Category.MOBS;
			default -> StatsScreen.Category.GENERAL;
		};
		return new int[]{cat.ordinal(), (int) ChronoUnit.DAYS.between(starts[i], LocalDate.now())};
	}
}
