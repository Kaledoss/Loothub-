package de.loothub.client.bau;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.InventoryUtils;

import de.loothub.Loothub;

/**
 * Die einzige Stelle, die Litematica direkt anspricht. Wird nur benutzt, wenn Litematica installiert ist
 * ({@link Build#present()}) – sonst wird diese Klasse nie geladen.
 */
final class LitematicaBridge {
	/** Gesamt-Materialien je Schematic (einmal im Hintergrund gezählt). */
	private static final Map<LitematicaSchematic, Map<String, Long>> TOTALS = new IdentityHashMap<>();
	private static final Map<LitematicaSchematic, Boolean> COUNTING = new IdentityHashMap<>();

	private LitematicaBridge() {
	}

	/** Alle platzierten Schematics als Loothub-Pläne (ohne Litematica-Typen nach außen). */
	static List<Plan> plans(Map<String, Plan> previous) {
		List<Plan> out = new ArrayList<>();
		for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
			Box box = p.getEclosingBox();
			if (box == null || box.getPos1() == null || box.getPos2() == null) {
				continue;
			}
			String id = p.getHashId().toString();
			Plan plan = previous.get(id);
			if (plan == null) {
				plan = new Plan(id);
			}
			plan.handle = p;
			plan.name = p.getName();
			Path file = p.getSchematicFile();
			plan.file = file == null ? "" : file.getFileName().toString();
			plan.enabled = p.isEnabled();
			BlockPos a = box.getPos1(), b = box.getPos2();
			plan.setBox(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
					Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
			plan.totals = totals(p.getSchematic());
			plan.missing = plan.countRequested ? missing(p) : null;
			out.add(plan);
		}
		return out;
	}

	/** Materialien der ganzen Schematic (Item-ID → Anzahl); null, solange noch gezählt wird. */
	private static synchronized Map<String, Long> totals(LitematicaSchematic schematic) {
		Map<String, Long> t = TOTALS.get(schematic);
		if (t != null || COUNTING.containsKey(schematic)) {
			return t;
		}
		COUNTING.put(schematic, Boolean.TRUE);
		CompletableFuture.runAsync(() -> {
			Map<String, Long> map = new HashMap<>();
			try {
				for (MaterialListEntry e : MaterialListUtils.createMaterialListFor(schematic)) {
					String id = itemId(e.getStack());
					if (id != null) {
						map.merge(id, (long) e.getCountTotal(), Long::sum);
					}
				}
			} catch (RuntimeException ex) {
				Loothub.LOGGER.warn("Materialliste der Schematic konnte nicht gezählt werden", ex);
			}
			synchronized (LitematicaBridge.class) {
				TOTALS.put(schematic, map);
				COUNTING.remove(schematic);
			}
		});
		return null;
	}

	/** Was laut Litematica-Materialliste dieser Platzierung noch fehlt (Item-ID → Anzahl); null = nicht gezählt. */
	private static Map<String, Long> missing(SchematicPlacement p) {
		MaterialListBase list = p.getMaterialList();
		List<MaterialListEntry> all = list.getMaterialsAll();
		if (all == null || all.isEmpty()) {
			return null;
		}
		Map<String, Long> map = new HashMap<>();
		for (MaterialListEntry e : all) {
			String id = itemId(e.getStack());
			if (id != null) {
				map.merge(id, (long) e.getCountMissing(), Long::sum);
			}
		}
		return map;
	}

	/** Litematica zählen lassen, was im Spiel schon steht (für „fehlt noch“). */
	static void recount(Plan plan) {
		if (plan.handle instanceof SchematicPlacement p) {
			if (plan.countRequested) {
				p.getMaterialList().reCreateMaterialList();
			} else {
				p.getMaterialList(); // legt die Liste an und zählt dabei
			}
			plan.countRequested = true;
		}
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	// ------------------------------------------------------------------ Litematica-Fenster

	static void openMainMenu(Screen parent) {
		open(new GuiMainMenu(), parent);
	}

	static void openLoad(Screen parent) {
		open(new GuiSchematicLoad(), parent);
	}

	static void openPlacements(Screen parent) {
		open(new GuiSchematicPlacementsList(), parent);
	}

	static void openPlacement(Plan plan, Screen parent) {
		if (plan.handle instanceof SchematicPlacement p) {
			open(new GuiPlacementConfiguration(p), parent);
		}
	}

	static void openMaterialList(Plan plan, Screen parent) {
		if (plan.handle instanceof SchematicPlacement p) {
			MaterialListBase list = p.getMaterialList();
			DataManager.setMaterialList(list);
			open(new GuiMaterialList(list), parent);
		}
	}

	private static void open(GuiBase gui, Screen parent) {
		gui.setParent(parent);
		GuiBase.openGui(gui);
	}

	// ------------------------------------------------------------------ Fortschritt

	/** Block der Schematic an dieser Stelle; null = kein Schematic-Chunk geladen. */
	static BlockState schematicState(BlockPos pos) {
		Level w = SchematicWorldHandler.getSchematicWorld();
		if (w == null || !w.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
			return null;
		}
		return w.getBlockState(pos);
	}

	/** Ist der Schematic-Chunk geladen (sonst wäre alles Luft)? */
	static boolean schematicChunkLoaded(int cx, int cz) {
		Level w = SchematicWorldHandler.getSchematicWorld();
		return w != null && w.hasChunk(cx, cz);
	}

	/** Teil-Kästen der Platzierung innerhalb eines Chunks: je {minX, minY, minZ, maxX, maxY, maxZ}. */
	static List<int[]> boxesInChunk(Plan plan, int cx, int cz) {
		List<int[]> out = new ArrayList<>();
		if (plan.handle instanceof SchematicPlacement p) {
			for (IntBoundingBox b : p.getBoxesWithinChunk(cx, cz, RequiredEnabled.PLACEMENT_ENABLED).values()) {
				out.add(new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()});
			}
		}
		return out;
	}

	/** Chunks, die die Platzierung berührt, als {cx, cz}. */
	static List<int[]> chunks(Plan plan) {
		List<int[]> out = new ArrayList<>();
		if (plan.handle instanceof SchematicPlacement p) {
			p.getTouchedChunks(RequiredEnabled.PLACEMENT_ENABLED).forEach(c -> out.add(new int[]{c.getMinBlockX() >> 4, c.getMinBlockZ() >> 4}));
		}
		return out;
	}

	// ------------------------------------------------------------------ Easy Place: fehlt der Block im Inventar?

	static boolean easyPlaceOn() {
		return Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
	}

	/** Block, auf den der Spieler in der Schematic zielt und der noch nicht steht – das dafür nötige Item, sonst leer. */
	static ItemStack targetedNeeded(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return ItemStack.EMPTY;
		}
		double range = mc.player.blockInteractionRange() + 1.0;
		RayTraceUtils.RayTraceWrapper trace = RayTraceUtils.getFurthestSchematicWorldTraceBeforeVanilla(mc.level, mc.player, range);
		if (trace == null || trace.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
			return ItemStack.EMPTY;
		}
		BlockPos pos = trace.getBlockHitResult().getBlockPos();
		Level world = SchematicWorldHandler.getSchematicWorld();
		if (world == null) {
			return ItemStack.EMPTY;
		}
		BlockState wanted = world.getBlockState(pos);
		if (wanted.isAir() || wanted == mc.level.getBlockState(pos)) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(wanted, world, pos);
		return stack == null ? ItemStack.EMPTY : stack;
	}

	/** Inhalt einer Shulkerkiste (leer, wenn keine bzw. leer). */
	static List<ItemStack> shulkerContents(ItemStack stack) {
		if (!InventoryUtils.shulkerBoxHasItems(stack)) {
			return List.of();
		}
		return InventoryUtils.getStoredItems(stack);
	}

	// ------------------------------------------------------------------ Gemeinsam bauen

	/** Platzierung zum Teilen: Einstellungen (ohne eigenen Dateipfad) und die Schematic-Datei. */
	static Share.Outgoing export(Plan plan) {
		if (!(plan.handle instanceof SchematicPlacement p)) {
			return null;
		}
		JsonObject json = p.toJson();
		Path file = p.getSchematicFile();
		if (json == null || file == null || !Files.isRegularFile(file)) {
			return null;
		}
		json.remove("schematic");
		json.remove("material_list");
		try {
			return new Share.Outgoing(plan.id, plan.name, json, Files.readAllBytes(file), file.getFileName().toString());
		} catch (java.io.IOException e) {
			Loothub.LOGGER.warn("Schematic-Datei nicht lesbar", e);
			return null;
		}
	}

	/** Geteilte Platzierung übernehmen bzw. aktualisieren (gleiche ID = gleiche Platzierung). */
	static boolean importShared(String id, JsonObject settings, Path schematicFile) {
		JsonObject json = settings.deepCopy();
		json.addProperty("schematic", schematicFile.toAbsolutePath().toString());
		json.addProperty("hash_code", id);
		if (!json.has("placements")) {
			json.add("placements", new JsonArray());
		}
		var manager = DataManager.getSchematicPlacementManager();
		for (SchematicPlacement old : new ArrayList<>(manager.getAllSchematicsPlacements())) {
			if (old.getHashId().toString().equals(id)) {
				manager.removeSchematicPlacement(old);
			}
		}
		SchematicPlacement placement = SchematicPlacement.fromJson(json);
		if (placement == null) {
			return false;
		}
		manager.addSchematicPlacement(placement, false);
		return true;
	}

	static void removeShared(String id) {
		var manager = DataManager.getSchematicPlacementManager();
		for (SchematicPlacement old : new ArrayList<>(manager.getAllSchematicsPlacements())) {
			if (old.getHashId().toString().equals(id)) {
				manager.removeSchematicPlacement(old);
			}
		}
	}

	/** Ordner für geteilte Schematics. */
	static Path sharedDir() {
		return DataManager.getSchematicsBaseDirectory().resolve("loothub-geteilt");
	}

	/** UUID gültig? (Litematica erwartet eine UUID als hash_code.) */
	static boolean validId(String id) {
		try {
			UUID.fromString(id);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
