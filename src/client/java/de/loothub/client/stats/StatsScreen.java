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
		PLANTS("loothub.stats.cat.plants"),
		FOOD("loothub.stats.cat.food"),
		ITEMS("loothub.stats.cat.items"),
		TOOLS("loothub.stats.cat.tools"),
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
	/** Bei „Heute“: so viele Tage zurückgeblättert (0 = heute). */
	private static int dayBack;
	private Button prevDay, nextDay;
	private static String search = "";
	private static int sortColumn = 1; // 0 = Name, 1.. = Wertespalten
	private static boolean sortDesc = true;

	private static final Map<String, ItemStack> ICONS = new HashMap<>();
	private static final Map<String, String> NAMES = new HashMap<>();

	private Button clearButton;
	private int sinceY;
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
		int catW = Mth.clamp((cw * 3 / 5 - 16) / Category.values().length, 44, 76);
		// Knöpfe so breit wie ihre Beschriftung, solange alle in die Zeile passen
		int need = 0;
		for (Category c : Category.values()) {
			need += Math.max(44, font.width(Component.translatable(c.translationKey)) + 12) + 4;
		}
		boolean fitLabels = need <= cw * 3 / 5;
		int x = cl;
		for (Category c : Category.values()) {
			int bw = fitLabels ? Math.max(44, font.width(Component.translatable(c.translationKey)) + 12) : catW;
			categoryButtons.add(addRenderableWidget(Button.builder(Component.translatable(c.translationKey), b -> {
				category = c;
				sortColumn = 1;
				sortDesc = true;
				scroll = 0;
				clearWidgets();
				init();
			}).bounds(x, y, bw, 16).build()));
			x += bw + 4;
		}
		rangeButtons.clear();
		int rw = Mth.clamp((cw / 2 - 8) / 3, 46, 70);
		x = cr - 3 * rw - 8;
		// Tage zurück- und vorblättern (wie im Jobverlauf) – links neben „Heute“
		prevDay = addRenderableWidget(Button.builder(Component.literal("‹"), b -> shiftDay(1))
				.bounds(x - 2 * 16 - 6, y, 16, 16).build());
		nextDay = addRenderableWidget(Button.builder(Component.literal("›"), b -> shiftDay(-1))
				.bounds(x - 16 - 2, y, 16, 16).build());
		for (Range r : Range.values()) {
			rangeButtons.add(addRenderableWidget(Button.builder(rangeLabel(r), b -> {
				range = r;
				dayBack = 0;
				updateToggleButtons();
				seenVersion = -1;
				refresh();
			}).bounds(x, y, rw, 16).build()));
			x += rw + 4;
		}
		updateToggleButtons();

		int y2 = y + 20;
		sinceY = y2;
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
			// „Heute“ bleibt anklickbar, solange ein früherer Tag gezeigt wird (zurück zu heute)
			rangeButtons.get(i).active = ranges[i] != range || ranges[i] == Range.TODAY && dayBack > 0;
			rangeButtons.get(i).setMessage(rangeLabel(ranges[i]));
		}
		if (prevDay != null) {
			java.time.LocalDate first = StatsStore.firstDay();
			prevDay.active = first != null && shownDay().isAfter(first);
			nextDay.active = range == Range.TODAY && dayBack > 0;
		}
	}

	/** Gezeigter Tag bei „Heute“. */
	private static java.time.LocalDate shownDay() {
		return java.time.LocalDate.now().minusDays(range == Range.TODAY ? dayBack : 0);
	}

	/** „Heute“ bzw. beim Zurückblättern das Datum (z. B. „Fr 14.11.“). */
	private static Component rangeLabel(Range r) {
		if (r == Range.TODAY && range == Range.TODAY && dayBack > 0) {
			java.time.LocalDate d = shownDay();
			String wd = d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT,
					net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected().startsWith("de")
							? Locale.GERMAN : Locale.ENGLISH).replace(".", "");
			return Component.literal(wd + " " + d.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.")));
		}
		return Component.translatable("loothub.stats.range." + r.name().toLowerCase(Locale.ROOT));
	}

	private void shiftDay(int delta) {
		if (range != Range.TODAY) {
			range = Range.TODAY;
			dayBack = 0;
		}
		dayBack = Math.max(0, dayBack + delta);
		scroll = 0;
		updateToggleButtons();
		seenVersion = -1;
		refresh();
	}

	@Override
	public void tick() {
		super.tick();
		refresh();
	}

	private void onClear() {
		minecraft.setScreen(new de.loothub.client.ConfirmScreen(this, Component.translatable("loothub.stats.clear"),
				Component.translatable("loothub.stats.clear.question"), () -> {
					StatsStore.clear();
				}));
	}


	// ------------------------------------------------------------------ Daten

	private void refresh() {
		int v = StatsStore.version();
		if (v == seenVersion) {
			return;
		}
		seenVersion = v;
		sums = range == Range.TODAY ? StatsStore.sumDay(shownDay()) : StatsStore.sum(range);
		rows = switch (category) {
			case GENERAL -> List.of();
			case BLOCKS -> buildRows(new String[]{"mined.", "placed.", "picked."}, Kind.BLOCK);
			case PLANTS -> buildRows(new String[]{"mined.", "placed.", "picked."}, Kind.PLANT);
			case FOOD -> buildRows(new String[]{"mined.", "placed.", "picked.", "eaten."}, Kind.ITEM);
			case ITEMS -> buildRows(new String[]{"picked.", "used."}, Kind.ITEM);
			case TOOLS -> buildRows(new String[]{"tool_mined.", "tool_hits.", "tool_used.", "picked."}, Kind.ITEM);
			case MOBS -> buildRows(new String[]{"killed."}, Kind.MOB);
		};
		clampScroll();
	}

	private enum Kind {
		BLOCK, ITEM, MOB, PLANT
	}

	private List<Row> buildRows(String[] prefixes, Kind kind) {
		int[] cols = new int[prefixes.length];
		for (int i = 0; i < cols.length; i++) {
			cols[i] = i;
		}
		return buildRows(prefixes, cols, prefixes.length, kind);
	}

	/** {@code cols[i]} = Spalte für Präfix i (mehrere Präfixe können in eine Spalte fallen). */
	private List<Row> buildRows(String[] prefixes, int[] cols, int columnCount, Kind kind) {
		Map<String, long[]> byId = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : sums.entrySet()) {
			for (int i = 0; i < prefixes.length; i++) {
				if (e.getKey().startsWith(prefixes[i])) {
					String id = e.getKey().substring(prefixes[i].length());
					if (!accepts(prefixes[i], id, kind)) {
						continue;
					}
					if (kind == Kind.PLANT) {
						id = plantKey(id); // Feldfrucht-Block (z. B. „Wheat Crops“) und Samen in eine Zeile
					}
					if (category == Category.FOOD && isFoodPlant(id)) {
						id = isGlowBerries(id) ? "minecraft:glow_berries" : plantKey(id); // Pflanze und Frucht in eine Zeile
					}
					byId.computeIfAbsent(id, k -> new long[columnCount])[cols[i]] += e.getValue();
				}
			}
		}
		String q = search.toLowerCase(Locale.ROOT).strip();
		List<Row> out = new ArrayList<>();
		for (Map.Entry<String, long[]> e : byId.entrySet()) {
			if (kind == Kind.BLOCK && !isRealBlock(e.getKey())) {
				continue;
			}
			if (kind == Kind.PLANT && !isPlantId(e.getKey())) {
				continue;
			}
			String name = name(e.getKey(), kind);
			if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !e.getKey().contains(q.replace(' ', '_'))) {
				continue;
			}
			long[] vals = e.getValue();
			if (category == Category.TOOLS) {
				boolean[] use = toolColumns(e.getKey());
				for (int c = 0; c < vals.length && c < use.length; c++) {
					if (!use[c]) {
						vals[c] = -1; // passt nicht zu diesem Gegenstand → leer
					}
				}
			}
			out.add(new Row(e.getKey(), name, icon(e.getKey(), kind), vals));
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
		boolean blocks = prefix.equals("mined.") || prefix.equals("placed.");
		long sum = 0;
		for (Map.Entry<String, Long> e : sums.entrySet()) {
			if (e.getKey().startsWith(prefix)
					&& (!blocks || isRealBlock(e.getKey().substring(prefix.length())))) {
				sum += e.getValue();
			}
		}
		return sum;
	}

	/**
	 * Ist das Item selbst ein echter Block (Erde, Stein, Holz …)? Samen u. Ä. sind zwar technisch Block-Items
	 * (sie pflanzen etwas an), haben aber eine andere ID als der Block und zählen als Item.
	 */
	private static boolean isBlockItem(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return false;
		}
		Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) {
			return false;
		}
		Block block = blockItem.getBlock();
		return BuiltInRegistries.BLOCK.getKey(block).equals(identifier) && StatsTracker.isRealBlock(block);
	}

	/** Für ältere Einträge: Pflanzen wie Gras nicht als Block zählen. */
	/** Hat das Item Haltbarkeit (Werkzeug, Waffe, Rüstung …)? */
	private static boolean isTool(String id) {
		Identifier identifier = Identifier.tryParse(id);
		Item item = identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		return item != null && new ItemStack(item).isDamageableItem();
	}

	/**
	 * Gehört dieser Eintrag in die aktuelle Kategorie? Jedes Item steht genau einmal: Werkzeuge unter „Tools“,
	 * Essen unter „Nahrung“, Pflanzen/Samen unter „Pflanzen“, echte Blöcke unter „Blöcke“, der Rest unter „Items“.
	 */
	private boolean accepts(String prefix, String id, Kind kind) {
		return switch (category) {
			case BLOCKS -> !isFoodBlock(id) && !isLeaves(id) && (!prefix.equals("picked.") || (isBlockItem(id) && !isFood(id)));
			case PLANTS -> isPlantId(id) && !isFood(id) && !isFoodBlock(id) && !isFoodPlant(id);
			case FOOD -> prefix.equals("placed.") ? isFoodPlant(id)
					: isFoodBlock(id) || isFoodPlant(id) || (!prefix.equals("mined.") && isFood(id));
			case TOOLS -> !prefix.equals("picked.") || isTool(id);
			case ITEMS -> !isTool(id) && !isFood(id) && !isFoodBlock(id) && !isPlantId(id) && !isBlockItem(id);
			default -> true;
		};
	}

	/** Pflanzen-Block → zugehöriges Item (Weizen-Pflanze → Weizensamen), damit Ernten, Pflanzen und Aufsammeln eine Zeile sind. */
	private static String plantKey(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return id;
		}
		Block block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
		if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
			return id;
		}
		Item item = block.asItem();
		return item == Items.AIR ? id : BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/**
	 * Welche Spalten (Abgebaut, Treffer, Benutzt, Aufgesammelt) passen zu diesem Werkzeug? Spitzhacke: nur Abbauen;
	 * Waffen: Treffer; Axt: Abbauen, Treffer, Benutzen (Entrinden); Schaufel/Hacke: Abbauen und Benutzen (Wege, Felder);
	 * Rüstung: nur Aufsammeln; Bogen, Angel, Schere, Feuerzeug … : Benutzen.
	 */
	private static boolean[] toolColumns(String id) {
		Identifier identifier = Identifier.tryParse(id);
		Item item = identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		if (item == null) {
			return new boolean[]{true, true, true, true};
		}
		ItemStack st = new ItemStack(item);
		if (st.is(net.minecraft.tags.ItemTags.PICKAXES)) {
			return new boolean[]{true, false, false, true};
		}
		if (st.is(net.minecraft.tags.ItemTags.AXES)) {
			return new boolean[]{true, true, true, true};
		}
		if (st.is(net.minecraft.tags.ItemTags.SHOVELS) || st.is(net.minecraft.tags.ItemTags.HOES)) {
			return new boolean[]{true, false, true, true};
		}
		if (st.is(net.minecraft.tags.ItemTags.SWORDS) || item instanceof net.minecraft.world.item.TridentItem
				|| item instanceof net.minecraft.world.item.MaceItem) {
			return new boolean[]{false, true, false, true};
		}
		if (item instanceof net.minecraft.world.item.ShearsItem) {
			return new boolean[]{true, false, true, true};
		}
		if (st.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)) {
			return new boolean[]{false, false, false, true}; // Rüstung, Elytren …
		}
		return new boolean[]{false, false, true, true};
	}

	/** Laub jeder Art (Eiche, Kirsche, Azalee …) – steht unter „Pflanzen“. */
	private static boolean isLeaves(String id) {
		Identifier identifier = Identifier.tryParse(id);
		Block block = identifier == null ? null : BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
		return block != null && block.defaultBlockState().is(net.minecraft.tags.BlockTags.LEAVES);
	}

	/** Leuchtbeeren samt ihren Ranken – stehen unter „Nahrung“, nicht unter „Pflanzen“. */
	private static boolean isGlowBerries(String id) {
		return id.equals("minecraft:glow_berries") || id.equals("minecraft:cave_vines") || id.equals("minecraft:cave_vines_plant");
	}

	/** Pflanze, deren Ernte essbar ist (Karotten, Kartoffeln, Rote Bete, Süß- und Leuchtbeeren) – steht unter „Nahrung“. */
	private static boolean isFoodPlant(String id) {
		if (isGlowBerries(id)) {
			return true;
		}
		Identifier identifier = Identifier.tryParse(id);
		Block block = identifier == null ? null : BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
		return block != null && block != net.minecraft.world.level.block.Blocks.AIR && StatsTracker.isPlant(block)
				&& isFood(plantKey(id));
	}

	/** Melonen und Kürbisse: eigentlich Blöcke, stehen aber unter „Nahrung“. */
	private static boolean isFoodBlock(String id) {
		return id.equals("minecraft:melon") || id.equals("minecraft:pumpkin") || id.equals("minecraft:carved_pumpkin");
	}

	/** Essbar (Apfel, Brot, gebratenes Fleisch, Chorusfrucht …)? */
	private static boolean isFood(String id) {
		Identifier identifier = Identifier.tryParse(id);
		Item item = identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		return item != null && item != Items.AIR && new ItemStack(item).has(net.minecraft.core.component.DataComponents.FOOD);
	}

	/** Pflanze als Block-ID (Abbauen/Platzieren) oder als Item, das eine Pflanze setzt (Blume, Setzling, Samen …)? */
	private static boolean isPlantId(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return false;
		}
		Block block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
		if (block != null && (StatsTracker.isPlant(block) || block.defaultBlockState().is(net.minecraft.tags.BlockTags.LEAVES))) {
			return true; // Laub zählt zu den Pflanzen
		}
		Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		if (item == Items.WHEAT || item == Items.SUGAR_CANE || item == Items.BAMBOO || item == Items.CACTUS) {
			return true; // Ernte, die selbst kein Block-Item einer Pflanze ist
		}
		return item instanceof net.minecraft.world.item.BlockItem bi && StatsTracker.isPlant(bi.getBlock());
	}

	private static boolean isRealBlock(String id) {
		Identifier identifier = Identifier.tryParse(id);
		Block block = identifier == null ? null : BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
		return block == null || StatsTracker.isRealBlock(block);
	}

	private long get(String key) {
		return sums.getOrDefault(key, 0L);
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		panel(g, tableX, tableY, tableW, tableH);
		super.render(g, mouseX, mouseY, delta);
		renderSince(g);
		if (category == Category.GENERAL) {
			renderGeneral(g);
		} else {
			renderTable(g, mouseX, mouseY);
		}
	}

	/** „Gezählt seit …“ links neben „Statistik löschen“, passend zum gewählten Zeitraum. */
	private void renderSince(GuiGraphics g) {
		java.time.LocalDate since = StatsStore.since(range);
		Component text;
		if (range == Range.TODAY && dayBack > 0) {
			// früherer Tag: nur dieser eine Tag
			text = Component.translatable("loothub.stats.day",
					shownDay().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")));
		} else if (since == null) {
			text = Component.translatable("loothub.stats.since.none");
		} else {
			long days = java.time.temporal.ChronoUnit.DAYS.between(since, java.time.LocalDate.now()) + 1;
			String date = since.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
			text = Component.translatable(days == 1 ? "loothub.stats.since.one" : "loothub.stats.since", date, days);
		}
		int x = clearButton.getX() - 8 - font.width(text);
		g.drawString(font, text, x, sinceY + 4, MUTED, false);
	}

	private void renderGeneral(GuiGraphics g) {
		long walked = get("general.walk") + get("general.sprint") + get("general.crouch");
		List<GeneralEntry> entries = new ArrayList<>();
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
		entries.add(entry("tool_uses", Fmt.whole(total("tool_mined.") + total("tool_hits.") + total("tool_used."))));
		entries.add(entry("damage_dealt", hearts(get("general.damage_dealt"))));
		entries.add(entry("damage_taken", hearts(get("general.damage_taken"))));
		entries.add(entry("deaths", Fmt.whole(get("general.deaths"))));
		// gefundene Jobschätze – bei „Gesamt“ auch die vor dem Zählen in der Statistik (Zähler des Job-Widgets)
		long treasures = get("general.treasures");
		if (range == Range.TOTAL) {
			treasures = Math.max(treasures, de.loothub.client.jobs.WidgetConfig.get().treasuresFound);
		}
		entries.add(entry("treasures", Fmt.whole(treasures)));
		// Spitzenreiter im gewählten Zeitraum
		entries.add(top("top_mined", Kind.BLOCK, "mined."));
		entries.add(top("top_placed", Kind.BLOCK, "placed."));
		entries.add(top("top_tool", Kind.ITEM, "tool_mined.", "tool_hits.", "tool_used."));
		entries.add(top("top_killed", Kind.MOB, "killed."));

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
			GeneralEntry e = entries.get(i);
			g.drawString(font, e.label(), x, y, MUTED, false);
			int vx = x + colW - font.width(e.value());
			g.drawString(font, e.value(), vx, y, e.value().equals("–") ? FAINT : GOLD, false);
			if (!e.icon().isEmpty()) {
				g.renderItem(e.icon(), vx - 19, y - 4);
			} else if (e.serverMob()) {
				serverIcon(g, vx - 19, y - 4);
			}
		}
		Component note = Component.translatable("loothub.stats.note");
		g.drawString(font, note, tableX + 5, tableY + tableH - 12, FAINT, false);
	}

	/** Eine Zeile unter „Allgemein“; {@code icon} nur bei den Spitzenreitern. */
	private record GeneralEntry(String label, String value, ItemStack icon, boolean serverMob) {
		GeneralEntry(String label, String value, ItemStack icon) {
			this(label, value, icon, false);
		}
	}

	/** Passendes Symbol je Eintrag unter „Allgemein“. */
	private static final Map<String, ItemStack> GENERAL_ICONS = Map.ofEntries(
			Map.entry("play_time", new ItemStack(Items.CLOCK)),
			Map.entry("walk_total", new ItemStack(Items.COMPASS)),
			Map.entry("walk", new ItemStack(Items.LEATHER_BOOTS)),
			Map.entry("sprint", new ItemStack(Items.SUGAR)),
			Map.entry("crouch", new ItemStack(Items.SCULK_SENSOR)),
			Map.entry("swim", new ItemStack(Items.WATER_BUCKET)),
			Map.entry("climb", new ItemStack(Items.LADDER)),
			Map.entry("elytra", new ItemStack(Items.ELYTRA)),
			Map.entry("fly", new ItemStack(Items.FEATHER)),
			Map.entry("ride", new ItemStack(Items.SADDLE)),
			Map.entry("jumps", new ItemStack(Items.RABBIT_FOOT)),
			Map.entry("mined", new ItemStack(Items.IRON_PICKAXE)),
			Map.entry("placed", new ItemStack(Items.BRICKS)),
			Map.entry("picked", new ItemStack(Items.HOPPER)),
			Map.entry("eaten", new ItemStack(Items.COOKED_BEEF)),
			Map.entry("killed", new ItemStack(Items.IRON_SWORD)),
			Map.entry("tool_uses", new ItemStack(Items.ANVIL)),
			Map.entry("damage_dealt", new ItemStack(Items.BLAZE_POWDER)),
			Map.entry("damage_taken", new ItemStack(Items.SHIELD)),
			Map.entry("deaths", new ItemStack(Items.SKELETON_SKULL)),
			Map.entry("treasures", new ItemStack(Items.CHEST)));

	private GeneralEntry entry(String key, String value) {
		return new GeneralEntry(Component.translatable("loothub.stats.general." + key).getString(), value,
				GENERAL_ICONS.getOrDefault(key, ItemStack.EMPTY));
	}

	/** Spitzenreiter: höchster Zähler über die Präfixe (gleiche ID wird zusammengezählt), im gewählten Zeitraum. */
	private GeneralEntry top(String key, Kind kind, String... prefixes) {
		Map<String, Long> byId = new HashMap<>();
		boolean blocks = kind == Kind.BLOCK;
		for (Map.Entry<String, Long> e : sums.entrySet()) {
			for (String prefix : prefixes) {
				if (e.getKey().startsWith(prefix)) {
					String id = e.getKey().substring(prefix.length());
					if (!blocks || isRealBlock(id)) {
						byId.merge(id, e.getValue(), Long::sum);
					}
				}
			}
		}
		String bestId = null;
		long best = 0;
		for (Map.Entry<String, Long> e : byId.entrySet()) {
			if (e.getValue() > best) {
				best = e.getValue();
				bestId = e.getKey();
			}
		}
		String label = Component.translatable("loothub.stats.general." + key).getString();
		if (bestId == null) {
			return new GeneralEntry(label, "–", ItemStack.EMPTY);
		}
		String name = ellipsize(name(bestId, kind), 150);
		return new GeneralEntry(label, name + " · " + Fmt.whole(best), icon(bestId, kind), bestId.startsWith("~"));
	}

	private List<String> columnKeys() {
		return switch (category) {
			case BLOCKS -> List.of("loothub.stats.col.mined", "loothub.stats.col.placed", "loothub.stats.col.picked");
			case PLANTS -> List.of("loothub.stats.col.harvested", "loothub.stats.col.planted", "loothub.stats.col.picked");
			case FOOD -> List.of("loothub.stats.col.harvested", "loothub.stats.col.planted", "loothub.stats.col.picked", "loothub.stats.col.eaten");
			case ITEMS -> List.of("loothub.stats.col.picked", "loothub.stats.col.used");
			case TOOLS -> List.of("loothub.stats.col.tool_mined", "loothub.stats.col.tool_hits", "loothub.stats.col.tool_used",
					"loothub.stats.col.picked");
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
		g.fill(tableX + 1, tableY + 14, tableX + tableW - 1, tableY + 15, de.loothub.client.Theme.bg(BORDER));

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
			} else if (row.id().startsWith("~")) {
				serverIcon(g, tableX + 4, ry + 1); // Server-Mob/-Item ohne Minecraft-Symbol: Loothub-Logo
			}
			int nameMax = columnRight(0, cols.size()) - 90 - (tableX + 24);
			g.drawString(font, ellipsize(row.name(), nameMax), tableX + 24, ry + 5, TEXT, false);
			for (int c = 0; c < cols.size(); c++) {
				long v = row.values()[c];
				if (v < 0) {
					continue; // Spalte gilt für diesen Gegenstand nicht
				}
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
		if (id.startsWith("~")) {
			return id.substring(1); // Server-Mob mit eigenem Namen
		}
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
					case PLANT -> {
						Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
						if (item != null && item != Items.AIR) {
							return new ItemStack(item).getHoverName().getString();
						}
						Block b = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
						if (b != null && b != net.minecraft.world.level.block.Blocks.AIR) {
							return b.getName().getString();
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

	private static final net.minecraft.resources.Identifier SERVER_ICON =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "icon.png");

	/** Loothub-Logo als Symbol für Server-Mobs und -Items (die haben kein Minecraft-Symbol). */
	private static void serverIcon(GuiGraphics g, int x, int y) {
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, SERVER_ICON, x, y, 0f, 0f, 16, 16, 128, 128, 128, 128);
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
				case PLANT -> {
					Item direct = BuiltInRegistries.ITEM.getOptional(identifier).orElse(Items.AIR);
					yield direct != Items.AIR ? direct
							: BuiltInRegistries.BLOCK.getOptional(identifier).map(Block::asItem).orElse(Items.AIR);
				}
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
		g.fill(x, y, x + w, y + h, de.loothub.client.Theme.bg(PANEL));
		g.fill(x, y, x + w, y + 1, de.loothub.client.Theme.bg(BORDER));
		g.fill(x, y + h - 1, x + w, y + h, de.loothub.client.Theme.bg(BORDER));
		g.fill(x, y, x + 1, y + h, de.loothub.client.Theme.bg(BORDER));
		g.fill(x + w - 1, y, x + w, y + h, de.loothub.client.Theme.bg(BORDER));
	}
}
