package de.loothub.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.loothub.client.MarketHistory.Interval;
import de.loothub.client.MarketHistory.Point;
import de.loothub.client.MarketPrices.Listing;
import de.loothub.client.MarketPrices.Side;

/**
 * Seite „Markt“: in der Mitte alle Items als Raster wie im Inventar (Suche + Kategorie). Ein Klick öffnet
 * die Preise und das Diagramm mit Durchschnittspreis (Linie) sowie Tiefst- bis Höchstpreis je Zeitabschnitt (Balken).
 * Öffnen mit Taste L (änderbar in der Steuerung), Reiter „Markt“.
 */
public class MarketScreen extends LoothubScreen {
	// Farben (ARGB – Alpha muss gesetzt sein)
	private static final int PANEL = 0xE01C1315;
	private static final int BORDER = 0xFF3A1F23;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFB0B0C0;
	private static final int FAINT = 0xFF707080;
	private static final int GRID = 0x22FFFFFF;
	private static final int GREEN = 0xFF55DD77;
	private static final int RED = 0xFFFF6464;
	private static final int GOLD = 0xFFFFC040;
	private static final int BAND = 0x5566AAFF;
	private static final int BAND_HOVER = 0x9966AAFF;

	private static final int PAD = 8;
	private static final int SLOT = 20;      // Raster: 18 px Slot + 2 px Abstand
	private static final int SLOT_INNER = 18;
	private static final int SLOT_BG = 0xFF24171A;
	private static final int SLOT_EDGE_DARK = 0xFF0C0708;
	private static final int SLOT_EDGE_LIGHT = 0xFF3E272B;
	private static final int ITEM_BG = 0xC0181012;
	private static final int ITEM_BG_HOVER = 0xD0301A1E;
	private static final int ITEM_BORDER = 0xFF3E2226;
	private static final int ITEM_BORDER_HOVER = 0xFFE23B3B;

	// Bleibt zwischen zwei Öffnungen des Menüs erhalten
	private static String search = "";
	private static String selectedMaterial;
	private static Interval interval = Interval.DAILY;
	private static String category; // null = alle

	private static final Map<String, ItemStack> STACKS = new HashMap<>();
	private static final Map<String, String> NAMES = new HashMap<>();

	private final List<Button> intervalButtons = new ArrayList<>();
	private Button categoryButton;
	/** false = Item-Liste, true = Diagramm des gewählten Items. */
	private boolean detail;
	private List<Listing> filtered = List.of();
	private List<String> categories = List.of();
	private int seenVersion = -1;
	private double scroll;

	private int listX, listY, listW, listH;
	private int gridCols = 9;
	private int detailX, detailY, detailW, detailH;
	private int chartX, chartY, chartW, chartH;

	public MarketScreen() {
		super(Page.MARKET);
	}

	// ------------------------------------------------------------------ Aufbau

	@Override
	protected void initPage() {
		MarketPrices.refreshIfStale();

		intervalButtons.clear();
		categoryButton = null;

		if (detail && selected() != null) {
			initDetail();
		} else {
			detail = false;
			initList();
		}
	}

	/** Ansicht 1: Liste mit Item-Knöpfen in der Bildschirmmitte. */
	private void initList() {
		int top = ct;
		// Raster mittig: so viele Spalten wie passen (höchstens 18)
		gridCols = Mth.clamp(cw / SLOT, 4, 18);
		int gridW = gridCols * SLOT;
		int boxW = Math.min(cw, Math.max(gridW, 220));
		listW = gridW + 6; // Platz für die Bildlaufleiste
		listX = cl + (cw - listW) / 2;
		int boxX = cl + (cw - boxW) / 2;

		EditBox searchBox = new EditBox(font, boxX, top, boxW, 16, Component.translatable("loothub.screen.search"));
		searchBox.setHint(Component.translatable("loothub.screen.search_hint").withStyle(ChatFormatting.DARK_GRAY));
		searchBox.setValue(search);
		searchBox.setResponder(text -> {
			search = text;
			scroll = 0;
			updateFilter();
		});
		addRenderableWidget(searchBox);

		categoryButton = addRenderableWidget(Button.builder(categoryLabel(), b -> cycleCategory())
				.bounds(boxX, top + 20, boxW, 16)
				.build());

		listY = top + 42;
		listH = cb - listY;

		seenVersion = -1; // erzwingt Neuaufbau der Liste
		refreshData();
		setFocused(searchBox);
	}

	/** Ansicht 2: Preise und Diagramm des angeklickten Items, mit „Zurück“ zur Liste. */
	private void initDetail() {
		addRenderableWidget(Button.builder(Component.translatable("loothub.screen.back"), b -> showList())
				.bounds(cl, ct, 80, 16)
				.build());

		detailX = cl;
		detailY = ct + 20;
		detailW = cw;
		detailH = cb - detailY;

		Interval[] values = Interval.values();
		int gap = 4;
		int bw = Math.min(64, (detailW - 12 - gap * (values.length - 1)) / values.length);
		int bx = detailX + 6;
		int by = detailY + 42;
		for (Interval iv : values) {
			Button b = Button.builder(Component.translatable(iv.translationKey()), btn -> {
				interval = iv;
				updateIntervalButtons();
			}).bounds(bx, by, bw, 16).build();
			intervalButtons.add(addRenderableWidget(b));
			bx += bw + gap;
		}
		updateIntervalButtons();

		chartX = detailX + 6;
		chartY = by + 22;
		chartW = detailW - 12;
		chartH = detailY + detailH - chartY - 6;
	}

	private void showDetail(Listing l) {
		selectedMaterial = l.material();
		detail = true;
		clearWidgets();
		init();
	}

	private void showList() {
		detail = false;
		clearWidgets();
		init();
	}

	/** Esc im Diagramm führt zurück zur Liste, in der Liste schließt es das Menü. */
	@Override
	public void onClose() {
		if (detail) {
			showList();
		} else {
			super.onClose();
		}
	}

	@Override
	public void tick() {
		super.tick();
		MarketPrices.refreshIfStale();
		refreshData();
	}

	private void refreshData() {
		if (seenVersion == MarketPrices.version()) {
			return;
		}
		seenVersion = MarketPrices.version();

		Set<String> cats = new LinkedHashSet<>();
		for (Listing l : MarketPrices.listings()) {
			cats.add(l.category());
		}
		List<String> sorted = new ArrayList<>(cats);
		sorted.sort(String.CASE_INSENSITIVE_ORDER);
		categories = sorted;
		if (category != null && !categories.contains(category)) {
			category = null;
		}
		if (categoryButton != null) {
			categoryButton.setMessage(categoryLabel());
		}
		updateFilter();
	}

	private void updateFilter() {
		String q = search.toLowerCase(Locale.ROOT).strip();
		List<Listing> out = new ArrayList<>();
		for (Listing l : MarketPrices.listings()) {
			if (category != null && !category.equals(l.category())) {
				continue;
			}
			if (!q.isEmpty()
					&& !name(l).toLowerCase(Locale.ROOT).contains(q)
					&& !l.itemId().contains(q.replace(' ', '_'))) {
				continue;
			}
			out.add(l);
		}
		out.sort(Comparator.comparing(MarketScreen::name, String.CASE_INSENSITIVE_ORDER));
		filtered = out;
		clampScroll();
	}

	private void cycleCategory() {
		if (categories.isEmpty()) {
			category = null;
		} else if (category == null) {
			category = categories.get(0);
		} else {
			int i = categories.indexOf(category);
			category = i < 0 || i + 1 >= categories.size() ? null : categories.get(i + 1);
		}
		categoryButton.setMessage(categoryLabel());
		scroll = 0;
		updateFilter();
	}

	private Component categoryLabel() {
		Component value = category == null
				? Component.translatable("loothub.screen.category.all")
				: Component.literal(prettify(category));
		return Component.translatable("loothub.screen.category", value);
	}

	private void updateIntervalButtons() {
		Interval[] values = Interval.values();
		for (int i = 0; i < intervalButtons.size(); i++) {
			intervalButtons.get(i).active = values[i] != interval;
		}
	}

	private Listing selected() {
		if (selectedMaterial == null) {
			return null;
		}
		for (Listing l : MarketPrices.listings()) {
			if (l.material().equals(selectedMaterial)) {
				return l;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		if (detail) {
			// Hintergrund zuerst, damit die Zeitraum-Knöpfe darüber liegen
			panel(g, detailX, detailY, detailW, detailH);
			super.render(g, mouseX, mouseY, delta);
			renderDetail(g, mouseX, mouseY);
		} else {
			super.render(g, mouseX, mouseY, delta);
			renderList(g, mouseX, mouseY);
		}
	}

	@Override
	protected Component headerRight() {
		if (MarketPrices.hasData()) {
			return Component.translatable("loothub.screen.status", Fmt.time(MarketPrices.lastUpdate()));
		}
		return Component.translatable(MarketPrices.lastAttemptFailed() ? "loothub.tooltip.error" : "loothub.tooltip.loading");
	}

	private void renderList(GuiGraphics g, int mx, int my) {
		if (filtered.isEmpty()) {
			Component msg = MarketPrices.hasData()
					? Component.translatable("loothub.screen.no_results")
					: Component.translatable("loothub.tooltip.loading");
			centered(g, msg, listX + listW / 2, listY + 10, FAINT);
			return;
		}

		int top = listY;
		int bottom = listY + listH;
		boolean scrollbar = contentHeight() > listH;

		Listing hoveredListing = null;
		g.enableScissor(listX, top, listX + listW, bottom);
		int firstRow = Math.max(0, (int) (scroll / SLOT));
		for (int i = firstRow * gridCols; i < filtered.size(); i++) {
			int col = i % gridCols;
			int row = i / gridCols;
			int sx = listX + col * SLOT;
			int sy = top + row * SLOT - (int) scroll;
			if (sy > bottom) {
				break;
			}
			Listing l = filtered.get(i);
			boolean hovered = mx >= sx && mx < sx + SLOT_INNER && my >= Math.max(sy, top)
					&& my < Math.min(sy + SLOT_INNER, bottom);

			// Slot wie im Inventar: dunkle Kante oben/links, helle unten/rechts
			g.fill(sx, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_BG);
			g.fill(sx, sy, sx + SLOT_INNER, sy + 1, SLOT_EDGE_DARK);
			g.fill(sx, sy, sx + 1, sy + SLOT_INNER, SLOT_EDGE_DARK);
			g.fill(sx, sy + SLOT_INNER - 1, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_EDGE_LIGHT);
			g.fill(sx + SLOT_INNER - 1, sy, sx + SLOT_INNER, sy + SLOT_INNER, SLOT_EDGE_LIGHT);
			g.renderItem(stack(l), sx + 1, sy + 1);
			if (hovered) {
				hoveredListing = l;
				g.fill(sx + 1, sy + 1, sx + SLOT_INNER - 1, sy + SLOT_INNER - 1, 0x60FFFFFF);
				g.fill(sx - 1, sy - 1, sx + SLOT_INNER + 1, sy, ITEM_BORDER_HOVER);
				g.fill(sx - 1, sy + SLOT_INNER, sx + SLOT_INNER + 1, sy + SLOT_INNER + 1, ITEM_BORDER_HOVER);
				g.fill(sx - 1, sy, sx, sy + SLOT_INNER, ITEM_BORDER_HOVER);
				g.fill(sx + SLOT_INNER, sy, sx + SLOT_INNER + 1, sy + SLOT_INNER, ITEM_BORDER_HOVER);
			}
		}
		g.disableScissor();

		if (scrollbar) {
			int thumbH = Math.max(12, listH * listH / contentHeight());
			int thumbY = top + (int) ((listH - thumbH) * (scroll / maxScroll()));
			g.fill(listX + listW - 3, top, listX + listW, bottom, 0x33FFFFFF);
			g.fill(listX + listW - 3, thumbY, listX + listW, thumbY + thumbH, 0xAAFFFFFF);
		}

		// Tooltip: genaue Preise und Hinweis aufs Diagramm
		if (hoveredListing != null) {
			List<FormattedCharSequence> tip = new ArrayList<>();
			tip.add(Component.literal(name(hoveredListing)).withStyle(ChatFormatting.WHITE).getVisualOrderText());
			tip.add(priceLine("loothub.tooltip.buy", hoveredListing.entry().buy(), ChatFormatting.GOLD));
			tip.add(priceLine("loothub.tooltip.sell", hoveredListing.entry().sell(), ChatFormatting.GREEN));
			tip.add(Component.translatable("loothub.screen.click_for_chart").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
			g.setTooltipForNextFrame(font, tip, mx, my);
		}
	}

	private static FormattedCharSequence priceLine(String labelKey, Side side, ChatFormatting color) {
		Component value = side.available()
				? Component.literal(Fmt.money(side.price())).withStyle(color)
						.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
						.append(Component.translatable("loothub.tooltip.orders", side.activeOrders()).withStyle(ChatFormatting.DARK_GRAY))
				: Component.translatable("loothub.tooltip.none").withStyle(ChatFormatting.DARK_GRAY);
		return Component.translatable(labelKey).withStyle(ChatFormatting.GRAY)
				.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
				.append(value)
				.getVisualOrderText();
	}

	private void renderDetail(GuiGraphics g, int mx, int my) {
		Listing l = selected();
		if (l == null) {
			centered(g, Component.translatable("loothub.screen.select_hint"),
					detailX + detailW / 2, detailY + detailH / 2, FAINT);
			return;
		}

		// Kopf: Icon, Name, Kategorie
		g.renderItem(stack(l), detailX + 6, detailY + 6);
		g.drawString(font, ellipsize(name(l), detailW - 34), detailX + 28, detailY + 6, TEXT, false);
		g.drawString(font, ellipsize(prettify(l.category()), detailW - 34), detailX + 28, detailY + 16, FAINT, false);

		// Aktuelle Preise
		int py = detailY + 28;
		int px = detailX + 6;
		px = drawPrice(g, px, py, "loothub.tooltip.buy", l.entry().buy(), GOLD);
		drawPrice(g, px + 12, py, "loothub.tooltip.sell", l.entry().sell(), GREEN);

		// Diagramm
		MarketHistory.request(l.material());
		List<Point> points = MarketHistory.get(l.material(), interval);
		if (points == null) {
			Component msg = MarketHistory.hasError(l.material())
					? Component.translatable("loothub.screen.history_error")
					: Component.translatable("loothub.screen.history_loading");
			centered(g, msg, chartX + chartW / 2, chartY + chartH / 2 - 4,
					MarketHistory.hasError(l.material()) ? RED : FAINT);
			return;
		}
		renderChart(g, points, mx, my);
	}

	/** Zeichnet "Label: Preis" und gibt die x-Position hinter dem Text zurück. */
	private int drawPrice(GuiGraphics g, int x, int y, String labelKey, Side side, int color) {
		Component label = Component.translatable(labelKey).append(": ");
		g.drawString(font, label, x, y, MUTED, false);
		x += font.width(label);
		String value = side.available() ? Fmt.money(side.price()) : "–";
		g.drawString(font, value, x, y, side.available() ? color : FAINT, false);
		return x + font.width(value);
	}

	private void renderChart(GuiGraphics g, List<Point> all, int mx, int my) {
		List<Point> pts = all.subList(Math.max(0, all.size() - interval.maxPoints()), all.size());

		// Kennzahlen: Hoch, Tief, Veränderung
		int hiIdx = -1, loIdx = -1, firstAvg = -1, lastAvg = -1;
		double hi = -Double.MAX_VALUE, lo = Double.MAX_VALUE;
		for (int i = 0; i < pts.size(); i++) {
			Point p = pts.get(i);
			double top = p.hasRange() ? p.max() : p.hasAvg() ? p.avg() : Double.NaN;
			double bottom = p.hasRange() ? p.min() : p.hasAvg() ? p.avg() : Double.NaN;
			if (!Double.isNaN(top) && top > hi) {
				hi = top;
				hiIdx = i;
			}
			if (!Double.isNaN(bottom) && bottom < lo) {
				lo = bottom;
				loIdx = i;
			}
			if (p.hasAvg()) {
				if (firstAvg < 0) {
					firstAvg = i;
				}
				lastAvg = i;
			}
		}
		if (hiIdx < 0 || loIdx < 0) {
			centered(g, Component.translatable("loothub.screen.no_history"),
					chartX + chartW / 2, chartY + chartH / 2 - 4, FAINT);
			return;
		}

		// Legende über dem Diagramm
		int lx = chartX;
		lx = legend(g, lx, chartY, "loothub.chart.high", Fmt.money(hi), GREEN);
		lx = legend(g, lx + 10, chartY, "loothub.chart.low", Fmt.money(lo), RED);
		if (firstAvg >= 0 && lastAvg > firstAvg && pts.get(firstAvg).avg() > 0) {
			double change = (pts.get(lastAvg).avg() / pts.get(firstAvg).avg() - 1) * 100;
			legend(g, lx + 10, chartY, "loothub.chart.change", Fmt.percent(change), change >= 0 ? GREEN : RED);
		}

		int left = chartX + 40;
		int right = chartX + chartW - 4;
		int top = chartY + 16;
		int bottom = chartY + chartH - 12;
		if (right - left < 40 || bottom - top < 30) {
			return; // Fenster zu klein
		}

		double yMin = lo;
		double yMax = hi;
		if (yMax - yMin < 1e-9) {
			yMax += Math.max(1, yMax * 0.1);
			yMin -= Math.max(1, yMin * 0.1);
		}
		double pad = (yMax - yMin) * 0.08;
		yMin = Math.max(0, yMin - pad);
		yMax += pad;

		// Raster + Preisachse
		for (int i = 0; i <= 4; i++) {
			int gy = bottom - (bottom - top) * i / 4;
			g.fill(left, gy, right, gy + 1, GRID);
			String label = Fmt.compact(yMin + (yMax - yMin) * i / 4.0);
			g.drawString(font, label, left - 4 - font.width(label), gy - 4, FAINT, false);
		}

		int n = pts.size();
		int[] xs = new int[n];
		for (int i = 0; i < n; i++) {
			xs[i] = n == 1 ? (left + right) / 2 : left + (int) Math.round(i * (right - left) / (double) (n - 1));
		}
		double step = n == 1 ? 12 : (right - left) / (double) (n - 1);
		int barW = Mth.clamp((int) (step * 0.6), 1, 10);

		// Hover-Index bestimmen
		int hover = -1;
		if (mx >= left - barW && mx <= right + barW && my >= top && my <= bottom) {
			int best = Integer.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				int d = Math.abs(xs[i] - mx);
				if (d < best) {
					best = d;
					hover = i;
				}
			}
		}

		// Balken: Tiefst- bis Höchstpreis je Abschnitt
		for (int i = 0; i < n; i++) {
			Point p = pts.get(i);
			if (!p.hasRange()) {
				continue;
			}
			int y1 = toY(p.max(), yMin, yMax, top, bottom);
			int y2 = toY(p.min(), yMin, yMax, top, bottom);
			int x1 = xs[i] - barW / 2;
			g.fill(x1, y1, x1 + barW, Math.max(y2, y1 + 1) + 1, i == hover ? BAND_HOVER : BAND);
		}

		// Linie: Durchschnittspreis (Lücken bleiben offen)
		int prevX = Integer.MIN_VALUE, prevY = 0;
		for (int i = 0; i < n; i++) {
			Point p = pts.get(i);
			if (!p.hasAvg()) {
				prevX = Integer.MIN_VALUE;
				continue;
			}
			int y = toY(p.avg(), yMin, yMax, top, bottom);
			if (prevX != Integer.MIN_VALUE) {
				line(g, prevX, prevY, xs[i], y, GOLD);
			} else {
				g.fill(xs[i] - 1, y - 1, xs[i] + 1, y + 1, GOLD);
			}
			prevX = xs[i];
			prevY = y;
		}

		// Markierungen für Hoch und Tief
		marker(g, xs[hiIdx], toY(hi, yMin, yMax, top, bottom), GREEN);
		marker(g, xs[loIdx], toY(lo, yMin, yMax, top, bottom), RED);

		// Zeitachse: erster und letzter Wert
		String from = Fmt.date(pts.get(0).time(), interval.showTime());
		String to = Fmt.date(pts.get(n - 1).time(), interval.showTime());
		g.drawString(font, from, left, bottom + 3, FAINT, false);
		if (font.width(from) + font.width(to) + 8 < right - left) {
			g.drawString(font, to, right - font.width(to), bottom + 3, FAINT, false);
		}

		// Hover: Hilfslinie + Details
		if (hover >= 0) {
			Point p = pts.get(hover);
			g.fill(xs[hover], top, xs[hover] + 1, bottom, 0x55FFFFFF);
			List<FormattedCharSequence> tip = new ArrayList<>();
			tip.add(Component.literal(Fmt.date(p.time(), interval.showTime())).withStyle(ChatFormatting.WHITE).getVisualOrderText());
			if (p.hasAvg()) {
				tip.add(tipLine("loothub.chart.avg", Fmt.money(p.avg()), ChatFormatting.GOLD));
			}
			if (p.hasRange()) {
				tip.add(tipLine("loothub.chart.high", Fmt.money(p.max()), ChatFormatting.GREEN));
				tip.add(tipLine("loothub.chart.low", Fmt.money(p.min()), ChatFormatting.RED));
			}
			if (!p.hasAvg() && !p.hasRange()) {
				tip.add(Component.translatable("loothub.chart.no_trades").withStyle(ChatFormatting.GRAY).getVisualOrderText());
			} else {
				tip.add(tipLine("loothub.chart.items", Fmt.whole(p.items()), ChatFormatting.GRAY));
				tip.add(tipLine("loothub.chart.transactions", Fmt.whole(p.transactions()), ChatFormatting.GRAY));
			}
			g.setTooltipForNextFrame(font, tip, mx, my);
		}
	}

	private int legend(GuiGraphics g, int x, int y, String labelKey, String value, int color) {
		Component label = Component.translatable(labelKey).append(": ");
		g.drawString(font, label, x, y, MUTED, false);
		x += font.width(label);
		g.drawString(font, value, x, y, color, false);
		return x + font.width(value);
	}

	private static FormattedCharSequence tipLine(String labelKey, String value, ChatFormatting color) {
		return Component.translatable(labelKey).withStyle(ChatFormatting.GRAY)
				.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(value).withStyle(color))
				.getVisualOrderText();
	}

	private static int toY(double v, double min, double max, int top, int bottom) {
		return bottom - (int) Math.round((v - min) / (max - min) * (bottom - top));
	}

	/** 2 Pixel dicke Linie aus kleinen Rechtecken. */
	private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
		if (x2 < x1) {
			int tx = x1, ty = y1;
			x1 = x2;
			y1 = y2;
			x2 = tx;
			y2 = ty;
		}
		if (x1 == x2) {
			g.fill(x1, Math.min(y1, y2), x1 + 2, Math.max(y1, y2) + 2, color);
			return;
		}
		int prev = y1;
		for (int px = x1; px <= x2; px++) {
			int py = (int) Math.round(y1 + (y2 - y1) * (px - x1) / (double) (x2 - x1));
			g.fill(px, Math.min(prev, py), px + 1, Math.max(prev, py) + 2, color);
			prev = py;
		}
	}

	private static void marker(GuiGraphics g, int x, int y, int color) {
		g.fill(x - 3, y - 3, x + 3, y + 3, 0xFF000000);
		g.fill(x - 2, y - 2, x + 2, y + 2, color);
	}

	private void centered(GuiGraphics g, Component text, int centerX, int y, int color) {
		g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
	}

	private static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL);
		g.fill(x, y, x + w, y + 1, BORDER);
		g.fill(x, y + h - 1, x + w, y + h, BORDER);
		g.fill(x, y, x + 1, y + h, BORDER);
		g.fill(x + w - 1, y, x + w, y + h, BORDER);
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		if (!detail && event.button() == 0 && mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
			double offY = my - listY + scroll;
			double offX = mx - listX;
			int row = (int) (offY / SLOT);
			int col = (int) (offX / SLOT);
			boolean onSlot = offY - row * SLOT < SLOT_INNER && offX - col * SLOT < SLOT_INNER && col < gridCols;
			int index = row * gridCols + col;
			if (onSlot && index >= 0 && index < filtered.size()) {
				showDetail(filtered.get(index));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (!detail && mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
			scroll -= vertical * SLOT * 2;
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

	private int contentHeight() {
		int rows = (filtered.size() + gridCols - 1) / gridCols;
		return rows * SLOT;
	}

	private double maxScroll() {
		return Math.max(1, contentHeight() - listH);
	}

	private void clampScroll() {
		scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight() - listH));
	}

	private String ellipsize(String s, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (font.width(s) <= maxWidth) {
			return s;
		}
		int dots = font.width("…");
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end)) + dots > maxWidth) {
			end--;
		}
		return s.substring(0, end) + "…";
	}

	private static ItemStack stack(Listing l) {
		return STACKS.computeIfAbsent(l.itemId(), id -> {
			Identifier identifier = Identifier.tryParse(id);
			Item item = identifier == null ? Items.BARRIER : BuiltInRegistries.ITEM.getOptional(identifier).orElse(Items.BARRIER);
			return new ItemStack(item == Items.AIR ? Items.BARRIER : item);
		});
	}

	/** Übersetzter Item-Name (z. B. „Diamantblock“), sonst lesbarer API-Name. */
	private static String name(Listing l) {
		return NAMES.computeIfAbsent(l.itemId(), id -> {
			ItemStack s = stack(l);
			return s.is(Items.BARRIER) ? prettify(l.material()) : s.getHoverName().getString();
		});
	}

	/** "DIAMOND_BLOCK" -> "Diamond Block" */
	private static String prettify(String raw) {
		StringBuilder sb = new StringBuilder();
		for (String part : raw.toLowerCase(Locale.ROOT).split("[_\\s]+")) {
			if (part.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return sb.toString();
	}
}
