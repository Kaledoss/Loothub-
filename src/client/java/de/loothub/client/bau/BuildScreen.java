package de.loothub.client.bau;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.loothub.client.Fmt;
import de.loothub.client.MarketPrices;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Reiter „Bau“: platzierte Schematics (Litematica) mit Baukosten nach Marktpreisen, was noch fehlt (abzüglich Inventar
 * und Lager-Fässern), Fortschritt und „Mit Freund teilen“. Die Litematica-Fenster selbst öffnen die Knöpfe direkt.
 */
public final class BuildScreen extends de.loothub.client.LoothubScreen {
	private static final int PANEL = 0xE01C1315;
	private static final int BORDER = 0xFF3A1F23;
	private static final int GOLD = 0xFFFFD66B;
	private static final int GREEN = 0xFF7BD88F;
	private static final int ROW_H = 18;
	private static final int PLAN_H = 26;

	/** Eine Zeile der Materialtabelle. */
	private record Row(String id, ItemStack icon, String name, long total, long need, long inv, long stock, long buy,
			double price, double recipe) {
	}

	/** Eine Zeile der Tabelle: Material oder (aufgeklappt) eine Zutat darunter. */
	private record Line(Row row, Recipes.Part part) {
	}

	/** Aufgeklappte Materialien (Item-ID) – zeigen, woraus sie bestehen. */
	private static final java.util.Set<String> EXPANDED = new java.util.HashSet<>();
	private List<Line> lines = List.of();
	private double cheapest;

	private static String selectedId;
	private List<Plan> plans = List.of();
	private Plan selected;
	private List<Row> rows = List.of();
	private double totalCost, buyCost;
	private int noPrice;
	private BuildStorage.Stock stock;
	private int scroll;
	private int refreshIn;
	private int listX, listY, listW, listH, detailX, detailW, tableY, tableH;
	private EditBox shareBox;
	private int tableColW;

	public BuildScreen() {
		super(Page.BUILD);
	}

	@Override
	protected void initPage() {
		MarketPrices.refreshIfStale();
		if (!Build.present()) {
			return;
		}
		int y = ct;
		int x = cl;
		x = button(x, y, "loothub.build.btn.menu", b -> Build.openMainMenu(this));
		x = button(x, y, "loothub.build.btn.load", b -> Build.openLoad(this));
		button(x, y, "loothub.build.btn.placements", b -> Build.openPlacements(this));
		WidgetConfig c = WidgetConfig.get();
		// rechts: Anzeigen ein/aus
		int rx = cr;
		Button hudMove = Button.builder(Component.translatable("loothub.build.btn.hud_move"),
				b -> minecraft.setScreen(de.loothub.client.HudLayoutScreen.select(this, "loothub.hud.build")))
				.bounds(rx - 90, y, 90, 16).build();
		addRenderableWidget(hudMove);
		rx -= 94;
		Button warn = Button.builder(toggle("loothub.build.btn.warn", c.buildWarn), b -> {
			c.buildWarn = !c.buildWarn;
			c.save();
			b.setMessage(toggle("loothub.build.btn.warn", c.buildWarn));
		}).bounds(rx - 120, y, 120, 16).build();
		warn.setTooltip(Tooltip.create(Component.translatable("loothub.build.btn.warn.tooltip")));
		addRenderableWidget(warn);
		rx -= 124;
		Button hud = Button.builder(toggle("loothub.build.btn.hud", c.buildHud), b -> {
			c.buildHud = !c.buildHud;
			c.save();
			b.setMessage(toggle("loothub.build.btn.hud", c.buildHud));
		}).bounds(rx - 120, y, 120, 16).build();
		hud.setTooltip(Tooltip.create(Component.translatable("loothub.build.btn.hud.tooltip", Build.AREA)));
		addRenderableWidget(hud);

		listX = cl;
		listY = ct + 22;
		// Liste so breit, dass Namen und Dateinamen ganz zu sehen sind (höchstens knapp die Hälfte)
		int need = 130;
		for (Plan p : Build.plans()) {
			need = Math.max(need, Math.max(font.width(p.name) + 16, font.width(p.file) + 60 + font.width("-00000, 000, -00000 · ")));
		}
		listW = Math.max(130, Math.min(need, Math.max(200, cw * 45 / 100)));
		listH = cb - listY;
		detailX = listX + listW + 8;
		detailW = cr - detailX;

		plans = Build.plans();
		selected = null;
		for (Plan p : plans) {
			if (p.id.equals(selectedId)) {
				selected = p;
			}
		}
		if (selected == null && !plans.isEmpty()) {
			selected = plans.get(0);
			selectedId = selected.id;
		}
		if (selected != null) {
			int dy = listY + 16;
			int dx = detailX + 4;
			dx = button(dx, dy, "loothub.build.btn.placement", b -> Build.openPlacement(selected, this));
			dx = button(dx, dy, "loothub.build.btn.materials", b -> Build.openMaterialList(selected, this));
			dx = button(dx, dy, "loothub.build.btn.preview", b -> minecraft.setScreen(new Preview(this, selected)));
			Button recount = Button.builder(Component.translatable("loothub.build.btn.recount"), b -> {
				Build.recount(selected);
				refreshIn = 20;
			}).bounds(dx, dy, font.width(Component.translatable("loothub.build.btn.recount")) + 14, 16).build();
			recount.setTooltip(Tooltip.create(Component.translatable("loothub.build.btn.recount.tooltip")));
			addRenderableWidget(recount);
			dx += recount.getWidth() + 4;
			// Einkaufsliste im HUD für diese Schematic ein/aus
			Plan forList = selected;
			Button shopping = Button.builder(toggle("loothub.build.btn.shopping", ShoppingList.on(forList)), b -> {
				ShoppingList.toggle(forList);
				b.setMessage(toggle("loothub.build.btn.shopping", ShoppingList.on(forList)));
			}).bounds(dx, dy, font.width(toggle("loothub.build.btn.shopping", false)) + 14, 16).build();
			shopping.setTooltip(Tooltip.create(Component.translatable("loothub.build.btn.shopping.tooltip")));
			addRenderableWidget(shopping);
			// Teilen: Name eintippen, Knopf
			int sy = dy + 20;
			shareBox = new EditBox(font, detailX + 4, sy, 110, 16, Component.translatable("loothub.build.share.name"));
			shareBox.setMaxLength(16);
			shareBox.setHint(Component.translatable("loothub.build.share.name").withStyle(ChatFormatting.DARK_GRAY));
			addRenderableWidget(shareBox);
			Button share = Button.builder(Component.translatable("loothub.build.btn.share"), b -> {
				Share.share(selected, shareBox.getValue());
				shareBox.setValue("");
			}).bounds(detailX + 118, sy, font.width(Component.translatable("loothub.build.btn.share")) + 14, 16).build();
			share.setTooltip(Tooltip.create(Component.translatable("loothub.build.btn.share.tooltip")));
			addRenderableWidget(share);
			// Ein frisch ausgewählter Plan: Litematica einmal zählen lassen, was schon steht
			if (!selected.countRequested) {
				Build.recount(selected);
			}
		}
		refresh();
	}

	private int button(int x, int y, String key, Button.OnPress press) {
		Component label = Component.translatable(key);
		int w = font.width(label) + 14;
		addRenderableWidget(Button.builder(label, press).bounds(x, y, w, 16).build());
		return x + w + 4;
	}

	private static Component toggle(String key, boolean on) {
		return Component.translatable(key, Component.translatable(on ? "loothub.settings.on" : "loothub.settings.off"));
	}

	@Override
	public void tick() {
		super.tick();
		if (--refreshIn <= 0) {
			refreshIn = 20;
			List<Plan> now = Build.plans();
			if (now.size() != plans.size()) {
				clearWidgets();
				init();
				return;
			}
			plans = now;
			refresh();
		}
	}

	/** Materialtabelle und Kosten für die ausgewählte Schematic neu rechnen. */
	private void refresh() {
		if (selected == null) {
			rows = List.of();
			lines = List.of();
			return;
		}
		Recipes.refresh();
		Plan fresh = Build.plan(selected.id);
		if (fresh != null) {
			selected = fresh;
		}
		Map<String, Map<String, Long>> needs = new HashMap<>();
		for (Plan p : plans) {
			needs.put(p.id, need(p));
		}
		Map<String, Long> stockHere = BuildStorage.allocate(plans, needs).getOrDefault(selected.id, Map.of());
		stock = BuildStorage.stockFor(selected);
		Map<String, Long> inv = Build.inventory();
		Map<String, Long> totals = selected.totals == null ? Map.of() : selected.totals;
		Map<String, Long> need = needs.getOrDefault(selected.id, Map.of());
		List<Row> out = new ArrayList<>();
		totalCost = 0;
		buyCost = 0;
		cheapest = 0;
		noPrice = 0;
		java.util.Set<String> ids = new java.util.HashSet<>(totals.keySet());
		ids.addAll(need.keySet());
		for (String id : ids) {
			long total = totals.getOrDefault(id, 0L);
			long n = need.getOrDefault(id, 0L);
			long have = inv.getOrDefault(id, 0L);
			long st = stockHere.getOrDefault(id, 0L);
			long buy = Math.max(0, n - have - st);
			double price = price(id);
			double recipe = Recipes.recipeUnit(id);
			if (Double.isNaN(price)) {
				noPrice++;
			} else {
				totalCost += total * price;
				buyCost += buy * price;
			}
			// günstigster Weg: kaufen oder aus den Zutaten herstellen
			double best = Double.isNaN(price) ? recipe : Double.isNaN(recipe) ? price : Math.min(price, recipe);
			if (!Double.isNaN(best)) {
				cheapest += buy * best;
			}
			Item item = item(id);
			ItemStack icon = item == null ? new ItemStack(Items.BARRIER) : new ItemStack(item);
			out.add(new Row(id, icon, icon.getHoverName().getString(), total, n, have, st, buy, price, recipe));
		}
		// zuerst, was am meisten kostet; dann, wovon am meisten fehlt
		out.sort(comparator());
		rows = out;
		List<Line> ls = new ArrayList<>();
		for (Row row : out) {
			ls.add(new Line(row, null));
			if (EXPANDED.contains(row.id())) {
				// für die noch zu besorgende Menge (oder, wenn nichts fehlt, für ein Stück)
				for (Recipes.Part part : Recipes.breakdown(row.id(), Math.max(1, row.buy()))) {
					ls.add(new Line(row, part));
				}
			}
		}
		lines = ls;
		clampScroll();
	}

	/**
	 * Sortierung nach der angeklickten Spalte: 0 = Material, 1 Gesamt, 2 Fehlt, 3 Inventar, 4 Lager, 5 Besorgen,
	 * 6 Preis, 7 Rezept; -1 = Standard (zuerst, was am meisten kostet). Ohne Preis steht immer unten.
	 */
	private static int sortColumn = -1;
	private static boolean sortDesc = true;

	private static Comparator<Row> comparator() {
		Comparator<Row> byName = Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER);
		Comparator<Row> c = switch (sortColumn) {
			case 0 -> byName;
			case 1 -> Comparator.comparingLong(Row::total);
			case 2 -> Comparator.comparingLong(Row::need);
			case 3 -> Comparator.comparingLong(Row::inv);
			case 4 -> Comparator.comparingLong(Row::stock);
			case 5 -> Comparator.comparingLong(Row::buy);
			case 6 -> Comparator.comparingDouble((Row r) -> Double.isNaN(r.price()) ? -1 : r.buy() * r.price());
			case 7 -> Comparator.comparingDouble((Row r) -> Double.isNaN(r.recipe()) ? -1 : Math.max(1, r.buy()) * r.recipe());
			default -> Comparator.comparingDouble((Row r) -> Double.isNaN(r.price()) ? -1 : r.buy() * r.price())
					.thenComparing(Comparator.comparingLong(Row::buy));
		};
		if (sortDesc) {
			c = c.reversed();
		}
		return c.thenComparing(byName);
	}

	/** Was noch gebraucht wird: laut Litematica fehlend, sonst (noch nicht gezählt) alles. */
	private static Map<String, Long> need(Plan p) {
		Map<String, Long> m = p.missing;
		if (m != null) {
			return m;
		}
		return p.totals == null ? Map.of() : p.totals;
	}

	/** Kaufpreis am Markt je Stück; NaN = kein Preis. */
	private static double price(String id) {
		MarketPrices.Entry e = MarketPrices.get(id);
		if (e == null) {
			return Double.NaN;
		}
		if (e.buy().available()) {
			return e.buy().price();
		}
		return e.sell().available() ? e.sell().price() : Double.NaN;
	}

	private static Item item(String id) {
		Identifier identifier = Identifier.tryParse(id);
		Item item = identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		return item == null || item == Items.AIR ? null : item;
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		if (Build.present()) {
			// Flächen unter den Knöpfen, Text danach darüber
			panel(g, listX, listY, listW, listH);
			if (selected != null) {
				panel(g, detailX, listY, detailW, listH);
			}
		}
		super.render(g, mx, my, delta);
		if (!Build.present()) {
			Component msg = Component.translatable("loothub.build.no_litematica");
			g.drawString(font, msg, cl + (cw - font.width(msg)) / 2, ct + 30, MUTED, false);
			return;
		}
		renderList(g, mx, my);
		if (selected == null) {
			Component msg = Component.translatable("loothub.build.none");
			g.drawString(font, msg, detailX + 8, listY + 10, MUTED, false);
			return;
		}
		renderDetail(g, mx, my);
	}

	private void renderList(GuiGraphics g, int mx, int my) {
		int y = listY + 4;
		g.drawString(font, Component.translatable("loothub.build.list"), listX + 6, y, GOLD, false);
		y += 12;
		for (Plan p : plans) {
			if (y + PLAN_H > listY + listH - 4) {
				break;
			}
			boolean sel = selected != null && p.id.equals(selected.id);
			boolean hover = mx >= listX && mx < listX + listW && my >= y && my < y + PLAN_H;
			if (sel) {
				g.fill(listX + 1, y, listX + listW - 1, y + PLAN_H, 0x40E23B3B);
				g.fill(listX + 1, y, listX + 3, y + PLAN_H, RED);
			} else if (hover) {
				g.fill(listX + 1, y, listX + listW - 1, y + PLAN_H, 0x18FFFFFF);
			}
			g.drawString(font, fit(p.name, listW - 16), listX + 8, y + 3, p.enabled ? TEXT : FAINT, false);
			// gleiche Schematic mehrfach gesetzt: Lage dazuschreiben, damit man sie unterscheiden kann
			String file = p.file;
			if (Build.plans().stream().filter(o -> o.name.equals(p.name)).count() > 1) {
				file = p.minX + ", " + p.minY + ", " + p.minZ + " · " + file;
			}
			String sub = fit(file, listW - 60);
			g.drawString(font, sub, listX + 8, y + 14, FAINT, false);
			long total = p.totalBlocks();
			if (total > 0 && p.counted > 0) {
				String pct = Math.min(100, Math.round(100.0 * p.correct / total)) + " %";
				g.drawString(font, pct, listX + listW - 6 - font.width(pct), y + 14, GREEN, false);
			}
			y += PLAN_H + 2;
		}
		// mit dir geteilt
		List<Share.Incoming> in = Share.inbox();
		if (!in.isEmpty()) {
			y += 6;
			g.drawString(font, Component.translatable("loothub.build.inbox"), listX + 6, y, GOLD, false);
			y += 12;
			for (Share.Incoming s : in) {
				if (y + 22 > listY + listH - 4) {
					break;
				}
				g.drawString(font, fit(s.name(), listW - 50), listX + 8, y, TEXT, false);
				g.drawString(font, fit(Component.translatable("loothub.build.inbox.from", s.from()).getString(), listW - 50),
						listX + 8, y + 10, FAINT, false);
				int bx = listX + listW - 40;
				if (!s.accepted()) {
					miniButton(g, bx, y + 2, "✔", GREEN, mx, my);
				}
				miniButton(g, bx + 20, y + 2, "✖", RED, mx, my);
				y += 24;
			}
		}
	}

	private void miniButton(GuiGraphics g, int x, int y, String label, int color, int mx, int my) {
		boolean hover = mx >= x && mx < x + 16 && my >= y && my < y + 16;
		g.fill(x, y, x + 16, y + 16, hover ? 0xFF3A2A2D : 0xFF241A1C);
		g.drawString(font, label, x + (16 - font.width(label)) / 2, y + 4, color, false);
	}

	private void renderDetail(GuiGraphics g, int mx, int my) {
		int x = detailX;
		g.drawString(font, fit(selected.name, detailW - 12), x + 6, listY + 4, GOLD, false);
		// geteilt mit … (✖ = nicht mehr teilen)
		int sy = listY + 36 + 4;
		int nx = x + 118 + font.width(Component.translatable("loothub.build.btn.share")) + 22;
		List<String> with = Share.sharedWith(selected);
		if (!with.isEmpty()) {
			String label = Component.translatable("loothub.build.shared_with").getString() + " ";
			g.drawString(font, label, nx, sy, FAINT, false);
			nx += font.width(label);
			for (String n : with) {
				g.drawString(font, n, nx, sy, TEXT, false);
				nx += font.width(n) + 2;
				boolean hover = mx >= nx && mx < nx + 8 && my >= sy - 1 && my < sy + 9;
				g.drawString(font, "✖", nx, sy, hover ? 0xFFFFFFFF : RED, false);
				nx += 12;
			}
		}
		String status = Share.status();
		if (!status.isEmpty()) {
			g.drawString(font, fit(status, detailW - 12), x + 6, listY + 58, MUTED, false);
		}

		// Kosten und Lager
		int y = listY + 72;
		String costs = Component.translatable("loothub.build.cost.total", Fmt.money(totalCost)).getString();
		g.drawString(font, costs, x + 6, y, TEXT, false);
		String buy = Component.translatable("loothub.build.cost.buy", Fmt.money(buyCost)).getString();
		g.drawString(font, buy, x + 6 + font.width(costs) + 14, y, GOLD, false);
		if (cheapest > 0 && cheapest < buyCost - 0.005) {
			String ch = Component.translatable("loothub.build.cost.cheapest", Fmt.money(cheapest)).getString();
			g.drawString(font, ch, x + 6 + font.width(costs) + 14 + font.width(buy) + 14, y, GREEN, false);
		}
		y += 11;
		StringBuilder info = new StringBuilder();
		if (selected.totals == null) {
			info.append(Component.translatable("loothub.build.counting").getString()).append(" · ");
		} else if (selected.missing == null) {
			info.append(Component.translatable("loothub.build.not_counted").getString()).append(" · ");
		}
		if (noPrice > 0) {
			info.append(Component.translatable("loothub.build.no_price_count", noPrice).getString()).append(" · ");
		}
		int barrels = stock == null ? 0 : stock.own().size() + stock.general().size();
		if (barrels == 0) {
			info.append(Component.translatable("loothub.build.storage.none", BuildStorage.RADIUS, selected.name).getString());
		} else {
			long oldest = BuildStorage.oldest(stock);
			info.append(Component.translatable("loothub.build.storage", barrels,
					Fmt.duration(System.currentTimeMillis() - oldest)).getString());
		}
		g.drawString(font, fit(info.toString(), detailW - 12), x + 6, y, FAINT, false);
		y += 14;

		// Tabelle
		String[] heads = {"loothub.build.col.total", "loothub.build.col.need", "loothub.build.col.inv",
				"loothub.build.col.stock", "loothub.build.col.buy", "loothub.build.col.price", "loothub.build.col.recipe"};
		int colW = Math.max(48, Math.min(70, (detailW - 160) / heads.length));
		headerY = y;
		headColW = colW;
		String mat = Component.translatable("loothub.build.col.material").getString() + arrow(0);
		g.drawString(font, mat, x + 26, y, sortColumn == 0 ? GOLD : FAINT, false);
		for (int i = 0; i < heads.length; i++) {
			String h = Component.translatable(heads[i]).getString() + arrow(i + 1);
			g.drawString(font, h, colRight(i, heads.length, colW) - font.width(h), y, sortColumn == i + 1 ? GOLD : FAINT, false);
		}
		g.fill(x + 1, y + 10, x + detailW - 1, y + 11, BORDER);
		tableY = y + 12;
		tableH = listY + listH - 2 - tableY;
		g.enableScissor(x + 1, tableY, x + detailW - 1, tableY + tableH);
		int ry = tableY - scroll;
		int nameMax = colRight(0, heads.length, colW) - colW - (x + 26);
		for (Line line : lines) {
			Row r = line.row();
			if (line.part() != null) {
				// Zutat (aufgeklappt): eingerückt, Menge in „Besorgen“, Kosten in „Preis“
				if (ry + ROW_H >= tableY && ry < tableY + tableH) {
					Recipes.Part part = line.part();
					g.fill(x + 14, ry, x + 15, ry + ROW_H, BORDER);
					ItemStack icon = new ItemStack(part.item());
					g.renderItem(icon, x + 20, ry + 1);
					g.drawString(font, fit(icon.getHoverName().getString(), nameMax - 16), x + 40, ry + 5, MUTED, false);
					String amount = Fmt.whole((long) Math.ceil(part.amount() - 1e-6));
					g.drawString(font, amount, colRight(4, heads.length, colW) - font.width(amount), ry + 5, MUTED, false);
					String c = Double.isNaN(part.cost()) ? Component.translatable("loothub.build.no_price").getString()
							: Fmt.compact(part.cost()) + " $";
					g.drawString(font, c, colRight(5, heads.length, colW) - font.width(c), ry + 5,
							Double.isNaN(part.cost()) ? FAINT : MUTED, false);
				}
				ry += ROW_H;
				continue;
			}
			if (ry + ROW_H >= tableY && ry < tableY + tableH) {
				boolean craftable = Recipes.craftable(r.id());
				if (craftable) {
					g.drawString(font, EXPANDED.contains(r.id()) ? "▾" : "▸", x + 3, ry + 5, FAINT, false);
				}
				g.renderItem(r.icon(), x + 8, ry + 1);
				g.drawString(font, fit(r.name(), nameMax), x + 26, ry + 5, r.buy() > 0 ? TEXT : GREEN, false);
				long[] vals = {r.total(), r.need(), r.inv(), r.stock(), r.buy()};
				for (int i = 0; i < vals.length; i++) {
					String v = Fmt.whole(vals[i]);
					int color = i == 4 ? (r.buy() > 0 ? GOLD : GREEN) : MUTED;
					g.drawString(font, v, colRight(i, heads.length, colW) - font.width(v), ry + 5, color, false);
				}
				String p = Double.isNaN(r.price()) ? Component.translatable("loothub.build.no_price").getString()
						: Fmt.compact(r.buy() * r.price()) + " $";
				g.drawString(font, p, colRight(5, heads.length, colW) - font.width(p), ry + 5,
						Double.isNaN(r.price()) ? FAINT : TEXT, false);
				// nach Rezept: grün, wenn Herstellen billiger ist als Kaufen
				String rc = Double.isNaN(r.recipe()) ? "–" : Fmt.compact(Math.max(1, r.buy()) * r.recipe()) + " $";
				boolean cheaper = !Double.isNaN(r.recipe()) && (Double.isNaN(r.price()) || r.recipe() < r.price());
				g.drawString(font, rc, colRight(6, heads.length, colW) - font.width(rc), ry + 5,
						Double.isNaN(r.recipe()) ? FAINT : cheaper ? GREEN : MUTED, false);
			}
			ry += ROW_H;
		}
		g.disableScissor();
		if (rows.isEmpty()) {
			Component msg = Component.translatable("loothub.build.counting");
			g.drawString(font, msg, x + (detailW - font.width(msg)) / 2, tableY + 10, FAINT, false);
		}
		// Hinweise zu den Preisspalten
		if (my >= y && my < y + 10) {
			if (mx >= colRight(5, heads.length, colW) - colW && mx < colRight(5, heads.length, colW)) {
				g.setTooltipForNextFrame(font, Component.translatable("loothub.build.col.price.tooltip"), mx, my);
			} else if (mx >= colRight(6, heads.length, colW) - colW && mx < colRight(6, heads.length, colW)) {
				g.setTooltipForNextFrame(font, Component.translatable("loothub.build.col.recipe.tooltip", Recipes.known()), mx, my);
			}
		}
		tableColW = colW;
	}

	private static String arrow(int col) {
		return sortColumn == col ? (sortDesc ? " ▼" : " ▲") : "";
	}

	private int headerY = -100, headColW = 50;

	private int colRight(int i, int count, int colW) {
		return detailX + detailW - 8 - (count - 1 - i) * colW;
	}

	private void clampScroll() {
		int max = Math.max(0, lines.size() * ROW_H - Math.max(0, tableH));
		scroll = Math.max(0, Math.min(scroll, max));
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (mx >= detailX && my >= tableY) {
			scroll -= (int) (vertical * ROW_H * 2);
			clampScroll();
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0 || !Build.present()) {
			return false;
		}
		double mx = event.x(), my = event.y();
		// Schematic auswählen
		int y = listY + 16;
		for (Plan p : plans) {
			if (y + PLAN_H > listY + listH - 4) {
				break;
			}
			if (mx >= listX && mx < listX + listW && my >= y && my < y + PLAN_H) {
				selectedId = p.id;
				scroll = 0;
				clearWidgets();
				init();
				return true;
			}
			y += PLAN_H + 2;
		}
		// Annehmen / Ablehnen
		List<Share.Incoming> in = Share.inbox();
		if (!in.isEmpty()) {
			y += 18;
			for (Share.Incoming s : in) {
				int bx = listX + listW - 40;
				if (my >= y + 2 && my < y + 18) {
					if (!s.accepted() && mx >= bx && mx < bx + 16) {
						Share.accept(s);
						return true;
					}
					if (mx >= bx + 20 && mx < bx + 36) {
						Share.decline(s);
						return true;
					}
				}
				y += 24;
			}
		}
		// Spaltenkopf: nach dieser Spalte sortieren (nochmal klicken = andersherum)
		if (selected != null && my >= headerY && my < headerY + 10 && mx >= detailX && mx < detailX + detailW) {
			int col = 0;
			for (int i = 0; i < 7; i++) {
				if (mx >= colRight(i, 7, headColW) - headColW && mx < colRight(i, 7, headColW) + 2) {
					col = i + 1;
				}
			}
			if (sortColumn == col) {
				sortDesc = !sortDesc;
			} else {
				sortColumn = col;
				sortDesc = col != 0; // Namen A–Z, Zahlen groß zuerst
			}
			scroll = 0;
			refresh();
			return true;
		}
		// Material auf-/zuklappen (nur, wenn es ein Rezept dafür gibt)
		if (selected != null && mx >= detailX && mx < detailX + detailW && my >= tableY && my < tableY + tableH) {
			int idx = (int) ((my - tableY + scroll) / ROW_H);
			if (idx >= 0 && idx < lines.size() && lines.get(idx).part() == null) {
				String id = lines.get(idx).row().id();
				if (Recipes.craftable(id)) {
					if (!EXPANDED.remove(id)) {
						EXPANDED.add(id);
					}
					refresh();
					return true;
				}
			}
		}
		// Teilen beenden (✖ hinter dem Namen)
		if (selected != null) {
			int sy = listY + 40;
			int nx = detailX + 118 + font.width(Component.translatable("loothub.build.btn.share")) + 22;
			List<String> with = Share.sharedWith(selected);
			if (!with.isEmpty() && my >= sy - 1 && my < sy + 9) {
				nx += font.width(Component.translatable("loothub.build.shared_with").getString() + " ");
				for (String n : with) {
					nx += font.width(n) + 2;
					if (mx >= nx && mx < nx + 8) {
						Share.unshare(selected, n);
						return true;
					}
					nx += 12;
				}
			}
		}
		return false;
	}

	private String fit(String s, int max) {
		if (s == null) {
			return "";
		}
		if (font.width(s) <= max) {
			return s;
		}
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end)) + font.width("…") > max) {
			end--;
		}
		return s.substring(0, end) + "…";
	}

	private static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, de.loothub.client.Theme.bg(PANEL));
		g.fill(x, y, x + w, y + 1, de.loothub.client.Theme.bg(BORDER));
		g.fill(x, y + h - 1, x + w, y + h, de.loothub.client.Theme.bg(BORDER));
		g.fill(x, y, x + 1, y + h, de.loothub.client.Theme.bg(BORDER));
		g.fill(x + w - 1, y, x + w, y + h, de.loothub.client.Theme.bg(BORDER));
	}
}
