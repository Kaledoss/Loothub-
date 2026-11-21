package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Sammlung: eigene Erfolge je abbaubarem Block und je Mob. Blöcke werden unter ihrem Item zusammengefasst (Wandfackel →
 * Fackel), Mobs mit eigenem Namen (Custom-Mobs des Servers) zählen nicht.
 */
final class Collection {
	private Collection() {
	}

	record Entry(String id, String name, ItemStack icon) {
	}

	/** Mobs, die Minecraft unter „Sonstiges“ führt, die man aber trotzdem besiegen kann. */
	private static final Set<String> EXTRA_MOBS = Set.of("iron_golem", "snow_golem", "villager", "wandering_trader",
			"copper_golem");
	/** Blöcke, die man im Überleben nicht sinnvoll abbaut. */
	private static final Set<String> NO_BLOCKS = Set.of("light", "structure_void", "barrier", "jigsaw", "structure_block",
			"command_block", "chain_command_block", "repeating_command_block", "test_block", "test_instance_block");

	private static List<Entry> blocks, mobs;
	/** Block-ID → Schlüssel der Sammlung (ID des Items). */
	private static final Map<String, String> BLOCK_KEY = new HashMap<>();

	static List<Entry> blocks() {
		if (blocks == null) {
			Map<String, Entry> out = new LinkedHashMap<>();
			for (Block b : BuiltInRegistries.BLOCK) {
				Identifier id = BuiltInRegistries.BLOCK.getKey(b);
				if (!"minecraft".equals(id.getNamespace()) || NO_BLOCKS.contains(id.getPath()) || b.defaultDestroyTime() < 0) {
					continue;
				}
				Item item = b.asItem();
				if (item == Items.AIR) {
					continue;
				}
				Identifier iid = BuiltInRegistries.ITEM.getKey(item);
				if (!"minecraft".equals(iid.getNamespace())) {
					continue;
				}
				String key = iid.getPath();
				BLOCK_KEY.put(id.getPath(), key);
				ItemStack stack = new ItemStack(item);
				out.putIfAbsent(key, new Entry(key, stack.getHoverName().getString(), stack));
			}
			blocks = sorted(out);
		}
		return blocks;
	}

	static List<Entry> mobs() {
		if (mobs == null) {
			Map<String, Entry> out = new LinkedHashMap<>();
			for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
				Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
				String path = id.getPath();
				if (!"minecraft".equals(id.getNamespace())
						|| (t.getCategory() == MobCategory.MISC && !EXTRA_MOBS.contains(path))) {
					continue;
				}
				Item egg = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("minecraft", path + "_spawn_egg"));
				ItemStack icon = egg == Items.AIR ? ItemStack.EMPTY : new ItemStack(egg);
				out.put(path, new Entry(path, t.getDescription().getString(), icon));
			}
			mobs = sorted(out);
		}
		return mobs;
	}

	private static List<Entry> sorted(Map<String, Entry> m) {
		List<Entry> l = new ArrayList<>(m.values());
		l.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		return l;
	}

	/** Zähler je Block und je Mob aus der eigenen Statistik („mined.minecraft:stone“, „killed.minecraft:zombie“). */
	static JsonObject counters(Map<String, Long> all) {
		blocks(); // füllt BLOCK_KEY
		Map<String, Long> b = new HashMap<>(), m = new HashMap<>();
		for (Map.Entry<String, Long> e : all.entrySet()) {
			String k = e.getKey();
			long v = e.getValue();
			if (v <= 0) {
				continue;
			}
			if (k.startsWith("mined.minecraft:")) {
				String key = BLOCK_KEY.get(k.substring("mined.minecraft:".length()));
				if (key != null) {
					b.merge(key, v, Long::sum);
				}
			} else if (k.startsWith("killed.minecraft:")) {
				m.merge(k.substring("killed.minecraft:".length()), v, Long::sum); // „killed.~Name“ = Custom-Mob, zählt nicht
			}
		}
		JsonObject o = new JsonObject(), ob = new JsonObject(), om = new JsonObject();
		b.forEach(ob::addProperty);
		m.forEach(om::addProperty);
		o.add("b", ob);
		o.add("m", om);
		return o;
	}

	/** Anzeigename zu einem Schlüssel (für Einblendungen). */
	static String name(String kind, String id) {
		for (Entry e : kind.equals("m") ? mobs() : blocks()) {
			if (e.id().equals(id)) {
				return e.name();
			}
		}
		return id;
	}

	static Component title(String kind) {
		return Component.translatable(kind.equals("m") ? "loothub.lh.ach.sub.mobs" : "loothub.lh.ach.sub.blocks");
	}
}
