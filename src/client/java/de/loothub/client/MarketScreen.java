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
 * Seite „Markt“: alle Items als Raster aus Feldern über die ganze Breite (Name, Kauf- und Verkaufspreis). Ein Klick öffnet
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
	private static final int TILE_MIN_W = 118; // so breit, dass Name und beide Preise passen
	private static final int TILE_H = 30;
	private static final int TILE_GAP = 4;
	private static final int TILE_BG = 0xFF1E1416;
	private static final int TILE_BG_HOVER = 0xFF2E1C20;
	private static final int TILE_EDGE = 0xFF3E272B;
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
	private int gridCols = 4;
	private int tileW = TILE_MIN_W;
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
		// Raster über die ganze Breite: so viele Felder wie passen, Rest verteilt sich auf die Felder
		listX = cl;
		listW = cw;
		int usable = listW - 6; // Platz für die Bildlaufleiste
		gridCols = Math.max(1, (usable + TILE_GAP) / (TILE_MIN_W + TILE_GAP));
		tileW = (usable - (gridCols - 1) * TILE_GAP) / gridCols;
		// Suche und Kategorie nebeneinander
		int catW = Mth.clamp(cw / 3, 110, 200);
		int boxX = cl;
		int boxW = cw - catW - 4;

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
				.bounds(cr - catW, top, catW, 16)
				.build());

		listY = top + 22;
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
		int step = TILE_H + TILE_GAP;
		int firstRow = Math.max(0, (int) (scroll / step));
		for (int i = firstRow * gridCols; i < filtered.size(); i++) {
			int col = i % gridCols;
			int row = i / gridCols;
			int tx = listX + col * (tileW + TILE_GAP);
			int ty = top + row * step - (int) scroll;
			if (ty > bottom) {
				break;
			}
			Listing l = filtered.get(i);
			boolean hovered = mx >= tx && mx < tx + tileW && my >= Math.max(ty, top) && my < Math.min(ty + TILE_H, bottom);
			if (hovered) {
				hoveredListing = l;
			}

			// Feld: Rahmen (rot beim Überfahren), Hintergrund
			g.fill(tx, ty, tx + tileW, ty + TILE_H, hovered ? ITEM_BORDER_HOVER : TILE_EDGE);
			g.fill(tx + 1, ty + 1, tx + tileW - 1, ty + TILE_H - 1, hovered ? TILE_BG_HOVER : TILE_BG);
			g.renderItem(stack(l), tx + 5, ty + 7);

			// Zeile 1: Name · Zeile 2: Kaufen (gold) links, Verkaufen (grün) rechts
			int textX = tx + 26;
			int textW = tileW - 26 - 5;
			g.drawString(font, ellipsize(name(l), textW), textX, ty + 5, TEXT, false);
			Side buy = l.entry().buy();
			Side sell = l.entry().sell();
			String buyText = buy.available() ? Fmt.compact(buy.price()) + " $" : "–";
			String sellText = sell.available() ? Fmt.compact(sell.price()) + " $" : "–";
			g.drawString(font, buyText, textX, ty + 17, buy.available() ? GOLD : FAINT, false);
			g.drawString(font, sellText, tx + tileW - 5 - font.width(sellText), ty + 17, sell.available() ? GREEN : FAINT, false);
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

		int left = chartX + 44;
		int right = chartX + chartW - 6;
		int top = chartY + 16;
		int bottom = chartY + chartH - 14;
		if (right - left < 40 || bottom - top < 40) {
			return; // Fenster zu klein
		}
		int n = pts.size();

		// Skala nach dem Durchschnittspreis – so nutzt die Linie die ganze Höhe.
		// Die Spanne Tief–Hoch wird dahinter nur angedeutet (und am Rand abgeschnitten).
		double aMin = Double.MAX_VALUE, aMax = -Double.MAX_VALUE;
		int aMinIdx = -1, aMaxIdx = -1;
		long maxItems = 0;
		for (int i = 0; i < n; i++) {
			Point p = pts.get(i);
			if (p.hasAvg()) {
				if (p.avg() < aMin) {
					aMin = p.avg();
					aMinIdx = i;
				}
				if (p.avg() > aMax) {
					aMax = p.avg();
					aMaxIdx = i;
				}
			}
			maxItems = Math.max(maxItems, p.items());
		}
		if (aMinIdx < 0) {
			aMin = lo;
			aMax = hi;
		}
		double yMin = aMin;
		double yMax = aMax;
		if (yMax - yMin < 1e-9) {
			yMax += Math.max(1, yMax * 0.1);
			yMin -= Math.max(1, yMin * 0.1);
		}
		double pad = (yMax - yMin) * 0.15;
		yMin = Math.max(0, yMin - pad);
		yMax += pad;

		// Plotfläche + gestrichelte Hilfslinien mit Preisachse
		g.fill(left, top, right, bottom, 0x18000000);
		for (int i = 0; i <= 4; i++) {
			int gy = bottom - (bottom - top) * i / 4;
			for (int dx = left; dx < right; dx += 6) {
				g.fill(dx, gy, Math.min(dx + 3, right), gy + 1, GRID);
			}
			String label = Fmt.compact(yMin + (yMax - yMin) * i / 4.0) + " $";
			g.drawString(font, label, left - 5 - font.width(label), gy - 4, FAINT, false);
		}

		int[] xs = new int[n];
		for (int i = 0; i < n; i++) {
			xs[i] = n == 1 ? (left + right) / 2 : left + (int) Math.round(i * (right - left) / (double) (n - 1));
		}
		double step = n == 1 ? 12 : (right - left) / (double) (n - 1);

		int hover = -1;
		if (mx >= left - 4 && mx <= right + 4 && my >= top && my <= bottom) {
			int best = Integer.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				int d = Math.abs(xs[i] - mx);
				if (d < best) {
					best = d;
					hover = i;
				}
			}
		}

		// Handelsvolumen: dezente Säulen am unteren Rand (Höhe = gehandelte Stück)
		int volH = (bottom - top) / 6;
		int volW = Mth.clamp((int) (step * 0.5), 1, 8);
		if (maxItems > 0) {
			for (int i = 0; i < n; i++) {
				long items = pts.get(i).items();
				if (items <= 0) {
					continue;
				}
				int h = Math.max(1, (int) Math.round(volH * items / (double) maxItems));
				g.fill(xs[i] - volW / 2, bottom - h, xs[i] - volW / 2 + volW, bottom, i == hover ? 0x60FFFFFF : 0x22FFFFFF);
			}
		}

		// Spanne Tief–Hoch als zarte rote Fläche zwischen den Punkten
		for (int i = 0; i < n - 1; i++) {
			Point a = pts.get(i);
			Point b = pts.get(i + 1);
			if (!a.hasRange() || !b.hasRange()) {
				continue;
			}
			for (int px = xs[i]; px < xs[i + 1]; px++) {
				double t = (px - xs[i]) / (double) Math.max(1, xs[i + 1] - xs[i]);
				int yHi = Mth.clamp(toY(a.max() + (b.max() - a.max()) * t, yMin, yMax, top, bottom), top, bottom);
				int yLo = Mth.clamp(toY(a.min() + (b.min() - a.min()) * t, yMin, yMax, top, bottom), top, bottom);
				g.fill(px, yHi, px + 1, Math.max(yLo, yHi + 1), 0x1CE23B3B);
			}
		}

		// Fläche unter der Durchschnittslinie (Verlauf nach unten ausblendend) + Linie
		int prevX = Integer.MIN_VALUE, prevY = 0;
		for (int i = 0; i < n; i++) {
			Point p = pts.get(i);
			if (!p.hasAvg()) {
				prevX = Integer.MIN_VALUE;
				continue;
			}
			int y = toY(p.avg(), yMin, yMax, top, bottom);
			if (prevX != Integer.MIN_VALUE) {
				for (int px = prevX; px < xs[i]; px++) {
					double t = (px - prevX) / (double) Math.max(1, xs[i] - prevX);
					int py = (int) Math.round(prevY + (y - prevY) * t);
					g.fillGradient(px, py, px + 1, bottom, 0x55FFC040, 0x00FFC040);
				}
				line(g, prevX, prevY, xs[i], y, GOLD);
			}
			prevX = xs[i];
			prevY = y;
		}
		// Punkte auf der Linie (nur wenn genug Platz)
		if (step >= 10) {
			for (int i = 0; i < n; i++) {
				Point p = pts.get(i);
				if (p.hasAvg()) {
					int y = toY(p.avg(), yMin, yMax, top, bottom);
					g.fill(xs[i] - 1, y - 1, xs[i] + 2, y + 2, 0xFF1A1012);
					g.fill(xs[i], y, xs[i] + 1, y + 1, GOLD);
				}
			}
		}

		// Höchster und tiefster Durchschnitt mit Wert beschriftet
		if (aMaxIdx >= 0 && aMinIdx >= 0 && aMaxIdx != aMinIdx) {
			valueMarker(g, xs[aMaxIdx], toY(aMax, yMin, yMax, top, bottom), Fmt.compact(aMax) + " $", GREEN, true, left, right);
			valueMarker(g, xs[aMinIdx], toY(aMin, yMin, yMax, top, bottom), Fmt.compact(aMin) + " $", RED, false, left, right);
		}

		// Zeitachse: so viele Daten, wie nebeneinander passen
		int labelW = font.width(Fmt.date(pts.get(0).time(), interval.showTime())) + 10;
		int every = Math.max(1, (int) Math.ceil(labelW / Math.max(1.0, step)));
		for (int i = n - 1; i >= 0; i -= every) {
			String d = Fmt.date(pts.get(i).time(), interval.showTime());
			int lx = Mth.clamp(xs[i] - font.width(d) / 2, left, right - font.width(d));
			g.fill(xs[i], bottom, xs[i] + 1, bottom + 2, FAINT);
			g.drawString(font, d, lx, bottom + 4, i == hover ? TEXT : FAINT, false);
		}

		// Hover: gestrichelte Hilfslinie, hervorgehobener Punkt, Details
		if (hover >= 0) {
			Point p = pts.get(hover);
			for (int dy = top; dy < bottom; dy += 4) {
				g.fill(xs[hover], dy, xs[hover] + 1, Math.min(dy + 2, bottom), 0x88FFFFFF);
			}
			if (p.hasAvg()) {
				int y = toY(p.avg(), yMin, yMax, top, bottom);
				g.fill(xs[hover] - 3, y - 3, xs[hover] + 4, y + 4, 0xFF000000);
				g.fill(xs[hover] - 2, y - 2, xs[hover] + 3, y + 3, GOLD);
			}
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

	/** Punkt mit kleinem Wert-Schild darüber (oben) bzw. darunter. */
	private void valueMarker(GuiGraphics g, int x, int y, String text, int color, boolean above, int left, int right) {
		g.fill(x - 3, y - 3, x + 4, y + 4, 0xFF000000);
		g.fill(x - 2, y - 2, x + 3, y + 3, color);
		int w = font.width(text) + 6;
		int bx = Mth.clamp(x - w / 2, left, right - w);
		int by = above ? y - 16 : y + 6;
		g.fill(bx, by, bx + w, by + 11, 0xE0100A0B);
		g.fill(bx, above ? by + 10 : by, bx + w, above ? by + 11 : by + 1, color);
		g.drawString(font, text, bx + 3, by + 2, color, false);
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
			int step = TILE_H + TILE_GAP;
			int row = (int) (offY / step);
			int col = (int) (offX / (tileW + TILE_GAP));
			boolean onSlot = offY - row * step < TILE_H && offX - col * (tileW + TILE_GAP) < tileW && col < gridCols;
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
			scroll -= vertical * (TILE_H + TILE_GAP);
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
		return rows == 0 ? 0 : rows * (TILE_H + TILE_GAP) - TILE_GAP;
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
