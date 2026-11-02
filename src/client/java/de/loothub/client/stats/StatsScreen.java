package de.loothub.client.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.Tabs;
import de.loothub.client.stats.StatsStore.Range;

/**
 * Reiter „Statistik“: eigene Statistik wie bei Vanilla (Allgemein, Blöcke, Items, Mobs),
 * wahlweise für heute, die letzten 7 Tage oder gesamt.
 */
public class StatsScreen extends LoothubScreen {
	private static final int PANEL = 0xE01C1315;
	private static final int BORDER = 0xFF3A1F23;
	private static final int GOLD = 0xFFFFC040;
	private static final int ROW_HOVER = 0x22FFFFFF;
	private static final int PAD = 8;
	private static final int ROW_H = 18;
	private static final int GENERAL_ROW_H = 14;

	public enum Category {
		GENERAL("loothub.stats.cat.general"),
		BLOCKS("loothub.stats.cat.blocks"),
		ITEMS("loothub.stats.cat.items"),
		MOBS("loothub.stats.cat.mobs");

		final String translationKey;

		Category(String translationKey) {
			this.translationKey = translationKey;
		}
	}

	/** Eine Zeile der Tabellen Blöcke/Items/Mobs. */
	private record Row(String id, String name, ItemStack icon, long[] values) {
	}

	// Bleibt zwischen zwei Öffnungen erhalten
	private static Category category = Category.GENERAL;
	private static Range range = Range.TOTAL;
	private static String search = "";
	private static int sortColumn = 1; // 0 = Name, 1.. = Wertespalten
	private static boolean sortDesc = true;

	private static final Map<String, ItemStack> ICONS = new HashMap<>();
	private static final Map<String, String> NAMES = new HashMap<>();

	private Button clearButton;
	private long clearArmedUntil;
	private final List<Button> categoryButtons = new ArrayList<>();
	private final List<Button> rangeButtons = new ArrayList<>();

	private Map<String, Long> sums = Map.of();
	private List<Row> rows = List.of();
	private int seenVersion = -1;
	private double scroll;

	private int tableX, tableY, tableW, tableH;

	public StatsScreen() {
		super(Page.STATS);
	}

	// ------------------------------------------------------------------ Aufbau

	@Override
	protected void initPage() {

		int y = ct;
		// Links: Kategorien, rechts: Zeitraum
		categoryButtons.clear();
		int catW = Mth.clamp((cw / 2 - 12) / 4, 46, 78);
		int x = cl;
		for (Category c : Category.values()) {
			categoryButtons.add(addRenderableWidget(Button.builder(Component.translatable(c.translationKey), b -> {
				category = c;
				sortColumn = 1;
				sortDesc = true;
				scroll = 0;
				clearWidgets();
				init();
			}).bounds(x, y, catW, 16).build()));
			x += catW + 4;
		}
		rangeButtons.clear();
		int rw = Mth.clamp((cw / 2 - 8) / 3, 46, 70);
		x = cr - 3 * rw - 8;
		for (Range r : Range.values()) {
			rangeButtons.add(addRenderableWidget(Button.builder(Component.translatable(
					"loothub.stats.range." + r.name().toLowerCase(Locale.ROOT)), b -> {
				range = r;
				updateToggleButtons();
				seenVersion = -1;
				refresh();
			}).bounds(x, y, rw, 16).build()));
			x += rw + 4;
		}
		updateToggleButtons();

		int y2 = y + 20;
		if (category != Category.GENERAL) {
			EditBox box = new EditBox(font, cl, y2, Math.min(220, cw / 2), 16, Component.translatable("loothub.screen.search"));
			box.setHint(Component.translatable("loothub.screen.search_hint").withStyle(ChatFormatting.DARK_GRAY));
			box.setValue(search);
			box.setResponder(t -> {
				search = t;
				scroll = 0;
				seenVersion = -1;
				refresh();
			});
			addRenderableWidget(box);
			setFocused(box);
		}
		clearButton = addRenderableWidget(Button.builder(Component.translatable("loothub.stats.clear"), b -> onClear())
				.bounds(cr - 130, y2, 130, 16).build());

		tableX = cl;
		tableY = y2 + 22;
		tableW = cw;
		tableH = cb - tableY;

		seenVersion = -1;
		refresh();
	}

	private void updateToggleButtons() {
		Category[] cats = Category.values();
		for (int i = 0; i < categoryButtons.size(); i++) {
			categoryButtons.get(i).active = cats[i] != category;
		}
		Range[] ranges = Range.values();
		for (int i = 0; i < rangeButtons.size(); i++) {
			rangeButtons.get(i).active = ranges[i] != range;
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (clearArmedUntil > 0 && System.currentTimeMillis() > clearArmedUntil) {
			clearArmedUntil = 0;
			clearButton.setMessage(Component.translatable("loothub.stats.clear"));
		}
		refresh();
	}

	private void onClear() {
		long now = System.currentTimeMillis();
		if (clearArmedUntil > now) {
			StatsStore.clear();
			clearArmedUntil = 0;
			clearButton.setMessage(Component.translatable("loothub.stats.clear"));
		} else {
			clearArmedUntil = now + 3000;
			clearButton.setMessage(Component.translatable("loothub.stats.clear.confirm").withStyle(ChatFormatting.RED));
		}
	}

	// ------------------------------------------------------------------ Daten

	private void refresh() {
		int v = StatsStore.version();
		if (v == seenVersion) {
			return;
		}
		seenVersion = v;
		sums = StatsStore.sum(range);
		rows = switch (category) {
			case GENERAL -> List.of();
			case BLOCKS -> buildRows(new String[]{"mined.", "placed."}, Kind.BLOCK);
			case ITEMS -> buildRows(new String[]{"picked.", "eaten."}, Kind.ITEM);
			case MOBS -> buildRows(new String[]{"killed."}, Kind.MOB);
		};
		clampScroll();
	}

	private enum Kind {
		BLOCK, ITEM, MOB
	}

	private List<Row> buildRows(String[] prefixes, Kind kind) {
		Map<String, long[]> byId = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : sums.entrySet()) {
			for (int i = 0; i < prefixes.length; i++) {
				if (e.getKey().startsWith(prefixes[i])) {
					String id = e.getKey().substring(prefixes[i].length());
					byId.computeIfAbsent(id, k -> new long[prefixes.length])[i] += e.getValue();
				}
			}
		}
		String q = search.toLowerCase(Locale.ROOT).strip();
		List<Row> out = new ArrayList<>();
		for (Map.Entry<String, long[]> e : byId.entrySet()) {
			String name = name(e.getKey(), kind);
			if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !e.getKey().contains(q.replace(' ', '_'))) {
				continue;
			}
			out.add(new Row(e.getKey(), name, icon(e.getKey(), kind), e.getValue()));
		}
		Comparator<Row> cmp = sortColumn == 0
				? Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER)
				: Comparator.comparingLong(r -> r.values()[Math.min(sortColumn - 1, r.values().length - 1)]);
		if (sortDesc) {
			cmp = cmp.reversed();
		}
		out.sort(cmp.thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	private long total(String prefix) {
		long sum = 0;
		for (Map.Entry<String, Long> e : sums.entrySet()) {
			if (e.getKey().startsWith(prefix)) {
				sum += e.getValue();
			}
		}
		return sum;
	}

	private long get(String key) {
		return sums.getOrDefault(key, 0L);
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		panel(g, tableX, tableY, tableW, tableH);
		super.render(g, mouseX, mouseY, delta);
		if (category == Category.GENERAL) {
			renderGeneral(g);
		} else {
			renderTable(g, mouseX, mouseY);
		}
	}

	private void renderGeneral(GuiGraphics g) {
		long walked = get("general.walk") + get("general.sprint") + get("general.crouch");
		List<String[]> entries = new ArrayList<>();
		entries.add(entry("play_time", Fmt.longDuration(get("general.play_time"))));
		entries.add(entry("walk_total", distance(walked)));
		entries.add(entry("walk", distance(get("general.walk"))));
		entries.add(entry("sprint", distance(get("general.sprint"))));
		entries.add(entry("crouch", distance(get("general.crouch"))));
		entries.add(entry("swim", distance(get("general.swim"))));
		entries.add(entry("climb", distance(get("general.climb"))));
		entries.add(entry("elytra", distance(get("general.elytra"))));
		entries.add(entry("fly", distance(get("general.fly"))));
		entries.add(entry("ride", distance(get("general.ride"))));
		entries.add(entry("jumps", Fmt.whole(get("general.jumps"))));
		entries.add(entry("mined", Fmt.whole(total("mined."))));
		entries.add(entry("placed", Fmt.whole(total("placed."))));
		entries.add(entry("picked", Fmt.whole(total("picked."))));
		entries.add(entry("eaten", Fmt.whole(total("eaten."))));
		entries.add(entry("killed", Fmt.whole(total("killed."))));
		entries.add(entry("damage_dealt", hearts(get("general.damage_dealt"))));
		entries.add(entry("damage_taken", hearts(get("general.damage_taken"))));
		entries.add(entry("deaths", Fmt.whole(get("general.deaths"))));

		// Zwei Spalten, wenn genug Platz ist
		int columns = tableW >= 420 ? 2 : 1;
		int perColumn = (entries.size() + columns - 1) / columns;
		int colW = (tableW - 10 - (columns - 1) * 16) / columns;
		for (int i = 0; i < entries.size(); i++) {
			int col = i / perColumn;
			int row = i % perColumn;
			int x = tableX + 5 + col * (colW + 16);
			int y = tableY + 6 + row * GENERAL_ROW_H;
			if (y + 9 > tableY + tableH) {
				break;
			}
			if (row % 2 == 0) {
				g.fill(x - 2, y - 2, x + colW + 2, y + GENERAL_ROW_H - 2, 0x14FFFFFF);
			}
			g.drawString(font, entries.get(i)[0], x, y, MUTED, false);
			String value = entries.get(i)[1];
			g.drawString(font, value, x + colW - font.width(value), y, TEXT, false);
		}
		Component note = Component.translatable("loothub.stats.note");
		g.drawString(font, note, tableX + 5, tableY + tableH - 12, FAINT, false);
	}

	private String[] entry(String key, String value) {
		return new String[]{Component.translatable("loothub.stats.general." + key).getString(), value};
	}

	private List<String> columnKeys() {
		return switch (category) {
			case BLOCKS -> List.of("loothub.stats.col.mined", "loothub.stats.col.placed");
			case ITEMS -> List.of("loothub.stats.col.picked", "loothub.stats.col.eaten");
			case MOBS -> List.of("loothub.stats.col.killed");
			case GENERAL -> List.of();
		};
	}

	/** x-Position (rechter Rand) der Wertespalten. */
	private int columnRight(int i, int count) {
		int colW = 90;
		return tableX + tableW - 10 - (count - 1 - i) * colW;
	}

	private void renderTable(GuiGraphics g, int mx, int my) {
		List<String> cols = columnKeys();
		int headerY = tableY + 4;
		String nameHeader = Component.translatable("loothub.stats.col.name").getString() + arrow(0);
		g.drawString(font, nameHeader, tableX + 24, headerY, sortColumn == 0 ? GOLD : FAINT, false);
		for (int i = 0; i < cols.size(); i++) {
			String h = Component.translatable(cols.get(i)).getString() + arrow(i + 1);
			int r = columnRight(i, cols.size());
			g.drawString(font, h, r - font.width(h), headerY, sortColumn == i + 1 ? GOLD : FAINT, false);
		}
		g.fill(tableX + 1, tableY + 14, tableX + tableW - 1, tableY + 15, BORDER);

		int top = tableY + 16;
		int bottom = tableY + tableH - 1;
		if (rows.isEmpty()) {
			Component msg = Component.translatable(search.isBlank() ? "loothub.stats.empty" : "loothub.screen.no_results");
			g.drawString(font, msg, tableX + (tableW - font.width(msg)) / 2, top + 10, FAINT, false);
			return;
		}

		g.enableScissor(tableX + 1, top, tableX + tableW - 1, bottom);
		for (int i = Math.max(0, (int) (scroll / ROW_H)); i < rows.size(); i++) {
			int ry = top + i * ROW_H - (int) scroll;
			if (ry > bottom) {
				break;
			}
			Row row = rows.get(i);
			if (mx >= tableX && mx < tableX + tableW && my >= Math.max(ry, top) && my < Math.min(ry + ROW_H, bottom)) {
				g.fill(tableX + 1, ry, tableX + tableW - 1, ry + ROW_H, ROW_HOVER);
			}
			if (!row.icon().isEmpty()) {
				g.renderItem(row.icon(), tableX + 4, ry + 1);
			}
			int nameMax = columnRight(0, cols.size()) - 90 - (tableX + 24);
			g.drawString(font, ellipsize(row.name(), nameMax), tableX + 24, ry + 5, TEXT, false);
			for (int c = 0; c < cols.size(); c++) {
				long v = row.values()[c];
				String s = v == 0 ? "–" : Fmt.whole(v);
				int r = columnRight(c, cols.size());
				g.drawString(font, s, r - font.width(s), ry + 5, v == 0 ? FAINT : TEXT, false);
			}
		}
		g.disableScissor();

		int contentH = rows.size() * ROW_H;
		int viewH = bottom - top;
		if (contentH > viewH) {
			int thumbH = Math.max(10, viewH * viewH / contentH);
			int thumbY = top + (int) ((viewH - thumbH) * (scroll / Math.max(1, contentH - viewH)));
			g.fill(tableX + tableW - 4, top, tableX + tableW - 1, bottom, 0x33FFFFFF);
			g.fill(tableX + tableW - 4, thumbY, tableX + tableW - 1, thumbY + thumbH, 0xAAFFFFFF);
		}
	}

	private String arrow(int column) {
		return sortColumn == column ? (sortDesc ? " ▼" : " ▲") : "";
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		// Klick auf eine Spaltenüberschrift sortiert danach (nochmal klicken = umgekehrt)
		if (category != Category.GENERAL && event.button() == 0
				&& event.y() >= tableY && event.y() < tableY + 15 && event.x() >= tableX && event.x() < tableX + tableW) {
			int count = columnKeys().size();
			int clicked = 0;
			for (int i = 0; i < count; i++) {
				int r = columnRight(i, count);
				if (event.x() >= r - 80 && event.x() <= r + 4) {
					clicked = i + 1;
				}
			}
			if (clicked == sortColumn) {
				sortDesc = !sortDesc;
			} else {
				sortColumn = clicked;
				sortDesc = clicked != 0;
			}
			seenVersion = -1;
			refresh();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (category != Category.GENERAL && mx >= tableX && mx < tableX + tableW && my >= tableY && my < tableY + tableH) {
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

	private void clampScroll() {
		int viewH = tableH - 17;
		scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() * ROW_H - viewH));
	}

	/** Zentimeter → "350 m" oder "12,4 km". */
	private static String distance(long cm) {
		double m = cm / 100.0;
		return m < 1000 ? Fmt.whole(Math.round(m)) + " m" : Fmt.compactPlain(m / 1000.0) + " km";
	}

	/** Zehntel-Lebenspunkte → Herzen (2 Lebenspunkte = 1 Herz). */
	private static String hearts(long tenths) {
		return Fmt.compactPlain(tenths / 20.0) + " ❤";
	}

	private static String name(String id, Kind kind) {
		return NAMES.computeIfAbsent(kind + ":" + id, k -> {
			Identifier identifier = Identifier.tryParse(id);
			if (identifier != null) {
				switch (kind) {
					case BLOCK -> {
						Block b = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
						if (b != null) {
							return b.getName().getString();
						}
					}
					case ITEM -> {
						Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
						if (item != null) {
							return new ItemStack(item).getHoverName().getString();
						}
					}
					case MOB -> {
						EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(identifier).orElse(null);
						if (type != null) {
							return type.getDescription().getString();
						}
					}
				}
			}
			return id;
		});
	}

	private static ItemStack icon(String id, Kind kind) {
		return ICONS.computeIfAbsent(kind + ":" + id, k -> {
			Identifier identifier = Identifier.tryParse(id);
			if (identifier == null) {
				return ItemStack.EMPTY;
			}
			Item item = switch (kind) {
				case BLOCK -> BuiltInRegistries.BLOCK.getOptional(identifier).map(Block::asItem).orElse(Items.AIR);
				case ITEM -> BuiltInRegistries.ITEM.getOptional(identifier).orElse(Items.AIR);
				case MOB -> BuiltInRegistries.ITEM.getOptional(
						Identifier.fromNamespaceAndPath(identifier.getNamespace(), identifier.getPath() + "_spawn_egg")).orElse(Items.AIR);
			};
			return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
		});
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
