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
 * Markt-Menü: links alle Items vom Marktplatz (Suche + Kategorie), rechts aktuelle Preise und ein
 * Diagramm mit Durchschnittspreis (Linie) sowie Tiefst- bis Höchstpreis je Zeitabschnitt (Balken).
 * Öffnen mit Taste L (änderbar in der Steuerung), Reiter „Markt“.
 */
public class MarketScreen extends Screen {
	// Farben (ARGB – Alpha muss gesetzt sein)
	private static final int PANEL = 0xD0101018;
	private static final int BORDER = 0xFF3C3C4C;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFB0B0C0;
	private static final int FAINT = 0xFF707080;
	private static final int GRID = 0x22FFFFFF;
	private static final int GREEN = 0xFF55DD77;
	private static final int RED = 0xFFFF6464;
	private static final int GOLD = 0xFFFFC040;
	private static final int BAND = 0x5566AAFF;
	private static final int BAND_HOVER = 0x9966AAFF;
	private static final int ROW_HOVER = 0x22FFFFFF;
	private static final int ROW_SELECTED = 0x44FFC040;

	private static final int PAD = 8;
	private static final int ROW_H = 18;

	// Bleibt zwischen zwei Öffnungen des Menüs erhalten
	private static String search = "";
	private static String selectedMaterial;
	private static Interval interval = Interval.DAILY;
	private static String category; // null = alle

	private static final Map<String, ItemStack> STACKS = new HashMap<>();
	private static final Map<String, String> NAMES = new HashMap<>();

	private final List<Button> intervalButtons = new ArrayList<>();
	private Button categoryButton;
	private Button updateButton;
	private List<Listing> filtered = List.of();
	private List<String> categories = List.of();
	private int seenVersion = -1;
	private double scroll;

	private int listX, listY, listW, listH;
	private int detailX, detailY, detailW, detailH;
	private int chartX, chartY, chartW, chartH;

	public MarketScreen() {
		super(Component.translatable("loothub.screen.title"));
	}

	// ------------------------------------------------------------------ Aufbau

	@Override
	protected void init() {
		MarketPrices.refreshIfStale();

		Tabs.create(Tabs.Tab.MARKET, width, 4).forEach(this::addRenderableWidget);
		updateButton = addRenderableWidget(Tabs.updateButton(4));

		int top = 24;
		listX = PAD;
		listW = Mth.clamp((width - 3 * PAD) * 2 / 5, 130, 220);

		EditBox searchBox = new EditBox(font, listX, top, listW, 16, Component.translatable("loothub.screen.search"));
		searchBox.setHint(Component.translatable("loothub.screen.search_hint").withStyle(ChatFormatting.DARK_GRAY));
		searchBox.setValue(search);
		searchBox.setResponder(text -> {
			search = text;
			scroll = 0;
			updateFilter();
		});
		addRenderableWidget(searchBox);

		categoryButton = addRenderableWidget(Button.builder(categoryLabel(), b -> cycleCategory())
				.bounds(listX, top + 20, listW, 16)
				.build());

		listY = top + 40;
		listH = height - listY - PAD;

		detailX = listX + listW + PAD;
		detailY = top;
		detailW = width - detailX - PAD;
		detailH = height - detailY - PAD;

		intervalButtons.clear();
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

		seenVersion = -1; // erzwingt Neuaufbau der Liste
		refreshData();
		setFocused(searchBox);
	}

	@Override
	public void tick() {
		super.tick();
		Tabs.refreshUpdateButton(updateButton);
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
		if (selected() == null && !out.isEmpty()) {
			selectedMaterial = out.get(0).material();
		}
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
		// Hintergründe zuerst, damit die Knöpfe darüber liegen
		panel(g, listX, listY, listW, listH);
		panel(g, detailX, detailY, detailW, detailH);

		super.render(g, mouseX, mouseY, delta);

		renderStatus(g);
		renderList(g, mouseX, mouseY);
		renderDetail(g, mouseX, mouseY);
	}

	private void renderStatus(GuiGraphics g) {
		Component status;
		int color = FAINT;
		if (MarketPrices.hasData()) {
			status = Component.translatable("loothub.screen.status", Fmt.time(MarketPrices.lastUpdate()));
		} else if (MarketPrices.lastAttemptFailed()) {
			status = Component.translatable("loothub.tooltip.error");
			color = RED;
		} else {
			status = Component.translatable("loothub.tooltip.loading");
		}
		g.drawString(font, status, width - PAD - font.width(status), 8, color, false);
	}

	private void renderList(GuiGraphics g, int mx, int my) {
		if (filtered.isEmpty()) {
			Component msg = MarketPrices.hasData()
					? Component.translatable("loothub.screen.no_results")
					: Component.translatable("loothub.tooltip.loading");
			centered(g, msg, listX + listW / 2, listY + 10, FAINT);
			return;
		}

		int innerTop = listY + 1;
		int innerBottom = listY + listH - 1;
		boolean scrollbar = contentHeight() > listH - 2;
		int rowW = listW - 2 - (scrollbar ? 4 : 0);

		g.enableScissor(listX + 1, innerTop, listX + listW - 1, innerBottom);
		int first = Math.max(0, (int) (scroll / ROW_H));
		for (int i = first; i < filtered.size(); i++) {
			int ry = innerTop + i * ROW_H - (int) scroll;
			if (ry > innerBottom) {
				break;
			}
			Listing l = filtered.get(i);
			boolean hovered = mx >= listX && mx < listX + rowW && my >= Math.max(ry, innerTop)
					&& my < Math.min(ry + ROW_H, innerBottom);
			if (l.material().equals(selectedMaterial)) {
				g.fill(listX + 1, ry, listX + 1 + rowW, ry + ROW_H, ROW_SELECTED);
			} else if (hovered) {
				g.fill(listX + 1, ry, listX + 1 + rowW, ry + ROW_H, ROW_HOVER);
			}
			g.renderItem(stack(l), listX + 3, ry + 1);

			Side sell = l.entry().sell();
			String price = sell.available() ? Fmt.compact(sell.price()) + " $" : "–";
			int priceW = font.width(price);
			g.drawString(font, price, listX + 1 + rowW - 3 - priceW, ry + 5, sell.available() ? GOLD : FAINT, false);
			g.drawString(font, ellipsize(name(l), rowW - 26 - priceW - 6), listX + 22, ry + 5, TEXT, false);
		}
		g.disableScissor();

		if (scrollbar) {
			int trackH = listH - 2;
			int thumbH = Math.max(12, trackH * trackH / contentHeight());
			int thumbY = innerTop + (int) ((trackH - thumbH) * (scroll / maxScroll()));
			g.fill(listX + listW - 4, innerTop, listX + listW - 1, innerBottom, 0x33FFFFFF);
			g.fill(listX + listW - 4, thumbY, listX + listW - 1, thumbY + thumbH, 0xAAFFFFFF);
		}
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
		px = drawPrice(g, px, py, "loothub.tooltip.buy", l.entry().buy(), GREEN);
		drawPrice(g, px + 12, py, "loothub.tooltip.sell", l.entry().sell(), GOLD);

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
		if (event.button() == 0 && mx >= listX && mx < listX + listW && my > listY && my < listY + listH - 1) {
			int index = (int) ((my - listY - 1 + scroll) / ROW_H);
			if (index >= 0 && index < filtered.size()) {
				selectedMaterial = filtered.get(index).material();
				setFocused(null);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
			scroll -= vertical * ROW_H * 2;
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
		return filtered.size() * ROW_H;
	}

	private double maxScroll() {
		return Math.max(1, contentHeight() - (listH - 2));
	}

	private void clampScroll() {
		scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight() - (listH - 2)));
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
