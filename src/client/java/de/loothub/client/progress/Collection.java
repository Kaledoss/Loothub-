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
	/** Mobs ohne eigenen Erfolg (Boss bzw. im Überleben nicht anzutreffen). */
	private static final Set<String> NO_MOBS = Set.of("wither", "illusioner", "giant");
	/** Blöcke, die man im Überleben nicht sinnvoll abbaut. */
	private static final Set<String> NO_BLOCKS = Set.of("light", "structure_void", "barrier", "jigsaw", "structure_block",
			"command_block", "chain_command_block", "repeating_command_block", "test_block", "test_instance_block");

	/** Holzarten: davon zählen nur die Stämme. */
	private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
			"pale_oak", "crimson", "warped", "bamboo", "azalea");
	/** Arbeitsblöcke, Redstone-Bauteile und Ähnliches, die nicht in die Sammlung gehören. */
	private static final Set<String> NO_COLLECT = Set.of("farmland", "dirt_path", "pitcher_pod", "crafting_table", "smithing_table",
			"fletching_table", "cartography_table", "loom", "stonecutter", "grindstone", "enchanting_table", "anvil", "chipped_anvil",
			"damaged_anvil", "brewing_stand", "furnace", "blast_furnace", "smoker", "composter", "barrel", "lectern", "cauldron",
			"crafter", "observer", "piston", "sticky_piston", "dispenser", "dropper", "hopper", "comparator", "repeater", "redstone",
			"redstone_torch", "redstone_lamp", "daylight_detector", "target", "lever", "tripwire_hook", "string", "note_block",
			"rail", "powered_rail", "detector_rail", "activator_rail", "trapped_chest", "short_grass", "tall_grass", "fern",
			"large_fern", "seagrass", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
			"lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "torchflower", "pitcher_plant",
			"pink_petals", "spore_blossom", "closed_eyeblossom", "open_eyeblossom", "wildflowers", "cactus_flower", "chorus_flower",
			"mangrove_propagule", "flower_pot", "mossy_cobblestone", "deepslate_tiles", "cracked_deepslate_tiles");

	/** Gehört dieser Block nicht in die Sammlung (Treppen, Stufen, Ziegel, Holzbauteile, Blumen, Glas, Wolle …)? */
	static boolean excluded(String id) {
		if (NO_COLLECT.contains(id) || id.endsWith("_seeds") || id.endsWith("_stairs") || id.endsWith("_slab")
				|| id.endsWith("_wall") || id.contains("brick") || id.startsWith("infested_") || id.endsWith("_sapling")
				|| id.endsWith("_leaves") || id.contains("glass") || id.endsWith("_wool") || id.endsWith("_carpet")
				|| id.endsWith("_tulip") || id.startsWith("polished_") || id.startsWith("chiseled_") || id.startsWith("cracked_")
				|| id.startsWith("smooth_") || id.startsWith("cut_") || id.startsWith("stripped_")) {
			return true;
		}
		// Betten, Laternen, Köpfe, Banner, Lagerfeuer, Glocken, Kerzen, Kisten, Eimer, Harz, Ketten, glasierte Keramik
		if (id.endsWith("_bed") || (id.contains("lantern") && !id.equals("sea_lantern")) || id.endsWith("_head") || id.endsWith("_skull") || id.endsWith("_banner")
				|| id.endsWith("campfire") || id.equals("bell") || id.equals("candle") || id.endsWith("_candle") || id.endsWith("chest")
				|| id.endsWith("shulker_box") || id.endsWith("_bucket") || id.equals("resin_clump") || id.equals("chain")
				|| id.endsWith("_chain") || id.equals("lightning_rod") || id.contains("glazed_terracotta")) {
			return true;
		}
		// Kupfer: nur die Erze zählen
		if (id.contains("copper") && !id.endsWith("copper_ore")) {
			return true;
		}
		// Tiefenschiefer: nur der normale, der Bruchstein und die Erze
		if (id.contains("deepslate") && !id.equals("deepslate") && !id.equals("cobbled_deepslate") && !id.endsWith("_ore")) {
			return true;
		}
		// Holz: nur die Stämme (Bambusrohr selbst bleibt)
		for (String w : WOODS) {
			if (id.startsWith(w + "_") && !id.endsWith("_log") && !id.endsWith("_stem") && !id.endsWith("_nylium")
					&& !id.equals("bamboo_block")) {
				return true;
			}
		}
		return id.equals("azalea") || id.equals("flowering_azalea");
	}

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
				if (excluded(id.getPath()) || excluded(key)) {
					continue;
				}
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
				if (!"minecraft".equals(id.getNamespace()) || NO_MOBS.contains(path)
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
				String mob = k.substring("killed.minecraft:".length()); // „killed.~Name“ = Custom-Mob, zählt nicht
				if (!NO_MOBS.contains(mob)) {
					m.merge(mob, v, Long::sum);
				}
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
