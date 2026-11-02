package de.loothub.client.jobs;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import de.loothub.client.Fmt;
import de.loothub.client.Tabs;

/**
 * Reiter „Verlauf“: alle gespeicherten Job-Sitzungen mit Summen (heute, 7 Tage, gesamt),
 * Tagesverdienst der letzten 14 Tage als Balkendiagramm und einer Liste aller Sitzungen.
 */
public class JobHistoryScreen extends Screen {
	private static final int PANEL = 0xD0101018;
	private static final int BORDER = 0xFF3C3C4C;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFB0B0C0;
	private static final int FAINT = 0xFF707080;
	private static final int GOLD = 0xFFFFC040;
	private static final int GREEN = 0xFF55DD77;
	private static final int BLUE = 0xFF66AAFF;
	private static final int BAR = 0xCCFFC040;
	private static final int BAR_HOVER = 0xFFFFE080;
	private static final int ROW_HOVER = 0x22FFFFFF;

	private static final int PAD = 8;
	private static final int ROW_H = 12;
	private static final int DAYS = 14;
	private static final long MIN_ACTIVE_FOR_RATE_MS = 20_000;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.");

	/** Gewählter Job-Filter; null = alle. Bleibt zwischen zwei Öffnungen erhalten. */
	private static String jobFilter;

	private Button updateButton;
	private Button filterButton;
	private Button clearButton;
	private long clearArmedUntil;

	private List<JobHistory.Session> rows = List.of(); // neueste zuerst, gefiltert
	private List<String> jobNames = List.of();
	private int lastSignature = -1;
	private double scroll;

	private int cardsY, chartX, chartY, chartW, chartH, listX, listY, listW, listH;

	/** Werte einer Sitzung unter Berücksichtigung des Job-Filters. */
	private record Values(double xp, double money, long activeMs) {
	}

	public JobHistoryScreen() {
		super(Component.translatable("loothub.history.title"));
	}

	@Override
	protected void init() {
		Tabs.create(Tabs.Tab.HISTORY, width, 4).forEach(this::addRenderableWidget);
		updateButton = addRenderableWidget(Tabs.updateButton(4));

		cardsY = 24;
		int controlsY = cardsY + 38;
		filterButton = addRenderableWidget(Button.builder(filterLabel(), b -> cycleFilter())
				.bounds(PAD, controlsY, 150, 16).build());
		clearButton = addRenderableWidget(Button.builder(Component.translatable("loothub.history.clear"), b -> onClear())
				.bounds(width - PAD - 120, controlsY, 120, 16).build());

		chartX = PAD;
		chartY = controlsY + 22;
		chartW = width - 2 * PAD;
		chartH = Mth.clamp((height - chartY) / 3, 50, 80);

		listX = PAD;
		listY = chartY + chartH + 6;
		listW = width - 2 * PAD;
		listH = height - listY - PAD;

		lastSignature = -1;
		refresh();
	}

	@Override
	public void tick() {
		super.tick();
		Tabs.refreshUpdateButton(updateButton);
		if (clearArmedUntil > 0 && System.currentTimeMillis() > clearArmedUntil) {
			clearArmedUntil = 0;
			clearButton.setMessage(Component.translatable("loothub.history.clear"));
		}
		refresh();
	}

	// ------------------------------------------------------------------ Daten

	private List<JobHistory.Session> allSessions() {
		List<JobHistory.Session> list = JobHistory.all();
		JobHistory.Session live = JobTracker.liveSession();
		if (live != null) {
			list.add(live);
		}
		return list;
	}

	private void refresh() {
		List<JobHistory.Session> all = allSessions();
		// Nur neu aufbauen, wenn sich etwas geändert hat
		int sig = all.size() * 31 + (all.isEmpty() ? 0 : (int) (all.get(all.size() - 1).end ^ Double.hashCode(all.get(all.size() - 1).money())));
		sig = sig * 31 + (jobFilter == null ? 0 : jobFilter.hashCode());
		if (sig == lastSignature) {
			return;
		}
		lastSignature = sig;

		Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (JobHistory.Session s : all) {
			for (JobHistory.JobEntry j : s.jobs) {
				names.add(j.job);
			}
		}
		jobNames = new ArrayList<>(names);
		if (jobFilter != null && !jobNames.contains(jobFilter)) {
			jobFilter = null;
		}
		filterButton.setMessage(filterLabel());

		List<JobHistory.Session> out = new ArrayList<>();
		for (JobHistory.Session s : all) {
			if (jobFilter == null || s.job(jobFilter) != null) {
				out.add(s);
			}
		}
		Collections.reverse(out);
		rows = out;
		clampScroll();
	}

	private Values values(JobHistory.Session s) {
		if (jobFilter != null) {
			JobHistory.JobEntry j = s.job(jobFilter);
			return j == null ? new Values(0, 0, 0) : new Values(j.xp, j.money, j.activeMs);
		}
		return new Values(s.xp(), s.money(), s.activeMs);
	}

	private void cycleFilter() {
		if (jobNames.isEmpty()) {
			jobFilter = null;
		} else if (jobFilter == null) {
			jobFilter = jobNames.get(0);
		} else {
			int i = jobNames.indexOf(jobFilter);
			jobFilter = i < 0 || i + 1 >= jobNames.size() ? null : jobNames.get(i + 1);
		}
		scroll = 0;
		refresh();
	}

	private Component filterLabel() {
		Component value = jobFilter == null
				? Component.translatable("loothub.history.filter.all")
				: Component.literal(jobFilter);
		return Component.translatable("loothub.history.filter", value);
	}

	/** Erster Klick: nachfragen. Zweiter Klick innerhalb von 3 Sekunden: wirklich löschen. */
	private void onClear() {
		long now = System.currentTimeMillis();
		if (clearArmedUntil > now) {
			JobHistory.clear();
			clearArmedUntil = 0;
			clearButton.setMessage(Component.translatable("loothub.history.clear"));
			lastSignature = -1;
			refresh();
		} else {
			clearArmedUntil = now + 3000;
			clearButton.setMessage(Component.translatable("loothub.history.clear.confirm").withStyle(ChatFormatting.RED));
		}
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		renderCards(g);
		renderChart(g, mouseX, mouseY);
		renderList(g, mouseX, mouseY);
	}

	private void renderCards(GuiGraphics g) {
		LocalDate today = LocalDate.now();
		LocalDate weekStart = today.minusDays(6);
		double[] xp = new double[3];
		double[] money = new double[3];
		long[] active = new long[3];
		for (JobHistory.Session s : rows) {
			LocalDate day = day(s.start);
			Values v = values(s);
			for (int i = 0; i < 3; i++) {
				boolean in = i == 2 || (i == 1 ? !day.isBefore(weekStart) : day.equals(today));
				if (in) {
					xp[i] += v.xp();
					money[i] += v.money();
					active[i] += v.activeMs();
				}
			}
		}
		String[] labels = {"loothub.history.today", "loothub.history.week", "loothub.history.total"};
		int gap = 6;
		int cw = (width - 2 * PAD - 2 * gap) / 3;
		for (int i = 0; i < 3; i++) {
			int x = PAD + i * (cw + gap);
			panel(g, x, cardsY, cw, 34);
			g.drawString(font, Component.translatable(labels[i]), x + 5, cardsY + 4, MUTED, false);
			String line1 = Fmt.money(money[i]) + " · " + Fmt.compact(xp[i]) + " XP";
			g.drawString(font, ellipsize(line1, cw - 10), x + 5, cardsY + 14, GOLD, false);
			String rate = active[i] >= MIN_ACTIVE_FOR_RATE_MS ? Fmt.compact(money[i] / (active[i] / 3_600_000.0)) + " $/h" : "–";
			String line2 = Fmt.duration(active[i]) + " · " + rate;
			g.drawString(font, ellipsize(line2, cw - 10), x + 5, cardsY + 24, FAINT, false);
		}
	}

	private void renderChart(GuiGraphics g, int mx, int my) {
		panel(g, chartX, chartY, chartW, chartH);
		g.drawString(font, Component.translatable("loothub.history.chart", DAYS), chartX + 5, chartY + 4, MUTED, false);

		LocalDate today = LocalDate.now();
		double[] money = new double[DAYS];
		double[] xp = new double[DAYS];
		for (JobHistory.Session s : rows) {
			long index = DAYS - 1 - (today.toEpochDay() - day(s.start).toEpochDay());
			if (index >= 0 && index < DAYS) {
				Values v = values(s);
				money[(int) index] += v.money();
				xp[(int) index] += v.xp();
			}
		}
		double max = 0;
		for (double m : money) {
			max = Math.max(max, m);
		}
		if (max > 0) {
			String maxLabel = Fmt.compact(max) + " $";
			g.drawString(font, maxLabel, chartX + chartW - 5 - font.width(maxLabel), chartY + 4, FAINT, false);
		}

		int left = chartX + 5;
		int right = chartX + chartW - 5;
		int top = chartY + 15;
		int bottom = chartY + chartH - 12;
		double slot = (right - left) / (double) DAYS;
		int barW = Math.max(2, (int) (slot * 0.7));
		g.fill(left, bottom, right, bottom + 1, 0x44FFFFFF);

		int hover = -1;
		for (int i = 0; i < DAYS; i++) {
			int cx = left + (int) Math.round(slot * i + slot / 2);
			int x1 = cx - barW / 2;
			boolean hovered = mx >= left + slot * i && mx < left + slot * (i + 1) && my >= top && my <= bottom + 10;
			if (hovered) {
				hover = i;
			}
			if (money[i] > 0 && max > 0) {
				int h = Math.max(1, (int) Math.round((bottom - top) * money[i] / max));
				g.fill(x1, bottom - h, x1 + barW, bottom, hovered ? BAR_HOVER : BAR);
			}
			// Datum unter jedem zweiten Balken (immer heute)
			if (i == DAYS - 1 || (DAYS - 1 - i) % 2 == 0 && slot >= 18) {
				String label = i == DAYS - 1 ? Component.translatable("loothub.history.today").getString()
						: DAY.format(today.minusDays(DAYS - 1 - i));
				int lw = font.width(label);
				if (slot * 2 >= lw + 2 || i == DAYS - 1) {
					g.drawString(font, label, Mth.clamp(cx - lw / 2, left, right - lw), bottom + 3, FAINT, false);
				}
			}
		}

		if (hover >= 0) {
			LocalDate d = today.minusDays(DAYS - 1 - hover);
			List<FormattedCharSequence> tip = new ArrayList<>();
			tip.add(Component.literal(DAY.format(d) + d.getYear()).withStyle(ChatFormatting.WHITE).getVisualOrderText());
			tip.add(Component.literal(Fmt.money(money[hover])).withStyle(ChatFormatting.GOLD).getVisualOrderText());
			tip.add(Component.literal(Fmt.compact(xp[hover]) + " XP").withStyle(ChatFormatting.GRAY).getVisualOrderText());
			g.setTooltipForNextFrame(font, tip, mx, my);
		}
	}

	private void renderList(GuiGraphics g, int mx, int my) {
		panel(g, listX, listY, listW, listH);

		// Spalten (rechtsbündig für Zahlen); die Level-Spalte nur, wenn genug Platz ist
		boolean showLevel = listW >= 470;
		int colDate = listX + 5;
		int colJob = colDate + 68;
		int colLevelR = listX + listW - 9;
		int colRateR = showLevel ? colLevelR - 64 : colLevelR;
		int colMoneyR = colRateR - 58;
		int colXpR = colMoneyR - 66;
		int colTimeR = colXpR - 52;
		int jobW = colTimeR - 72 - colJob; // Platz für die rechtsbündige Dauer lassen

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
		g.fill(listX + 1, listY + 14, listX + listW - 1, listY + 15, BORDER);

		int top = listY + 16;
		int bottom = listY + listH - 1;
		if (rows.isEmpty()) {
			Component msg = Component.translatable("loothub.history.empty");
			g.drawString(font, msg, listX + (listW - font.width(msg)) / 2, top + 8, FAINT, false);
			return;
		}

		JobHistory.Session hovered = null;
		g.enableScissor(listX + 1, top, listX + listW - 1, bottom);
		for (int i = Math.max(0, (int) (scroll / ROW_H)); i < rows.size(); i++) {
			int ry = top + 1 + i * ROW_H - (int) scroll;
			if (ry > bottom) {
				break;
			}
			JobHistory.Session s = rows.get(i);
			Values v = values(s);
			boolean over = mx >= listX && mx < listX + listW && my >= Math.max(ry, top) && my < Math.min(ry + ROW_H, bottom);
			if (over) {
				hovered = s;
				g.fill(listX + 1, ry - 1, listX + listW - 1, ry + ROW_H - 1, ROW_HOVER);
			}
			int ty = ry + 1;
			g.drawString(font, Fmt.date(s.start, true), colDate, ty, s.live ? GREEN : TEXT, false);

			JobHistory.JobEntry main = jobFilter != null ? s.job(jobFilter) : s.mainJob();
			String job = main == null ? "?" : main.job;
			if (jobFilter == null && s.jobs.size() > 1) {
				job += " +" + (s.jobs.size() - 1);
			}
			if (s.live) {
				job += " " + Component.translatable("loothub.history.live").getString();
			}
			g.drawString(font, ellipsize(job, jobW), colJob, ty, s.live ? GREEN : TEXT, false);
			right(g, Fmt.duration(v.activeMs()), colTimeR, ty, MUTED);
			right(g, Fmt.compact(v.xp()), colXpR, ty, BLUE);
			right(g, Fmt.compact(v.money()), colMoneyR, ty, GOLD);
			right(g, v.activeMs() >= MIN_ACTIVE_FOR_RATE_MS ? Fmt.compact(v.money() / (v.activeMs() / 3_600_000.0)) : "–",
					colRateR, ty, MUTED);
			if (showLevel && main != null) {
				right(g, levelText(main), colLevelR, ty, MUTED);
			}
		}
		g.disableScissor();

		int contentH = rows.size() * ROW_H;
		int viewH = bottom - top;
		if (contentH > viewH) {
			int thumbH = Math.max(10, viewH * viewH / contentH);
			int thumbY = top + (int) ((viewH - thumbH) * (scroll / Math.max(1, contentH - viewH)));
			g.fill(listX + listW - 4, top, listX + listW - 1, bottom, 0x33FFFFFF);
			g.fill(listX + listW - 4, thumbY, listX + listW - 1, thumbY + thumbH, 0xAAFFFFFF);
		}

		if (hovered != null) {
			g.setTooltipForNextFrame(font, tooltip(hovered), mx, my);
		}
	}

	private List<FormattedCharSequence> tooltip(JobHistory.Session s) {
		List<FormattedCharSequence> tip = new ArrayList<>();
		String range = Fmt.date(s.start, true) + " – " + Fmt.time(s.end);
		tip.add(Component.literal(range).withStyle(ChatFormatting.WHITE).getVisualOrderText());
		tip.add(Component.translatable("loothub.history.tip.active", Fmt.duration(s.activeMs))
				.withStyle(ChatFormatting.GRAY).getVisualOrderText());
		for (JobHistory.JobEntry j : s.jobs) {
			tip.add(Component.literal(j.job).withStyle(ChatFormatting.GOLD)
					.append(Component.literal(": +" + Fmt.compact(j.xp) + " XP · +" + Fmt.money(j.money)).withStyle(ChatFormatting.WHITE))
					.getVisualOrderText());
			String level = levelDetail(j);
			if (!level.isEmpty()) {
				tip.add(Component.literal("  " + level).withStyle(ChatFormatting.GRAY).getVisualOrderText());
			}
		}
		return tip;
	}

	/** Kurz für die Spalte: "58 → 59" oder "58". */
	private static String levelText(JobHistory.JobEntry j) {
		if (j.levelEnd < 0) {
			return "–";
		}
		if (j.levelStart >= 0 && j.levelStart != j.levelEnd) {
			return j.levelStart + " → " + j.levelEnd;
		}
		return String.valueOf(j.levelEnd);
	}

	/** Ausführlich für den Tooltip: "Level 58 (13,8 %) → 59 (2,1 %)". */
	private static String levelDetail(JobHistory.JobEntry j) {
		if (j.levelEnd < 0) {
			return "";
		}
		String start = j.levelStart >= 0 ? "Level " + j.levelStart + percent(j.progressStart) : "";
		String end = "Level " + j.levelEnd + percent(j.progressEnd);
		return start.isEmpty() ? end : start + " → " + end;
	}

	private static String percent(double p) {
		return p < 0 ? "" : " (" + Fmt.compact(p) + " %)";
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
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
		scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() * ROW_H - viewH));
	}

	private static LocalDate day(long epochMillis) {
		return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate();
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

	private static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL);
		g.fill(x, y, x + w, y + 1, BORDER);
		g.fill(x, y + h - 1, x + w, y + h, BORDER);
		g.fill(x, y, x + 1, y + h, BORDER);
		g.fill(x + w - 1, y, x + w, y + h, BORDER);
	}
}
