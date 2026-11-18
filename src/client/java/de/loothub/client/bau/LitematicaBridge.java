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
			// Rahmen selbst aus den Teilbereichen bilden: Litematicas eigener Rahmen existiert nur, wenn er angezeigt wird
			int[] b = null;
			for (Box box : p.getSubRegionBoxes(RequiredEnabled.ANY).values()) {
				BlockPos a = box.getPos1(), c = box.getPos2();
				if (a == null || c == null) {
					continue;
				}
				int[] r = {Math.min(a.getX(), c.getX()), Math.min(a.getY(), c.getY()), Math.min(a.getZ(), c.getZ()),
						Math.max(a.getX(), c.getX()), Math.max(a.getY(), c.getY()), Math.max(a.getZ(), c.getZ())};
				if (b == null) {
					b = r;
				} else {
					for (int i = 0; i < 3; i++) {
						b[i] = Math.min(b[i], r[i]);
						b[i + 3] = Math.max(b[i + 3], r[i + 3]);
					}
				}
			}
			if (b == null) {
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
			plan.setBox(b[0], b[1], b[2], b[3], b[4], b[5]);
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

	// ------------------------------------------------------------------ Litematica-Printer (über Reflexion, optional)

	private static final String PRINTER = "me.aleksilassila.litematica.printer.config.";
	private static Object printMode, printKey, printRange;
	private static boolean printerLooked;

	private static void lookupPrinter() {
		if (printerLooked) {
			return;
		}
		printerLooked = true;
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica_printer")) {
			return;
		}
		try {
			Class<?> c = Class.forName(PRINTER + "Configs");
			printMode = c.getField("PRINT_MODE").get(null);
			try {
				printRange = c.getField("PRINTING_RANGE").get(null);
			} catch (ReflectiveOperationException ignored) {
				// ältere/andere Fassung ohne Reichweite
			}
			printKey = Class.forName(PRINTER + "Hotkeys").getField("PRINT").get(null);
		} catch (ReflectiveOperationException | LinkageError e) {
			Loothub.LOGGER.info("Litematica-Printer nicht erkannt – Fehlt-Warnung nur für Easy Place");
		}
	}

	/** Druckt der Printer gerade (Druckmodus an oder Druck-Taste gehalten)? */
	static boolean printerActive() {
		lookupPrinter();
		if (printMode instanceof fi.dy.masa.malilib.config.options.ConfigBoolean b && b.getBooleanValue()) {
			return true;
		}
		return printKey instanceof fi.dy.masa.malilib.config.options.ConfigHotkey k && k.getKeybind().isKeybindHeld();
	}

	/** Reichweite des Printers (Blöcke). */
	static double printerRange() {
		lookupPrinter();
		return printRange instanceof fi.dy.masa.malilib.config.options.ConfigDouble d ? d.getDoubleValue() : 5.0;
	}

	/**
	 * Blöcke in Reichweite, die laut Schematic fehlen (an der Stelle ist noch Luft o. Ä.) – die dafür nötigen Items,
	 * jeweils einmal. Der Printer überspringt solche Blöcke still, wenn das Item nicht im Inventar ist.
	 */
	static List<ItemStack> neededAround(Minecraft mc, double range) {
		List<ItemStack> out = new ArrayList<>();
		Level world = SchematicWorldHandler.getSchematicWorld();
		if (world == null || mc.level == null || mc.player == null) {
			return out;
		}
		java.util.Set<net.minecraft.world.item.Item> seen = new java.util.HashSet<>();
		BlockPos center = mc.player.blockPosition();
		int r = (int) Math.ceil(range);
		double r2 = range * range;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		var eye = mc.player.getEyePosition();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					pos.set(center.getX() + dx, center.getY() + 1 + dy, center.getZ() + dz);
					if (eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > r2
							|| !world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
						continue;
					}
					BlockState want = world.getBlockState(pos);
					if (want.isAir() || want.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
						continue; // Wasser/Lava setzt der Printer nicht aus Blöcken
					}
					BlockState have = mc.level.getBlockState(pos);
					if (have == want || !have.canBeReplaced()) {
						continue;
					}
					ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(want, world, pos);
					if (stack != null && !stack.isEmpty() && seen.add(stack.getItem())) {
						out.add(stack);
					}
				}
			}
		}
		return out;
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

	// ------------------------------------------------------------------ Rezepte

	/** Alle Rezepte im Rezeptbuch des Spielers (über MaLiLibs Zugriff auf die interne Liste). */
	static java.util.Collection<net.minecraft.world.item.crafting.display.RecipeDisplayEntry> knownRecipes() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return List.of();
		}
		return ((fi.dy.masa.malilib.mixin.recipe.IMixinClientRecipeBook) mc.player.getRecipeBook()).malilib_getRecipeMap()
				.values();
	}

	// ------------------------------------------------------------------ 3D-Ansicht

	/**
	 * Alle sichtbaren Blöcke der Schematic (ganz von Blöcken umschlossene weggelassen) mit dem Item, das sie darstellt –
	 * Koordinaten relativ zur Schematic, ohne Drehung der Platzierung. Höchstens {@code max} Blöcke.
	 */
	static List<Preview.Block> previewBlocks(Plan plan, int max) {
		List<Preview.Block> out = new ArrayList<>();
		if (!(plan.handle instanceof SchematicPlacement p)) {
			return out;
		}
		LitematicaSchematic schematic = p.getSchematic();
		Map<Long, BlockState> all = new HashMap<>();
		for (String region : schematic.getAreaSizes().keySet()) {
			var container = schematic.getSubRegionContainer(region);
			BlockPos pos = schematic.getSubRegionPosition(region);
			BlockPos size = schematic.getAreaSize(region);
			if (container == null || pos == null || size == null) {
				continue;
			}
			// negative Größe = Bereich geht von pos aus in die andere Richtung
			int ox = pos.getX() + Math.min(0, size.getX() + 1);
			int oy = pos.getY() + Math.min(0, size.getY() + 1);
			int oz = pos.getZ() + Math.min(0, size.getZ() + 1);
			var dim = container.getSize();
			for (int y = 0; y < dim.getY(); y++) {
				for (int z = 0; z < dim.getZ(); z++) {
					for (int x = 0; x < dim.getX(); x++) {
						BlockState st = container.get(x, y, z);
						if (st != null && !st.isAir()) {
							all.put(BlockPos.asLong(ox + x, oy + y, oz + z), st);
						}
					}
				}
			}
		}
		for (var e : all.entrySet()) {
			long key = e.getKey();
			int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
			// ganz umschlossen: unsichtbar, nicht zeichnen
			if (all.containsKey(BlockPos.asLong(x + 1, y, z)) && all.containsKey(BlockPos.asLong(x - 1, y, z))
					&& all.containsKey(BlockPos.asLong(x, y + 1, z)) && all.containsKey(BlockPos.asLong(x, y - 1, z))
					&& all.containsKey(BlockPos.asLong(x, y, z + 1)) && all.containsKey(BlockPos.asLong(x, y, z - 1))) {
				continue;
			}
			BlockState st = e.getValue();
			out.add(new Preview.Block(x, y, z, st, st.canOcclude()));
			if (out.size() >= max) {
				break;
			}
		}
		return out;
	}

	/** Eigenes Zeichen-Element in die GUI geben (MaLiLib kommt an die interne Liste von GuiGraphics). */
	static void submit(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.gui.render.state.GuiElementRenderState e) {
		fi.dy.masa.malilib.render.GuiContext.fromGuiGraphics(g).addSimpleElement(e);
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
