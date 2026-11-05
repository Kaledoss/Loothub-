package de.loothub.client.itemdex;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.equipment.Equippable;

import de.loothub.Loothub;

/**
 * Itemdex: sammelt nach und nach alle Server-eigenen Items (eigener Name + Beschreibung, eigenes Modell
 * oder Plugin-Daten), die dem Spieler begegnen – im Inventar, in Kisten und Menüs (auch Auktionshaus),
 * im Chat (Item-Vorschau), am Boden, in Rahmen und bei anderen Spielern. Nur Mitlesen, keine Aktionen.
 */
public final class Itemdex {
	public enum Category {
		WEAPON("loothub.itemdex.cat.weapon"),
		TOOL("loothub.itemdex.cat.tool"),
		ARMOR("loothub.itemdex.cat.armor"),
		COSMETIC("loothub.itemdex.cat.cosmetic"),
		FOOD("loothub.itemdex.cat.food"),
		CONSUMABLE("loothub.itemdex.cat.consumable"),
		BLOCK("loothub.itemdex.cat.block"),
		OTHER("loothub.itemdex.cat.other");

		public final String key;

		Category(String key) {
			this.key = key;
		}
	}

	/** Wo ein Item gesehen wurde; höhere Priorität ersetzt die gespeicherte Ansicht (Inventar ist am „saubersten“). */
	public enum Source {
		CONTAINER(1), CHAT(2), WORLD(2), INVENTORY(3);

		final int priority;

		Source(int priority) {
			this.priority = priority;
		}
	}

	public static final class Entry {
		public int number;
		public String key;
		public String itemId;
		public String name;
		public String text; // Name + Beschreibung als reiner Text (für die Suche)
		public Category category;
		public long firstSeen;
		public String where;
		public Source source;
		public boolean hidden;
		JsonElement stackJson;
		private ItemStack cached;

		/** Das Item so, wie es gesehen wurde (für Symbol und Tooltip); leer, wenn nicht ladbar. */
		public ItemStack stack() {
			if (cached != null) {
				return cached;
			}
			HolderLookup.Provider regs = registries();
			ItemStack fallback = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(itemId))
					.map(ItemStack::new).orElse(ItemStack.EMPTY);
			if (stackJson == null) {
				return cached = fallback;
			}
			if (regs == null) {
				return fallback; // ohne Welt nicht ladbar – später erneut versuchen
			}
			cached = ItemStack.CODEC.parse(regs.createSerializationContext(JsonOps.INSTANCE), stackJson)
					.result().orElse(fallback);
			return cached;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static List<Entry> entries;
	private static Map<String, Entry> byKey;
	private static boolean dirty;
	private static long lastSave;
	private static int ticks;
	private static volatile int version;

	private Itemdex() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(Itemdex::tick);
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> scanChat(message));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> scanChat(message));
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> save(true));
	}

	public static synchronized List<Entry> entries() {
		load();
		return List.copyOf(entries);
	}

	public static int version() {
		return version;
	}

	public static synchronized void setHidden(Entry e, boolean hidden) {
		e.hidden = hidden;
		dirty = true;
		version++;
	}

	// ------------------------------------------------------------------ Erkennen

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		ticks++;
		if (ticks % 20 == 0) {
			var inv = mc.player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				see(inv.getItem(i), Source.INVENTORY, "Inventar");
			}
		}
		if (ticks % 10 == 5 && mc.screen instanceof AbstractContainerScreen<?> screen
				&& !(screen instanceof InventoryScreen) && !(screen instanceof CreativeModeInventoryScreen)) {
			String where = screen.getTitle().getString().strip();
			for (Slot slot : screen.getMenu().slots) {
				if (slot.container != mc.player.getInventory()) {
					see(slot.getItem(), Source.CONTAINER, where.isEmpty() ? "Menü" : where);
				}
			}
		}
		if (ticks % 40 == 10) {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e == mc.player) {
					continue;
				}
				if (e instanceof ItemEntity item) {
					see(item.getItem(), Source.WORLD, "Am Boden");
				} else if (e instanceof ItemFrame frame) {
					see(frame.getItem(), Source.WORLD, "Im Rahmen");
				} else if (e instanceof LivingEntity living) {
					String who = e.getName().getString();
					for (EquipmentSlot slot : EquipmentSlot.values()) {
						see(living.getItemBySlot(slot), Source.WORLD, "Bei " + who);
					}
				}
			}
		}
		if (ticks % 200 == 0) {
			save(false);
		}
	}

	private static void scanChat(Component message) {
		if (message == null) {
			return;
		}
		message.visit((Style style, String text) -> {
			if (style.getHoverEvent() instanceof HoverEvent.ShowItem show) {
				see(show.item(), Source.CHAT, "Chat");
			}
			return Optional.empty();
		}, Style.EMPTY);
	}

	/** Ist das ein Server-eigenes Item? */
	static boolean isCustom(ItemStack s) {
		if (s == null || s.isEmpty()) {
			return false;
		}
		String name = s.getHoverName().getString().strip();
		if (name.isEmpty()) {
			return false; // Platzhalter in Menüs
		}
		boolean modelChanged = s.has(DataComponents.ITEM_MODEL)
				&& !Objects.equals(s.get(DataComponents.ITEM_MODEL), s.getItem().components().get(DataComponents.ITEM_MODEL));
		boolean data = s.has(DataComponents.CUSTOM_DATA);
		ItemLore lore = s.get(DataComponents.LORE);
		boolean hasLore = lore != null && !lore.lines().isEmpty();
		return modelChanged || data || (s.has(DataComponents.CUSTOM_NAME) && hasLore);
	}

	static Category categorize(ItemStack s) {
		if (s.is(ItemTags.SWORDS) || s.getItem() instanceof BowItem || s.getItem() instanceof CrossbowItem
				|| s.getItem() instanceof TridentItem || s.getItem() instanceof MaceItem) {
			return Category.WEAPON;
		}
		if (s.is(ItemTags.PICKAXES) || s.is(ItemTags.AXES) || s.is(ItemTags.SHOVELS) || s.is(ItemTags.HOES)
				|| s.getItem() instanceof ShearsItem || s.getItem() instanceof FishingRodItem
				|| s.getItem() instanceof BrushItem || s.getItem() instanceof FlintAndSteelItem) {
			return Category.TOOL;
		}
		Equippable eq = s.get(DataComponents.EQUIPPABLE);
		if (eq != null) {
			return eq.slot() == EquipmentSlot.HEAD && !s.is(ItemTags.HEAD_ARMOR) ? Category.COSMETIC : Category.ARMOR;
		}
		if (s.has(DataComponents.FOOD)) {
			return Category.FOOD;
		}
		if (s.has(DataComponents.CONSUMABLE)) {
			return Category.CONSUMABLE;
		}
		if (s.getItem() instanceof BlockItem) {
			return Category.BLOCK;
		}
		return Category.OTHER;
	}

	private static String key(ItemStack s) {
		Object model = s.get(DataComponents.ITEM_MODEL);
		return BuiltInRegistries.ITEM.getKey(s.getItem()) + "|" + (model == null ? "" : model) + "|"
				+ s.getHoverName().getString().strip().toLowerCase(Locale.ROOT);
	}

	private static synchronized void see(ItemStack s, Source source, String where) {
		if (!de.loothub.client.jobs.WidgetConfig.get().itemdexEnabled || !isCustom(s)) {
			return;
		}
		load();
		String key = key(s);
		Entry e = byKey.get(key);
		if (e != null && e.source != null && e.source.priority >= source.priority) {
			return;
		}
		HolderLookup.Provider regs = registries();
		JsonElement json = null;
		if (regs != null) {
			json = ItemStack.CODEC.encodeStart(regs.createSerializationContext(JsonOps.INSTANCE), s.copyWithCount(1))
					.result().orElse(null);
		}
		if (e == null) {
			e = new Entry();
			e.number = entries.size() + 1;
			e.key = key;
			e.firstSeen = System.currentTimeMillis();
			e.where = where;
			entries.add(e);
			byKey.put(key, e);
		}
		e.itemId = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
		e.name = s.getHoverName().getString().strip();
		StringBuilder text = new StringBuilder(e.name);
		ItemLore lore = s.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) {
				text.append('\n').append(line.getString());
			}
		}
		e.text = text.toString().toLowerCase(Locale.ROOT);
		e.category = categorize(s);
		e.source = source;
		if (json != null) {
			e.stackJson = json;
		}
		e.cached = null;
		dirty = true;
		version++;
	}

	private static HolderLookup.Provider registries() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null ? mc.level.registryAccess() : null;
	}

	// ------------------------------------------------------------------ Speichern

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-itemdex.json");
	}

	private static synchronized void load() {
		if (entries != null) {
			return;
		}
		entries = new ArrayList<>();
		byKey = new HashMap<>();
		Path p = file();
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(r);
			if (root == null || !root.isJsonArray()) {
				return;
			}
			for (JsonElement el : root.getAsJsonArray()) {
				JsonObject o = el.getAsJsonObject();
				Entry e = new Entry();
				e.number = o.get("number").getAsInt();
				e.key = o.get("key").getAsString();
				e.itemId = o.get("item").getAsString();
				e.name = o.get("name").getAsString();
				e.text = o.has("text") ? o.get("text").getAsString() : e.name.toLowerCase(Locale.ROOT);
				e.category = parseEnum(Category.class, o, "category", Category.OTHER);
				e.firstSeen = o.has("firstSeen") ? o.get("firstSeen").getAsLong() : 0;
				e.where = o.has("where") ? o.get("where").getAsString() : "";
				e.source = parseEnum(Source.class, o, "source", Source.CONTAINER);
				e.hidden = o.has("hidden") && o.get("hidden").getAsBoolean();
				e.stackJson = o.get("stack");
				entries.add(e);
				byKey.put(e.key, e);
			}
			entries.sort((a, b) -> Integer.compare(a.number, b.number));
		} catch (IOException | RuntimeException ex) {
			Loothub.LOGGER.warn("Itemdex konnte nicht gelesen werden", ex);
		}
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, JsonObject o, String field, E fallback) {
		try {
			return o.has(field) ? Enum.valueOf(type, o.get(field).getAsString()) : fallback;
		} catch (IllegalArgumentException ex) {
			return fallback;
		}
	}

	private static synchronized void save(boolean force) {
		if (!dirty || entries == null || (!force && System.currentTimeMillis() - lastSave < 10_000)) {
			return;
		}
		JsonArray arr = new JsonArray();
		for (Entry e : entries) {
			JsonObject o = new JsonObject();
			o.addProperty("number", e.number);
			o.addProperty("key", e.key);
			o.addProperty("item", e.itemId);
			o.addProperty("name", e.name);
			o.addProperty("text", e.text);
			o.addProperty("category", e.category.name());
			o.addProperty("firstSeen", e.firstSeen);
			o.addProperty("where", e.where);
			o.addProperty("source", e.source.name());
			if (e.hidden) {
				o.addProperty("hidden", true);
			}
			if (e.stackJson != null) {
				o.add("stack", e.stackJson);
			}
			arr.add(o);
		}
		try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			GSON.toJson(arr, w);
			dirty = false;
			lastSave = System.currentTimeMillis();
		} catch (IOException ex) {
			Loothub.LOGGER.warn("Itemdex konnte nicht gespeichert werden", ex);
		}
	}
}
