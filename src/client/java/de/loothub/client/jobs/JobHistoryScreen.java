package de.loothub.client.jobs;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.Tabs;

/**
 * Reiter „Verlauf“ als Dashboard: ein Zeitraum (Stunde, Tag, Woche, Jahr oder alles) mit ◀/▶ zum Blättern,
 * Kennzahl-Kacheln, ein Verlaufsdiagramm (Klick auf einen Balken springt dorthin), Karten für alle Jobs mit
 * Level und Fortschritt (Klick filtert auf den Job) und darunter die Sitzungen des Zeitraums.
 */
public class JobHistoryScreen extends LoothubScreen {
	// Farben
	private static final int CARD = 0xE01C1315;
	private static final int CARD_HOVER = 0xE02A1A1E;
	private static final int CARD_EDGE = 0xFF3A1F23;
	private static final int GOLD = 0xFFFFC040;
	private static final int GREEN = 0xFF5BD68A;
	private static final int BLUE = 0xFF6AB0FF;
	private static final int TRACK = 0xFF2E1B1F;
	private static final int ROW_HOVER = 0x22FFFFFF;

	private static final int PAD = 8;
	private static final int GAP = 6;
	private static final int ROW_H = 12;
	private static final long MIN_ACTIVE_FOR_RATE_MS = 20_000;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.");
	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM.yy");
	private static final DateTimeFormatter MONTH_NAME = DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.GERMAN);

	/** Zeitraum-Art. */
	public enum Period {
		HOURLY("loothub.history.period.hourly"),
		DAILY("loothub.history.period.daily"),
		WEEKLY("loothub.history.period.weekly"),
		MONTHLY("loothub.history.period.monthly"),
		ALLTIME("loothub.history.period.alltime");

		final String translationKey;

		Period(String translationKey) {
			this.translationKey = translationKey;
		}
	}

	/** Bekannte Jobs: Anzeigename, weitere Schreibweisen, Farbe und Icon (eigene Palette). */
	private record JobInfo(String name, List<String> aliases, int color, Item icon) {
	}

	private static final List<JobInfo> JOBS = List.of(
			new JobInfo("Miner", List.of("Minenarbeiter"), 0xFF8FA3B8, Items.DIAMOND_PICKAXE),
			new JobInfo("Holzfäller", List.of(), 0xFF6FBF5A, Items.IRON_AXE),
			new JobInfo("Gräber", List.of(), 0xFFC9A26B, Items.IRON_SHOVEL),
			new JobInfo("Jäger", List.of(), 0xFFE0645A, Items.BOW),
			new JobInfo("Fischer", List.of("Angler"), 0xFF4FB3D9, Items.FISHING_ROD),
			new JobInfo("Farmer", List.of(), 0xFFE8C547, Items.WHEAT),
			new JobInfo("Builder", List.of(), 0xFFB57EDC, Items.BRICKS));

	/** Ein Balken im Diagramm. */
	private record Bucket(long start, long end, String label, String title, int unitOffset) {
	}

	/** Summen eines Jobs im gewählten Zeitraum plus letzter bekannter Level-Stand. */
	private static final class JobSum {
		final JobInfo info;
		double money;
		double xp;
		long activeMs;
		int level = -1;
		double progress = -1;
		long levelTime;

		JobSum(JobInfo info) {
			this.info = info;
		}

		double metric() {
			return showXp ? xp : money;
		}
	}

	// Zustand (bleibt zwischen zwei Öffnungen erhalten)
	private static Period period = Period.DAILY;
	private static int offset; // 0 = aktuell, -1 = vorheriger Zeitraum …
	private static boolean showXp;
	private static String jobFilter;

	private final List<Button> periodButtons = new ArrayList<>();
	private Button prevButton;
	private Button nextButton;
	private Button nowButton;
	private Button moneyButton;
	private Button xpButton;
	private Button clearButton;
	private long clearArmedUntil;

	// Berechnete Daten
	private long unitStart;
	private long unitEnd;
	private String unitLabel = "";
	private List<JobHistory.Session> unitSessions = List.of();
	private List<Bucket> buckets = List.of();
	private double[] bucketValues = new double[0];
	/** Je Balken der Anteil jedes Jobs (für gestapelte, farbige Balken). */
	private List<Map<JobInfo, Double>> bucketParts = new ArrayList<>();
	private int selectedBucket = -1;
	private final Map<String, JobSum> jobSums = new LinkedHashMap<>();
	private double totalMoney;
	private double totalXp;
	private long totalActive;
	private int lastSignature = -1;
	private double scroll;

	// Layout
	private int navY, kpiY, kpiH, chartX, chartY, chartW, chartH, tilesY, tileW, tileH, tileCols, listY, listH;

	public JobHistoryScreen() {
		super(Page.HISTORY);
	}

	// ------------------------------------------------------------------ Aufbau

	@Override
	protected void initPage() {

		// Zeile 1: Zeitraum-Art links, Geld/XP rechts
		int y = ct;
		Period[] periods = Period.values();
		int pw = Mth.clamp((cw * 3 / 5 - 4 * 4) / periods.length, 44, 76);
		int x = cl;
		periodButtons.clear();
		for (Period p : periods) {
			periodButtons.add(addRenderableWidget(Button.builder(Component.translatable(p.translationKey), b -> {
				period = p;
				offset = 0;
				scroll = 0;
				invalidate();
			}).bounds(x, y, pw, 16).build()));
			x += pw + 4;
		}
		int mw = 56;
		xpButton = addRenderableWidget(Button.builder(Component.translatable("loothub.history.metric.xp"), b -> {
			showXp = true;
			invalidate();
		}).bounds(cr - mw, y, mw, 16).build());
		moneyButton = addRenderableWidget(Button.builder(Component.translatable("loothub.history.metric.money"), b -> {
			showXp = false;
			invalidate();
		}).bounds(cr - 2 * mw - 4, y, mw, 16).build());

		// Zeile 2: ◀ Zeitraum ▶ Jetzt … Verlauf löschen
		navY = y + 20;
		prevButton = addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
			offset--;
			scroll = 0;
			invalidate();
		}).bounds(cl, navY, 20, 16).build());
		nextButton = addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
			offset = Math.min(0, offset + 1);
			scroll = 0;
			invalidate();
		}).bounds(cl + 24 + 150 + 4, navY, 20, 16).build());
		nowButton = addRenderableWidget(Button.builder(Component.translatable("loothub.history.now"), b -> {
			offset = 0;
			scroll = 0;
			invalidate();
		}).bounds(cl + 24 + 150 + 28, navY, 50, 16).build());
		clearButton = addRenderableWidget(Button.builder(Component.translatable("loothub.history.clear"), b -> onClear())
				.bounds(cr - 110, navY, 110, 16).build());

		// Layout der Inhalte
		kpiY = navY + 22;
		kpiH = 30;
		chartX = cl;
		chartY = kpiY + kpiH + GAP;
		chartW = cw;
		int freeH = cb - chartY;
		chartH = Mth.clamp(freeH / 4, 46, 80);
		tileCols = cw >= 400 ? 4 : 2;
		tileW = (cw - (tileCols - 1) * GAP) / tileCols;
		tileH = 34;
		tilesY = chartY + chartH + GAP;
		int tileRows = (JOBS.size() + tileCols - 1) / tileCols;
		listY = tilesY + tileRows * (tileH + GAP);
		listH = Math.max(30, cb - listY);

		invalidate();
	}

	private void invalidate() {
		lastSignature = -1;
		refresh();
	}

	private void updateButtons() {
		Period[] periods = Period.values();
		for (int i = 0; i < periodButtons.size(); i++) {
			periodButtons.get(i).active = periods[i] != period;
		}
		boolean nav = period != Period.ALLTIME;
		prevButton.visible = nav;
		nextButton.visible = nav;
		nowButton.visible = nav;
		nextButton.active = offset < 0;
		nowButton.active = offset < 0;
		moneyButton.active = showXp;
		xpButton.active = !showXp;
	}

	@Override
	public void tick() {
		super.tick();
		if (clearArmedUntil > 0 && System.currentTimeMillis() > clearArmedUntil) {
			clearArmedUntil = 0;
			clearButton.setMessage(Component.translatable("loothub.history.clear"));
		}
		refresh();
	}

	private void onClear() {
		long now = System.currentTimeMillis();
		if (clearArmedUntil > now) {
			JobHistory.clear();
			clearArmedUntil = 0;
			clearButton.setMessage(Component.translatable("loothub.history.clear"));
			invalidate();
		} else {
			clearArmedUntil = now + 3000;
			clearButton.setMessage(Component.translatable("loothub.history.clear.confirm").withStyle(ChatFormatting.RED));
		}
	}

	// ------------------------------------------------------------------ Daten

	private static List<JobHistory.Session> allSessions() {
		List<JobHistory.Session> list = JobHistory.all();
		JobHistory.Session live = JobTracker.liveSession();
		if (live != null) {
			list.add(live);
		}
		return list;
	}

	/** Symbol eines Jobs (auch für das Job-Widget). */
	public static Item jobIcon(String name) {
		return info(name).icon();
	}

	/** Gleicher Job unter verschiedenen Namen (z. B. Angler/Fischer) → ein Eintrag. */
	private static JobInfo info(String name) {
		for (JobInfo j : JOBS) {
			if (j.name().equalsIgnoreCase(name)) {
				return j;
			}
			for (String a : j.aliases()) {
				if (a.equalsIgnoreCase(name)) {
					return j;
				}
			}
		}
		return new JobInfo(name, List.of(), 0xFF9AA4B4, Items.BOOK);
	}

	private boolean matchesFilter(JobHistory.JobEntry e) {
		return jobFilter == null || info(e.job).name().equals(jobFilter);
	}

	private void refresh() {
		List<JobHistory.Session> all = allSessions();
		int sig = all.size() * 31 + (all.isEmpty() ? 0
				: (int) (all.get(all.size() - 1).end ^ Double.hashCode(all.get(all.size() - 1).money())));
		sig = sig * 31 + period.ordinal();
		sig = sig * 31 + offset;
		sig = sig * 31 + (showXp ? 1 : 0);
		sig = sig * 31 + (jobFilter == null ? 0 : jobFilter.hashCode());
		sig = sig * 31 + (int) (System.currentTimeMillis() / 60_000);
		if (sig == lastSignature) {
			return;
		}
		lastSignature = sig;

		ZoneId zone = ZoneId.systemDefault();
		long first = Long.MAX_VALUE;
		for (JobHistory.Session s : all) {
			first = Math.min(first, s.start);
		}
		computeUnit(zone, first);
		buckets = buildBuckets(zone, first);

		// Sitzungen im Zeitraum
		List<JobHistory.Session> inUnit = new ArrayList<>();
		for (JobHistory.Session s : all) {
			if (s.start >= unitStart && s.start < unitEnd) {
				inUnit.add(s);
			}
		}

		// Summen je Job (gefiltert für Kennzahlen, ungefiltert für die Karten)
		jobSums.clear();
		for (JobInfo j : JOBS) {
			jobSums.put(j.name(), new JobSum(j));
		}
		totalMoney = 0;
		totalXp = 0;
		totalActive = 0;
		for (JobHistory.Session s : inUnit) {
			for (JobHistory.JobEntry e : s.jobs) {
				JobInfo ji = info(e.job);
				JobSum sum = jobSums.computeIfAbsent(ji.name(), k -> new JobSum(ji));
				sum.money += e.money;
				sum.xp += e.xp;
				sum.activeMs += e.activeMs;
				if (matchesFilter(e)) {
					totalMoney += e.money;
					totalXp += e.xp;
					totalActive += jobFilter == null ? 0 : e.activeMs;
				}
			}
			if (jobFilter == null) {
				totalActive += s.activeMs;
			}
		}
		// Letzter bekannter Level-Stand je Job (aus allen Sitzungen)
		for (JobHistory.Session s : all) {
			for (JobHistory.JobEntry e : s.jobs) {
				JobInfo ji = info(e.job);
				JobSum sum = jobSums.computeIfAbsent(ji.name(), k -> new JobSum(ji));
				if (e.levelEnd >= 0 && s.end >= sum.levelTime) {
					sum.level = e.levelEnd;
					sum.progress = e.progressEnd;
					sum.levelTime = s.end;
				}
			}
		}

		// Diagramm-Werte
		bucketValues = new double[buckets.size()];
		bucketParts = new ArrayList<>();
		for (int k = 0; k < buckets.size(); k++) {
			bucketParts.add(new java.util.LinkedHashMap<>());
		}
		for (JobHistory.Session s : all) {
			int i = bucketIndex(s.start);
			if (i < 0) {
				continue;
			}
			for (JobHistory.JobEntry e : s.jobs) {
				if (matchesFilter(e)) {
					double v = showXp ? e.xp : e.money;
					bucketValues[i] += v;
					bucketParts.get(i).merge(info(e.job), v, Double::sum);
				}
			}
		}

		// Liste: neueste zuerst, Job-Filter beachten
		List<JobHistory.Session> rows = new ArrayList<>();
		for (JobHistory.Session s : inUnit) {
			if (jobFilter == null || s.jobs.stream().anyMatch(this::matchesFilter)) {
				rows.add(s);
			}
		}
		Collections.reverse(rows);
		unitSessions = rows;
		clampScroll();
		updateButtons();
	}

	/** Start/Ende und Beschriftung des gewählten Zeitraums. */
	private void computeUnit(ZoneId zone, long first) {
		LocalDate today = LocalDate.now();
		switch (period) {
			case HOURLY -> {
				LocalDateTime h = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(offset);
				unitStart = millis(h, zone);
				unitEnd = millis(h.plusHours(1), zone);
				unitLabel = DATE.format(h) + " · " + HOUR.format(h) + "–" + HOUR.format(h.plusHours(1));
			}
			case DAILY -> {
				LocalDate d = today.plusDays(offset);
				unitStart = millis(d.atStartOfDay(), zone);
				unitEnd = millis(d.plusDays(1).atStartOfDay(), zone);
				String prefix = offset == 0 ? Component.translatable("loothub.history.today").getString() + " · "
						: offset == -1 ? Component.translatable("loothub.history.yesterday").getString() + " · " : "";
				unitLabel = prefix + DATE.format(d);
			}
			case WEEKLY -> {
				LocalDate monday = today.with(DayOfWeek.MONDAY).plusWeeks(offset);
				unitStart = millis(monday.atStartOfDay(), zone);
				unitEnd = millis(monday.plusWeeks(1).atStartOfDay(), zone);
				unitLabel = "KW " + monday.get(WeekFields.ISO.weekOfWeekBasedYear()) + " · "
						+ DAY.format(monday) + "–" + DATE.format(monday.plusDays(6));
			}
			case MONTHLY -> {
				YearMonth m = YearMonth.now().plusMonths(offset);
				unitStart = millis(m.atDay(1).atStartOfDay(), zone);
				unitEnd = millis(m.plusMonths(1).atDay(1).atStartOfDay(), zone);
				unitLabel = MONTH_NAME.format(m);
			}
			case ALLTIME -> {
				unitStart = Long.MIN_VALUE;
				unitEnd = Long.MAX_VALUE;
				unitLabel = first == Long.MAX_VALUE
						? Component.translatable("loothub.history.alltime.empty").getString()
						: Component.translatable("loothub.history.alltime.since",
						DATE.format(Instant.ofEpochMilli(first).atZone(zone))).getString();
			}
		}
	}

	/** Diagramm-Balken: Verlauf bis zum gewählten Zeitraum (Monat: die letzten 12 Monate, Alltime: alle Monate/Jahre). */
	private List<Bucket> buildBuckets(ZoneId zone, long first) {
		List<Bucket> out = new ArrayList<>();
		selectedBucket = -1;
		switch (period) {
			case HOURLY -> {
				LocalDateTime end = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(offset);
				for (int i = 23; i >= 0; i--) {
					LocalDateTime h = end.minusHours(i);
					out.add(new Bucket(millis(h, zone), millis(h.plusHours(1), zone), HOUR.format(h),
							DAY.format(h) + " " + HOUR.format(h) + "–" + HOUR.format(h.plusHours(1)), offset - i));
				}
				selectedBucket = out.size() - 1;
			}
			case DAILY -> {
				LocalDate end = LocalDate.now().plusDays(offset);
				for (int i = 13; i >= 0; i--) {
					LocalDate d = end.minusDays(i);
					out.add(new Bucket(millis(d.atStartOfDay(), zone), millis(d.plusDays(1).atStartOfDay(), zone),
							DAY.format(d), DATE.format(d), offset - i));
				}
				selectedBucket = out.size() - 1;
			}
			case WEEKLY -> {
				LocalDate end = LocalDate.now().with(DayOfWeek.MONDAY).plusWeeks(offset);
				for (int i = 11; i >= 0; i--) {
					LocalDate w = end.minusWeeks(i);
					int kw = w.get(WeekFields.ISO.weekOfWeekBasedYear());
					out.add(new Bucket(millis(w.atStartOfDay(), zone), millis(w.plusWeeks(1).atStartOfDay(), zone),
							"KW " + kw, "KW " + kw + " · " + DAY.format(w) + "–" + DATE.format(w.plusDays(6)), offset - i));
				}
				selectedBucket = out.size() - 1;
			}
			case MONTHLY -> {
				YearMonth end = YearMonth.now().plusMonths(offset);
				for (int i = 11; i >= 0; i--) {
					YearMonth m = end.minusMonths(i);
					String label = MONTH.format(m);
					out.add(new Bucket(millis(m.atDay(1).atStartOfDay(), zone), millis(m.plusMonths(1).atDay(1).atStartOfDay(), zone),
							label, MONTH_NAME.format(m), offset - i));
				}
				selectedBucket = out.size() - 1;
			}
			case ALLTIME -> {
				YearMonth now = YearMonth.now();
				YearMonth start = first == Long.MAX_VALUE ? now : YearMonth.from(Instant.ofEpochMilli(first).atZone(zone));
				if (ChronoUnit.MONTHS.between(start, now) < 24) {
					for (YearMonth m = start; !m.isAfter(now); m = m.plusMonths(1)) {
						out.add(monthBucket(m, zone));
					}
				} else {
					for (int y = start.getYear(); y <= now.getYear(); y++) {
						out.add(new Bucket(millis(LocalDate.of(y, 1, 1).atStartOfDay(), zone),
								millis(LocalDate.of(y + 1, 1, 1).atStartOfDay(), zone),
								String.valueOf(y), String.valueOf(y), Integer.MIN_VALUE));
					}
				}
			}
		}
		return out;
	}

	private static Bucket monthBucket(YearMonth m, ZoneId zone) {
		String label = MONTH.format(m);
		return new Bucket(millis(m.atDay(1).atStartOfDay(), zone), millis(m.plusMonths(1).atDay(1).atStartOfDay(), zone),
				label, label, Integer.MIN_VALUE);
	}

	private int bucketIndex(long time) {
		for (int i = 0; i < buckets.size(); i++) {
			if (time >= buckets.get(i).start() && time < buckets.get(i).end()) {
				return i;
			}
		}
		return -1;
	}

	private static long millis(LocalDateTime t, ZoneId zone) {
		return t.atZone(zone).toInstant().toEpochMilli();
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);

		// Zeitraum-Beschriftung zwischen ◀ und ▶
		int labelX = period == Period.ALLTIME ? cl : cl + 24;
		g.drawString(font, unitLabel, labelX + 4, navY + 4, TEXT, false);

		renderKpis(g, mouseX, mouseY);
		renderChart(g, mouseX, mouseY);
		renderTiles(g, mouseX, mouseY);
		renderList(g, mouseX, mouseY);
	}

	/** Karte mit farbiger Akzentleiste links. */
	private static void card(GuiGraphics g, int x, int y, int w, int h, int accent, boolean hover) {
		g.fill(x, y, x + w, y + h, hover ? CARD_HOVER : de.loothub.client.Theme.bg(CARD));
		g.fill(x, y + h - 1, x + w, y + h, CARD_EDGE);
		g.fill(x, y, x + 2, y + h, accent);
	}

	private void renderKpis(GuiGraphics g, int mx, int my) {
		int kw = (cw - 3 * GAP) / 4;
		String metricSuffix = showXp ? " XP" : " $";

		// Stärkster Job im Zeitraum
		JobSum best = null;
		double bestValue = 0;
		double sumAll = 0;
		for (JobSum s : jobSums.values()) {
			sumAll += s.metric();
			if (s.metric() > bestValue) {
				bestValue = s.metric();
				best = s;
			}
		}

		double rate = totalActive >= MIN_ACTIVE_FOR_RATE_MS ? totalMoney / (totalActive / 3_600_000.0) : Double.NaN;
		double xpRate = totalActive >= MIN_ACTIVE_FOR_RATE_MS ? totalXp / (totalActive / 3_600_000.0) : Double.NaN;

		kpi(g, cl, kw, GOLD, Items.GOLD_INGOT, "loothub.history.kpi.money",
				Fmt.money(totalMoney), "+" + Fmt.compact(totalXp) + " XP", GOLD);
		kpi(g, cl + (kw + GAP), kw, GREEN, Items.EMERALD, "loothub.history.kpi.rate",
				Double.isNaN(rate) ? "–" : Fmt.compact(rate) + " $/h",
				Double.isNaN(xpRate) ? "" : Fmt.compact(xpRate) + " XP/h", GREEN);
		kpi(g, cl + 2 * (kw + GAP), kw, BLUE, Items.CLOCK, "loothub.history.kpi.active",
				Fmt.longDuration(totalActive),
				Component.translatable(unitSessions.size() == 1 ? "loothub.history.kpi.session" : "loothub.history.kpi.sessions", unitSessions.size()).getString(), TEXT);
		if (best != null) {
			String share = sumAll > 0 ? Math.round(bestValue / sumAll * 100) + " %" : "";
			kpi(g, cl + 3 * (kw + GAP), kw, best.info.color(), best.info.icon(), "loothub.history.kpi.best",
					best.info.name(), Fmt.compact(bestValue) + metricSuffix + (share.isEmpty() ? "" : " · " + share),
					best.info.color());
		} else {
			kpi(g, cl + 3 * (kw + GAP), kw, FAINT, Items.BOOK, "loothub.history.kpi.best", "–", "", MUTED);
		}
	}

	private void kpi(GuiGraphics g, int x, int w, int accent, Item icon, String labelKey, String value, String sub, int valueColor) {
		card(g, x, kpiY, w, kpiH, accent, false);
		g.renderItem(new ItemStack(icon), x + 6, kpiY + 7);
		int tx = x + 26;
		int maxW = w - 30;
		g.drawString(font, ellipsize(Component.translatable(labelKey).getString(), maxW), tx, kpiY + 4, MUTED, false);
		g.drawString(font, ellipsize(value, maxW), tx, kpiY + 14, valueColor, false);
		if (!sub.isEmpty()) {
			String s = ellipsize(sub, maxW - font.width(value) - 6);
			g.drawString(font, s, x + w - 5 - font.width(s), kpiY + 14, FAINT, false);
		}
	}

	private void renderChart(GuiGraphics g, int mx, int my) {
		card(g, chartX, chartY, chartW, chartH, showXp ? BLUE : GOLD, false);
		String title = Component.translatable(showXp ? "loothub.history.chart.xp" : "loothub.history.chart.money").getString();
		if (jobFilter != null) {
			title += " · " + jobFilter;
		}
		g.drawString(font, title, chartX + 8, chartY + 4, MUTED, false);

		int n = buckets.size();
		if (n == 0) {
			return;
		}
		double max = 0;
		for (double v : bucketValues) {
			max = Math.max(max, v);
		}
		if (max > 0) {
			String maxLabel = "max " + Fmt.compact(max) + (showXp ? " XP" : " $");
			g.drawString(font, maxLabel, chartX + chartW - 6 - font.width(maxLabel), chartY + 4, FAINT, false);
		}

		int left = chartX + 8;
		int right = chartX + chartW - 6;
		int top = chartY + 15;
		int bottom = chartY + chartH - 12;
		double slot = (right - left) / (double) n;
		int barW = Math.max(2, (int) (slot * 0.62));
		int barColor = showXp ? BLUE : GOLD;
		g.fill(left, bottom, right, bottom + 1, 0x33FFFFFF);
		// gestrichelte Hilfslinien bei 1/3 und 2/3
		for (int k = 1; k <= 2; k++) {
			int gy = bottom - (bottom - top) * k / 3;
			for (int dx = left; dx < right; dx += 6) {
				g.fill(dx, gy, Math.min(dx + 3, right), gy + 1, 0x16FFFFFF);
			}
		}

		int labelW = 0;
		for (Bucket b : buckets) {
			labelW = Math.max(labelW, font.width(b.label()));
		}
		int every = Math.max(1, (int) Math.ceil((labelW + 6) / slot));

		int hover = -1;
		for (int i = 0; i < n; i++) {
			int cx = left + (int) Math.round(slot * i + slot / 2);
			int x1 = cx - barW / 2;
			boolean hovered = mx >= left + slot * i && mx < left + slot * (i + 1) && my >= top && my <= bottom + 10;
			if (hovered) {
				hover = i;
			}
			boolean selected = i == selectedBucket;
			if (selected) {
				g.fill(left + (int) (slot * i), top - 2, left + (int) (slot * (i + 1)), bottom, 0x18FFFFFF);
			}
			// leere Spur, damit auch Null-Werte sichtbar sind
			g.fill(x1, bottom - 1, x1 + barW, bottom, TRACK);
			if (bucketValues[i] > 0 && max > 0) {
				int h = Math.max(2, (int) Math.round((bottom - top) * bucketValues[i] / max));
				boolean strong = selected || hovered;
				// Gestapelt: jeder Job in seiner Farbe, unten beginnend (Reihenfolge wie die Job-Kacheln)
				int segBottom = bottom;
				double acc = 0;
				for (JobInfo j : JOBS) {
					Double part = bucketParts.get(i).get(j);
					if (part == null || part <= 0) {
						continue;
					}
					acc += part;
					int segTop = bottom - (int) Math.round(h * acc / bucketValues[i]);
					if (segTop < segBottom) {
						int rgb = j.color() & 0x00FFFFFF;
						g.fill(x1, segTop, x1 + barW, segBottom, (strong ? 0xFF000000 : 0xD0000000) | rgb);
						segBottom = segTop;
					}
				}
				// Jobs, die nicht in der Liste stehen
				for (Map.Entry<JobInfo, Double> pe : bucketParts.get(i).entrySet()) {
					if (JOBS.contains(pe.getKey()) || pe.getValue() <= 0) {
						continue;
					}
					acc += pe.getValue();
					int segTop = bottom - (int) Math.round(h * acc / bucketValues[i]);
					if (segTop < segBottom) {
						g.fill(x1, segTop, x1 + barW, segBottom, (strong ? 0xFF000000 : 0xD0000000) | (pe.getKey().color() & 0xFFFFFF));
						segBottom = segTop;
					}
				}
				if (hovered && h > 10) {
					String v = Fmt.compact(bucketValues[i]);
					int vw = font.width(v);
					g.drawString(font, v, Mth.clamp(cx - vw / 2, left, right - vw), bottom - h - 10, TEXT, false);
				}
			}
			if ((n - 1 - i) % every == 0) {
				String label = buckets.get(i).label();
				int lw = font.width(label);
				g.drawString(font, label, Mth.clamp(cx - lw / 2, left, right - lw), bottom + 3,
						selected ? TEXT : FAINT, false);
			}
		}

		if (hover >= 0) {
			Bucket b = buckets.get(hover);
			List<FormattedCharSequence> tip = new ArrayList<>();
			tip.add(Component.literal(b.title()).withStyle(ChatFormatting.WHITE).getVisualOrderText());
			tip.add(Component.literal(showXp ? Fmt.compact(bucketValues[hover]) + " XP" : Fmt.money(bucketValues[hover]))
					.withStyle(showXp ? ChatFormatting.AQUA : ChatFormatting.GOLD).getVisualOrderText());
			// Aufteilung nach Jobs, größter zuerst
			bucketParts.get(hover).entrySet().stream()
					.filter(en -> en.getValue() > 0)
					.sorted((a, b2) -> Double.compare(b2.getValue(), a.getValue()))
					.forEach(en -> tip.add(Component.literal("■ ").withStyle(st -> st.withColor(en.getKey().color() & 0xFFFFFF))
							.append(Component.literal(en.getKey().name() + ": " + (showXp ? Fmt.compact(en.getValue()) + " XP"
									: Fmt.money(en.getValue()))).withStyle(ChatFormatting.GRAY)).getVisualOrderText()));
			if (b.unitOffset() != Integer.MIN_VALUE && hover != selectedBucket) {
				tip.add(Component.translatable("loothub.history.chart.click").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
			}
			g.setTooltipForNextFrame(font, tip, mx, my);
		}
	}

	private void renderTiles(GuiGraphics g, int mx, int my) {
		double max = 0;
		for (JobInfo j : JOBS) {
			max = Math.max(max, jobSums.get(j.name()).metric());
		}
		for (int i = 0; i < JOBS.size(); i++) {
			JobInfo j = JOBS.get(i);
			JobSum s = jobSums.get(j.name());
			int x = cl + (i % tileCols) * (tileW + GAP);
			int y = tilesY + (i / tileCols) * (tileH + GAP);
			boolean hover = mx >= x && mx < x + tileW && my >= y && my < y + tileH;
			boolean selected = j.name().equals(jobFilter);
			boolean dimmed = jobFilter != null && !selected;
			card(g, x, y, tileW, tileH, dimmed ? (j.color() & 0x00FFFFFF) | 0x55000000 : j.color(), hover || selected);
			if (selected) {
				g.fill(x, y, x + tileW, y + 1, j.color());
			}

			g.renderItem(new ItemStack(j.icon()), x + 6, y + 4);
			g.drawString(font, j.name(), x + 26, y + 4, dimmed ? FAINT : j.color(), false);
			String level = s.level >= 0 ? "Lv. " + s.level : "Lv. –";
			g.drawString(font, level, x + tileW - 6 - font.width(level), y + 4, dimmed ? FAINT : TEXT, false);

			// Level-Fortschritt
			int barX = x + 26;
			int barW = tileW - 26 - 6 - 34;
			int barY = y + 15;
			g.fill(barX, barY, barX + barW, barY + 3, TRACK);
			if (s.progress >= 0) {
				g.fill(barX, barY, barX + (int) Math.round(barW * Math.min(100, s.progress) / 100.0), barY + 3, j.color());
				String pct = Fmt.compactPlain(s.progress) + " %";
				g.drawString(font, pct, x + tileW - 6 - font.width(pct), barY - 2, FAINT, false);
			}

			// Wert im Zeitraum + Anteil am stärksten Job
			String value = s.metric() > 0 ? Fmt.compact(s.metric()) + (showXp ? " XP" : " $") : "–";
			String rate = s.activeMs >= MIN_ACTIVE_FOR_RATE_MS && !showXp
					? " · " + Fmt.compact(s.money / (s.activeMs / 3_600_000.0)) + " $/h" : "";
			g.drawString(font, ellipsize(value + rate, tileW - 32), x + 26, y + 22, s.metric() > 0 ? TEXT : FAINT, false);
			if (max > 0 && s.metric() > 0) {
				int w = (int) Math.round((tileW - 2) * s.metric() / max);
				g.fill(x + 2, y + tileH - 2, x + 2 + w, y + tileH - 1, j.color());
			}
		}
	}

	private void renderList(GuiGraphics g, int mx, int my) {
		int listX = cl;
		int listW = cw;
		card(g, listX, listY, listW, listH, CARD_EDGE, false);

		boolean showLevel = listW >= 470;
		int colDate = listX + 8;
		int colJob = colDate + 68;
		int colLevelR = listX + listW - 9;
		int colRateR = showLevel ? colLevelR - 64 : colLevelR;
		int colMoneyR = colRateR - 58;
		int colXpR = colMoneyR - 66;
		int colTimeR = colXpR - 52;
		int jobW = colTimeR - 72 - colJob;

		int hy = listY + 4;
		g.drawString(font, Component.translatable("loothub.history.col.date"), colDate, hy, FAINT, false);
		g.drawString(font, Component.translatable("loothub.history.col.job"), colJob, hy, FAINT, false);
		right(g, Component.translatable("loothub.history.col.time").getString(), colTimeR, hy, FAINT);
		right(g, "XP", colXpR, hy, FAINT);
		right(g, "$", colMoneyR, hy, FAINT);
		right(g, "$/h", colRateR, hy, FAINT);
		if (showLevel) {
			right(g, "Level", colLevelR, hy, FAINT);
		}
		g.fill(listX + 2, listY + 14, listX + listW, listY + 15, CARD_EDGE);

		int top = listY + 16;
		int bottom = listY + listH - 1;
		if (unitSessions.isEmpty()) {
			Component msg = Component.translatable(JobHistory.all().isEmpty() && JobTracker.liveSession() == null
					? "loothub.history.empty" : "loothub.history.empty.period");
			g.drawString(font, msg, listX + (listW - font.width(msg)) / 2, top + 6, FAINT, false);
			return;
		}

		JobHistory.Session hovered = null;
		g.enableScissor(listX + 2, top, listX + listW, bottom);
		for (int i = Math.max(0, (int) (scroll / ROW_H)); i < unitSessions.size(); i++) {
			int ry = top + 1 + i * ROW_H - (int) scroll;
			if (ry > bottom) {
				break;
			}
			JobHistory.Session s = unitSessions.get(i);
			boolean over = mx >= listX && mx < listX + listW && my >= Math.max(ry, top) && my < Math.min(ry + ROW_H, bottom);
			if (over) {
				hovered = s;
				g.fill(listX + 2, ry - 1, listX + listW, ry + ROW_H - 1, ROW_HOVER);
			}
			double xp = 0;
			double money = 0;
			long active = jobFilter == null ? s.activeMs : 0;
			JobHistory.JobEntry main = null;
			for (JobHistory.JobEntry e : s.jobs) {
				if (matchesFilter(e)) {
					xp += e.xp;
					money += e.money;
					if (jobFilter != null) {
						active += e.activeMs;
					}
					if (main == null || e.money > main.money) {
						main = e;
					}
				}
			}
			int ty = ry + 1;
			int color = s.live ? GREEN : TEXT;
			g.drawString(font, Fmt.date(s.start, true), colDate, ty, color, false);
			String job = main == null ? "?" : info(main.job).name();
			if (jobFilter == null && s.jobs.size() > 1) {
				job += " +" + (s.jobs.size() - 1);
			}
			if (s.live) {
				job += " " + Component.translatable("loothub.history.live").getString();
			}
			int jobColor = s.live ? GREEN : (main == null ? TEXT : info(main.job).color());
			g.drawString(font, ellipsize(job, jobW), colJob, ty, jobColor, false);
			right(g, Fmt.duration(active), colTimeR, ty, MUTED);
			right(g, Fmt.compact(xp), colXpR, ty, BLUE);
			right(g, Fmt.compact(money), colMoneyR, ty, GOLD);
			right(g, active >= MIN_ACTIVE_FOR_RATE_MS ? Fmt.compact(money / (active / 3_600_000.0)) : "–", colRateR, ty, MUTED);
			if (showLevel && main != null) {
				right(g, levelText(main), colLevelR, ty, MUTED);
			}
		}
		g.disableScissor();

		int contentH = unitSessions.size() * ROW_H;
		int viewH = bottom - top;
		if (contentH > viewH) {
			int thumbH = Math.max(10, viewH * viewH / contentH);
			int thumbY = top + (int) ((viewH - thumbH) * (scroll / Math.max(1, contentH - viewH)));
			g.fill(listX + listW - 3, top, listX + listW, bottom, 0x33FFFFFF);
			g.fill(listX + listW - 3, thumbY, listX + listW, thumbY + thumbH, 0xAAFFFFFF);
		}

		if (hovered != null) {
			g.setTooltipForNextFrame(font, tooltip(hovered), mx, my);
		}
	}

	private List<FormattedCharSequence> tooltip(JobHistory.Session s) {
		List<FormattedCharSequence> tip = new ArrayList<>();
		tip.add(Component.literal(Fmt.date(s.start, true) + " – " + Fmt.time(s.end)).withStyle(ChatFormatting.WHITE).getVisualOrderText());
		tip.add(Component.translatable("loothub.history.tip.active", Fmt.duration(s.activeMs),
				Fmt.duration(Math.max(0, s.end - s.start))).withStyle(ChatFormatting.GRAY).getVisualOrderText());
		for (JobHistory.JobEntry j : s.jobs) {
			tip.add(Component.literal(info(j.job).name()).withStyle(st -> st.withColor(info(j.job).color() & 0xFFFFFF))
					.append(Component.literal(": +" + Fmt.compact(j.xp) + " XP · +" + Fmt.money(j.money)).withStyle(ChatFormatting.WHITE))
					.getVisualOrderText());
			String level = levelDetail(j);
			if (!level.isEmpty()) {
				tip.add(Component.literal("  " + level).withStyle(ChatFormatting.GRAY).getVisualOrderText());
			}
		}
		return tip;
	}

	private static String levelText(JobHistory.JobEntry j) {
		if (j.levelEnd < 0) {
			return "–";
		}
		if (j.levelStart >= 0 && j.levelStart != j.levelEnd) {
			return j.levelStart + " → " + j.levelEnd;
		}
		return String.valueOf(j.levelEnd);
	}

	private static String levelDetail(JobHistory.JobEntry j) {
		if (j.levelEnd < 0) {
			return "";
		}
		String start = j.levelStart >= 0 ? "Level " + j.levelStart + percent(j.progressStart) : "";
		String end = "Level " + j.levelEnd + percent(j.progressEnd);
		return start.isEmpty() ? end : start + " → " + end;
	}

	private static String percent(double p) {
		return p < 0 ? "" : " (" + Fmt.compactPlain(p) + " %)";
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		// Klick auf eine Job-Karte: auf diesen Job filtern (nochmal klicken = alle)
		for (int i = 0; i < JOBS.size(); i++) {
			int x = cl + (i % tileCols) * (tileW + GAP);
			int y = tilesY + (i / tileCols) * (tileH + GAP);
			if (mx >= x && mx < x + tileW && my >= y && my < y + tileH) {
				String name = JOBS.get(i).name();
				jobFilter = name.equals(jobFilter) ? null : name;
				scroll = 0;
				invalidate();
				return true;
			}
		}

		// Klick auf einen Balken: in diesen Zeitraum springen
		if (my >= chartY && my < chartY + chartH && mx >= chartX && mx < chartX + chartW && !buckets.isEmpty()) {
			int left = chartX + 8;
			int right = chartX + chartW - 6;
			double slot = (right - left) / (double) buckets.size();
			int i = (int) ((mx - left) / slot);
			if (i >= 0 && i < buckets.size() && buckets.get(i).unitOffset() != Integer.MIN_VALUE) {
				offset = Math.min(0, buckets.get(i).unitOffset());
				scroll = 0;
				invalidate();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (mx >= cl && mx < cr && my >= listY && my < listY + listH) {
			scroll -= vertical * ROW_H * 3;
			clampScroll();
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}


	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ Hilfen

	private void clampScroll() {
		int viewH = listH - 17;
		scroll = Mth.clamp(scroll, 0, Math.max(0, unitSessions.size() * ROW_H - viewH));
	}

	private void right(GuiGraphics g, String s, int rightX, int y, int color) {
		g.drawString(font, s, rightX - font.width(s), y, color, false);
	}

	private String ellipsize(String s, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (font.width(s) <= maxWidth) {
			return s;
		}
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end)) + font.width("…") > maxWidth) {
			end--;
		}
		return s.substring(0, end) + "…";
	}
}
