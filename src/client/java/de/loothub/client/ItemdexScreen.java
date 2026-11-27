package de.loothub.client;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.itemdex.Itemdex;
import de.loothub.client.itemdex.Itemdex.Category;
import de.loothub.client.itemdex.Itemdex.Entry;

/**
 * Seite „Itemdex“: nummerierte Liste (A–Z) aller Server-eigenen Items, die bisher gesehen wurden.
 * Suche (Name, Beschreibung, Nummer), Filter nach Art, Rechtsklick blendet einen Eintrag aus.
 */
public class ItemdexScreen extends LoothubScreen {
	private static final int ROW_H = 20;

	private static String search = "";
	private static Category category; // null = alle
	private static boolean favOnly; // nur Favoriten
	private static boolean showHidden;

	private Button categoryButton;
	private boolean dropdownOpen;
	private static final int DD_ROW = 14;
	private Button hiddenButton;
	/** Sortierung: 0 A–Z, 1 Sofortkauf, 2 Schnitt 7 Tage, 3 neu entdeckt, 4 zuletzt gesehen. */
	private static int sortMode;
	private static final String[] SORTS = {"az", "offer", "avg7", "new", "seen"};
	private List<Entry> rows = List.of();
	private final Map<Entry, Integer> numbers = new IdentityHashMap<>();
	private int seenVersion = -1;
	private int scroll;
	private int listTop;

	public ItemdexScreen() {
		super(Page.ITEMDEX);
	}

	@Override
	protected void initPage() {
		Itemdex.prune();
		int hiddenW = 110;
		int catW = 120, sortW = 130;
		int boxW = Math.max(80, Math.min(260, cw - catW - hiddenW - sortW - 18));
		EditBox box = new EditBox(font, cl, ct, boxW, 16, Component.translatable("loothub.screen.search"));
		box.setHint(Component.translatable("loothub.itemdex.search").withStyle(ChatFormatting.DARK_GRAY));
		box.setValue(search);
		box.setResponder(t -> {
			search = t;
			scroll = 0;
			refresh();
		});
		addRenderableWidget(box);
		addRenderableWidget(Button.builder(sortLabel(), b -> {
			sortMode = (sortMode + 1) % SORTS.length;
			b.setMessage(sortLabel());
			scroll = 0;
			refresh();
		}).bounds(cr - catW - hiddenW - sortW - 12, ct, sortW, 16)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("loothub.itemdex.sort.tip"))).build());
		categoryButton = addRenderableWidget(Button.builder(categoryLabel(), b -> dropdownOpen = !dropdownOpen)
				.bounds(cr - catW - hiddenW - 6, ct, catW, 16).build());
		dropdownOpen = false;
		hiddenButton = addRenderableWidget(Button.builder(hiddenLabel(), b -> {
			showHidden = !showHidden;
			b.setMessage(hiddenLabel());
			scroll = 0;
			refresh();
		}).bounds(cr - hiddenW, ct, hiddenW, 16).build());
		listTop = ct + 22 + 12; // Platz für die Spaltenköpfe
		refresh();
	}

	private static Component categoryLabel() {
		return Component.translatable("loothub.itemdex.category",
				favOnly ? Component.translatable("loothub.itemdex.cat.fav")
						: category == null ? Component.translatable("loothub.itemdex.cat.all") : Component.translatable(category.key))
				.append(" ▾");
	}

	private static Component sortLabel() {
		return Component.translatable("loothub.itemdex.sort", Component.translatable("loothub.itemdex.sort." + SORTS[sortMode]));
	}

	/** Wert für die Preis-Sortierung (fehlender Preis = ganz unten). */
	private static double priceKey(Entry e) {
		String k = AuctionNames.key(Itemdex.clean(e.name), e.itemId, AuctionNames.starsOf(e.stack()));
		if (sortMode == 1) {
			AuctionNames.Now n = AuctionNames.now(k);
			return n == null || n.offers() <= 0 ? -1 : n.minPerUnit();
		}
		AuctionNames.Total t = AuctionNames.window(k, 7);
		return t == null ? -1 : t.avg();
	}

	private static Component hiddenLabel() {
		return Component.translatable(showHidden ? "loothub.itemdex.hidden.on" : "loothub.itemdex.hidden.off");
	}

	private void refresh() {
		seenVersion = Itemdex.version();
		String q = search.strip().toLowerCase(Locale.ROOT).replaceFirst("^#", "");
		// A–Z sortiert; Nummern nur für eingeblendete Items, lückenlos und unabhängig von Suche/Filter
		List<Entry> all = new ArrayList<>(Itemdex.entries());
		Collator collator = Collator.getInstance(Locale.GERMANY);
		collator.setStrength(Collator.PRIMARY);
		all.sort(Comparator.comparing((Entry e) -> e.name, collator).thenComparing(e -> e.itemId));
		numbers.clear(); // Nummern nach A–Z, Favoriten stehen in der Liste danach oben
		int n = 0;
		for (Entry e : all) {
			if (!e.hidden) {
				numbers.put(e, ++n);
			}
		}
		List<Entry> out = new ArrayList<>();
		for (Entry e : all) {
			if (e.hidden && !showHidden) {
				continue; // ausgeblendete nur zeigen, wenn eingeschaltet
			}
			if (category != null && e.category != category) {
				continue;
			}
			if (favOnly && !e.favorite) {
				continue;
			}
			if (!q.isEmpty() && !e.text.contains(q) && !e.itemId.contains(q)
					&& !String.valueOf(numbers.getOrDefault(e, -1)).equals(q.replaceFirst("^0+(?=\\d)", ""))) {
				continue;
			}
			out.add(e);
		}
		switch (sortMode) {
			case 1, 2 -> {
				Map<Entry, Double> pk = new IdentityHashMap<>();
				for (Entry e : out) {
					pk.put(e, priceKey(e));
				}
				out.sort(Comparator.comparingDouble((Entry e) -> pk.get(e)).reversed()); // teuerste zuerst
			}
			case 3 -> out.sort(Comparator.comparingLong((Entry e) -> e.firstSeen).reversed());
			case 4 -> out.sort(Comparator.comparingLong((Entry e) -> e.lastSeen).reversed());
			default -> { } // A–Z
		}
		out.sort(Comparator.comparing((Entry e) -> !e.favorite)); // stabil: Favoriten zuerst, sonst gewählte Sortierung
		rows = out;
	}

	@Override
	protected Component headerRight() {
		return Component.translatable("loothub.itemdex.count", numbers.size());
	}

	@Override
	public void tick() {
		super.tick();
		if (Itemdex.version() != seenVersion) {
			refresh();
		}
	}

	private Entry rowAt(double mx, double my) {
		if (mx < cl || mx >= cr || my < listTop || my >= cb) {
			return null;
		}
		int i = (int) ((my - listTop) / ROW_H) + scroll;
		return i >= 0 && i < rows.size() ? rows.get(i) : null;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		if (rows.isEmpty()) {
			boolean filtered = !search.isBlank() || category != null || favOnly || showHidden;
			Component msg = Component.translatable(filtered ? "loothub.screen.no_results" : "loothub.itemdex.empty");
			g.drawString(font, msg, cl + (cw - font.width(msg)) / 2, listTop + 20, FAINT, false);
			drawDropdown(g, mouseX, mouseY);
			return;
		}
		int visible = Math.max(1, (cb - listTop) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
		Entry hovered = dropdownOpen ? null : rowAt(mouseX, mouseY);
		g.enableScissor(cl, listTop, cr, cb);
		for (int i = 0; i <= visible && i + scroll < rows.size(); i++) {
			Entry e = rows.get(i + scroll);
			int y = listTop + i * ROW_H;
			g.fill(cl, y, cr, y + ROW_H - 1, e == hovered ? 0x30E23B3B : (i % 2 == 0 ? 0x14FFFFFF : 0x08FFFFFF));
			Integer number = numbers.get(e);
			// Stern: Favorit an/aus
			boolean starHover = mouseX >= cl && mouseX < cl + STAR_W && mouseY >= y && mouseY < y + ROW_H - 1 && !dropdownOpen;
			g.drawString(font, "★", cl + 3, y + 6, e.favorite ? 0xFFFFC93B : (starHover ? 0xFFB08A3A : 0x40FFFFFF), false);
			g.drawString(font, number == null ? "  –" : String.format(Locale.ROOT, "#%04d", number), cl + STAR_W + 2, y + 6, MUTED, false);
			ItemStack stack = e.stack();
			// Manche Server-Items sind riesige Menü-Bilder – Symbol auf 16×16 begrenzen
			g.enableScissor(cl + STAR_W + 38, Math.max(y + 2, listTop), cl + STAR_W + 54, Math.min(y + 18, cb));
			g.renderItem(stack, cl + STAR_W + 38, y + 2);
			g.disableScissor();
			Component name = stack.isEmpty() ? Component.literal(Itemdex.clean(e.name)) : cleanOr(stack.getHoverName(), e.name);
			int nameX = cl + STAR_W + 58;
			String right = Fmt.date(e.firstSeen, false) + " · " + Itemdex.clean(e.where);
			String cat = Component.translatable(e.category.key).getString();
			int rightW = font.width(right);
			// Auktionshaus nach Namen: Verkäufe und Schnitt (7 Tage / gesamt)
			int ahX = ahColumnsX();
			String ahKey = AuctionNames.key(Itemdex.clean(e.name), e.itemId, AuctionNames.starsOf(stack));
			AuctionNames.Total w7 = AuctionNames.window(ahKey, 7);
			AuctionNames.Total all = AuctionNames.total(ahKey);
			AuctionNames.Now offer = AuctionNames.now(ahKey);
			String[] ah = {offer == null || offer.offers() <= 0 ? "–" : Fmt.compact(offer.minPerUnit()),
					w7 == null ? "–" : String.valueOf(w7.count()), w7 == null ? "–" : Fmt.compact(w7.avg()),
					all == null ? "–" : String.valueOf(all.count()), all == null ? "–" : Fmt.compact(all.avg())};
			for (int c = 0; c < ah.length; c++) {
				int colRight = ahX + (c + 1) * AH_COL;
				g.drawString(font, ah[c], colRight - 4 - font.width(ah[c]), y + 6, ah[c].equals("–") ? FAINT : (c == 0 ? 0xFF7CE08A : (c % 2 == 0 ? 0xFFD98CFF : TEXT)), false);
			}
			int catX = Math.min(cr - 6 - rightW - 12 - font.width(cat), ahX - 8 - font.width(cat));
			int maxName = Math.max(30, catX - nameX - 8);
			FormattedCharSequence clipped = font.split(name, maxName).isEmpty() ? FormattedCharSequence.EMPTY
					: font.split(name, maxName).get(0);
			g.drawString(font, clipped, nameX, y + 6, e.hidden ? FAINT : TEXT, false);
			g.drawString(font, cat, catX, y + 6, FAINT, false);
			g.drawString(font, right, cr - 6 - rightW, y + 6, FAINT, false);
		}
		g.disableScissor();
		drawAhHeaders(g, mouseX, mouseY);

		if (hovered != null) {
			List<FormattedCharSequence> tip = new ArrayList<>();
			ItemStack stack = hovered.stack();
			if (!stack.isEmpty()) {
				for (Component line : Screen.getTooltipFromItem(minecraft, stack)) {
					Component cleaned = Itemdex.cleanLine(line); // Menü-Bilder des Servers nicht mitzeichnen
					if (cleaned != null) {
						tip.add(cleaned.getVisualOrderText());
					}
				}
			} else {
				tip.add(Component.literal(Itemdex.clean(hovered.name)).getVisualOrderText());
			}
			tip.add(FormattedCharSequence.EMPTY);
			tip.add(Component.translatable("loothub.itemdex.first_seen", Fmt.date(hovered.firstSeen, true), Itemdex.clean(hovered.where))
					.withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
			tip.add(Component.translatable(hovered.hidden ? "loothub.itemdex.unhide_hint" : "loothub.itemdex.hide_hint")
					.withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
			tip.add(Component.translatable(hovered.favorite ? "loothub.itemdex.unfav_hint" : "loothub.itemdex.fav_hint")
					.withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
			g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
		}
		drawDropdown(g, mouseX, mouseY);
	}

	// ------------------------------------------------------------------ Auktionshaus-Spalten

	private static final int STAR_W = 12;
	private static final int AH_COL = 52;
	private static final String[] AH_HEADS = {"loothub.itemdex.ah.offer", "loothub.itemdex.ah.sales7", "loothub.itemdex.ah.avg7",
			"loothub.itemdex.ah.sales_all", "loothub.itemdex.ah.avg_all"};

	/** Linke Kante der fünf AH-Spalten (links vom Datum/Fundort). */
	private int ahColumnsX() {
		int dateW = font.width("00.00.0000 · ") + 70;
		return cr - 6 - dateW - 12 - AH_COL * AH_HEADS.length;
	}

	private void drawAhHeaders(GuiGraphics g, int mx, int my) {
		int x = ahColumnsX();
		int y = listTop - 11;
		String tipKey = null;
		for (int c = 0; c < AH_HEADS.length; c++) {
			String h = Component.translatable(AH_HEADS[c]).getString();
			int colRight = x + (c + 1) * AH_COL;
			g.drawString(font, h, colRight - 4 - font.width(h), y, MUTED, false);
			if (mx >= colRight - AH_COL && mx < colRight && my >= y - 1 && my < y + 10) {
				tipKey = AH_HEADS[c] + ".tooltip";
			}
		}
		g.fill(x, y + 9, x + AH_COL * AH_HEADS.length, y + 10, 0x40E2A13B);
		if (tipKey != null) {
			g.setTooltipForNextFrame(font, font.split(Component.translatable(tipKey), 220), mx, my);
		}
	}

	// ------------------------------------------------------------------ Auswahlliste „Art“

	/** Auswahl i: 0 = Alle, sonst Category.values()[i - 1]. */
	private int dropdownIndexAt(double mx, double my) {
		if (!dropdownOpen) {
			return -1;
		}
		int x = categoryButton.getX();
		int y = categoryButton.getY() + categoryButton.getHeight() + 1;
		int w = categoryButton.getWidth();
		int n = Category.values().length + 2;
		if (mx < x || mx >= x + w || my < y || my >= y + n * DD_ROW) {
			return -1;
		}
		return (int) ((my - y) / DD_ROW);
	}

	private void drawDropdown(GuiGraphics g, int mx, int my) {
		if (!dropdownOpen) {
			return;
		}
		g.nextStratum(); // über Liste und Item-Symbolen
		int x = categoryButton.getX();
		int y = categoryButton.getY() + categoryButton.getHeight() + 1;
		int w = categoryButton.getWidth();
		Category[] cats = Category.values();
		int n = cats.length + 2;
		// Anzahl je Art (eingeblendete Items)
		int[] counts = new int[cats.length];
		int all = 0, favs = 0;
		for (Entry e : Itemdex.entries()) {
			if (e.hidden) {
				continue;
			}
			all++;
			if (e.favorite) {
				favs++;
			}
			counts[e.category.ordinal()]++;
		}
		g.fill(x - 1, y - 1, x + w + 1, y + n * DD_ROW + 1, RED);
		g.fill(x, y, x + w, y + n * DD_ROW, 0xF0140B0D);
		int hover = dropdownIndexAt(mx, my);
		for (int i = 0; i < n; i++) {
			Category cat = i <= 1 ? null : cats[i - 2];
			int ry = y + i * DD_ROW;
			boolean selected = i == 1 ? favOnly : !favOnly && cat == category;
			if (i == hover) {
				g.fill(x, ry, x + w, ry + DD_ROW, 0x40E23B3B);
			}
			String label = Component.translatable(i == 0 ? "loothub.itemdex.cat.all" : i == 1 ? "loothub.itemdex.cat.fav" : cat.key).getString();
			int count = i == 0 ? all : i == 1 ? favs : counts[cat.ordinal()];
			g.drawString(font, (selected ? "✔ " : "   ") + label, x + 4, ry + 3, selected ? TEXT : (count > 0 ? MUTED : FAINT), false);
			String c = String.valueOf(count);
			g.drawString(font, c, x + w - 4 - font.width(c), ry + 3, FAINT, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (dropdownOpen) {
			int i = dropdownIndexAt(event.x(), event.y());
			boolean onButton = categoryButton.isMouseOver(event.x(), event.y());
			if (i >= 0) {
				favOnly = i == 1;
				category = i <= 1 ? null : Category.values()[i - 2];
				categoryButton.setMessage(categoryLabel());
				scroll = 0;
				refresh();
				dropdownOpen = false;
				return true;
			}
			if (!onButton) {
				dropdownOpen = false; // Klick daneben schließt die Liste
				return true;
			}
		}
		if (event.button() == 0 && event.x() < cl + STAR_W) {
			Entry e = rowAt(event.x(), event.y());
			if (e != null) {
				Itemdex.setFavorite(e, !e.favorite);
				return true;
			}
		}
		if (event.button() == 1) {
			Entry e = rowAt(event.x(), event.y());
			if (e != null) {
				Itemdex.setHidden(e, !e.hidden);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= listTop && my < cb) {
			scroll = Math.max(0, scroll - (int) Math.signum(vertical) * 3);
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	private static Component cleanOr(Component name, String fallback) {
		Component c = Itemdex.cleanLine(name);
		return c != null ? c : Component.literal(Itemdex.clean(fallback));
	}
}
