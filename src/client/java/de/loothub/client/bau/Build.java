package de.loothub.client.bau;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Bau-Funktionen rund um Litematica: platzierte Schematics, Fortschritt im Bereich, Warnung, wenn Easy Place einen
 * Block nicht im Inventar findet. Alles nur Anzeige – gesetzt wird nichts. Ohne Litematica ist alles aus.
 */
public final class Build {
	/** Rand um die Schematic, in dem der Fortschritt angezeigt wird (Blöcke). */
	public static final int AREA = 16;
	/** Positionen, die pro Tick verglichen werden (verteilt die Arbeit, damit nichts ruckelt). */
	private static final int BUDGET = 6000;

	private static Boolean present;
	private static final Map<String, Plan> PLANS = new LinkedHashMap<>();
	private static int refreshIn;

	private Build() {
	}

	/** Ist Litematica installiert? */
	public static boolean present() {
		if (present == null) {
			present = FabricLoader.getInstance().isModLoaded("litematica");
		}
		return present;
	}

	public static void register() {
		if (!present()) {
			return;
		}
		BuildStorage.register();
		ClientTickEvents.END_CLIENT_TICK.register(Build::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			PLANS.clear();
			scan = null;
			return;
		}
		try {
			if (--refreshIn <= 0) {
				refreshIn = 40;
				refresh();
			}
			progressTick(mc);
			EasyPlaceWarning.tick(mc);
			Share.tick(mc);
		} catch (RuntimeException | LinkageError e) {
			// eine andere Litematica-Version mit geänderten Schnittstellen: Bau-Funktionen still abschalten
			Loothub.LOGGER.warn("Bau-Funktionen abgeschaltet (Litematica-Version passt nicht?)", e);
			present = false;
		}
	}

	private static void refresh() {
		List<Plan> now = LitematicaBridge.plans(PLANS);
		PLANS.clear();
		for (Plan p : now) {
			PLANS.put(p.id, p);
		}
	}

	/** Alle platzierten Schematics (Stand der letzten Abfrage). */
	public static List<Plan> plans() {
		return present() ? new ArrayList<>(PLANS.values()) : List.of();
	}

	public static Plan plan(String id) {
		return PLANS.get(id);
	}

	/** Gleich neu einlesen (z. B. nach dem Annehmen einer geteilten Schematic). */
	static void refreshSoon() {
		refreshIn = 1;
	}

	/** Schematic, in deren Bereich (Rahmen + {@link #AREA}) der Spieler gerade steht – die nächste; sonst null. */
	public static Plan here() {
		Minecraft mc = Minecraft.getInstance();
		if (!present() || mc.player == null) {
			return null;
		}
		Plan best = null;
		double bestD = AREA;
		for (Plan p : PLANS.values()) {
			if (!p.enabled) {
				continue;
			}
			double d = p.distance(mc.player.getX(), mc.player.getY(), mc.player.getZ());
			if (d <= bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ Fortschritt

	/** Laufender Vergleich: welche Schematic, welcher Chunk, wie weit. */
	private static final class Scan {
		final Plan plan;
		final List<int[]> chunks;
		int chunkIndex;
		List<int[]> boxes;
		int boxIndex;
		int x, y, z;
		int[] counts;

		Scan(Plan plan, List<int[]> chunks) {
			this.plan = plan;
			this.chunks = chunks;
		}
	}

	private static Scan scan;
	private static long lastTickAt;

	private static void progressTick(Minecraft mc) {
		Plan here = here();
		long now = System.currentTimeMillis();
		long dt = lastTickAt == 0 ? 0 : Math.min(1000, now - lastTickAt);
		lastTickAt = now;
		if (here == null || !WidgetConfig.get().buildHud) {
			scan = null;
			return;
		}
		Day day = day(here.id);
		day.activeMs += dt;
		save();
		if (scan == null || scan.plan != here) {
			List<int[]> chunks = LitematicaBridge.chunks(here);
			here.chunkTotal = chunks.size();
			scan = new Scan(here, chunks);
		}
		int budget = BUDGET;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (budget > 0) {
			Scan s = scan;
			if (s.chunks.isEmpty()) {
				return;
			}
			if (s.boxes == null) {
				if (s.chunkIndex >= s.chunks.size()) {
					// Durchgang fertig: von vorn (Blöcke werden ja laufend gesetzt)
					finishPass(here);
					List<int[]> chunks = LitematicaBridge.chunks(here);
					here.chunkTotal = chunks.size();
					scan = new Scan(here, chunks);
					return;
				}
				int[] c = s.chunks.get(s.chunkIndex);
				if (!mc.level.hasChunk(c[0], c[1]) || !LitematicaBridge.schematicChunkLoaded(c[0], c[1])) {
					s.chunkIndex++; // nicht geladen: alten Stand behalten
					budget -= 16;
					continue;
				}
				s.boxes = LitematicaBridge.boxesInChunk(here, c[0], c[1]);
				s.boxIndex = 0;
				s.counts = new int[3];
				s.x = Integer.MIN_VALUE;
			}
			if (s.boxIndex >= s.boxes.size()) {
				int[] c = s.chunks.get(s.chunkIndex);
				here.chunkCounts.put(((long) c[0] << 32) | (c[1] & 0xFFFFFFFFL), s.counts);
				here.sumUp();
				s.boxes = null;
				s.chunkIndex++;
				continue;
			}
			int[] b = s.boxes.get(s.boxIndex);
			if (s.x == Integer.MIN_VALUE) {
				s.x = b[0];
				s.y = b[1];
				s.z = b[2];
			}
			// Kasten zeilenweise abarbeiten, bis das Budget aufgebraucht ist
			while (budget > 0) {
				pos.set(s.x, s.y, s.z);
				BlockState want = LitematicaBridge.schematicState(pos);
				if (want != null && !want.isAir()) {
					BlockState have = mc.level.getBlockState(pos);
					s.counts[0]++;
					if (have.getBlock() == want.getBlock()) {
						s.counts[1]++;
					} else if (!have.isAir() && !have.canBeReplaced()) {
						s.counts[2]++;
					}
				}
				budget--;
				if (++s.x > b[3]) {
					s.x = b[0];
					if (++s.z > b[5]) {
						s.z = b[2];
						if (++s.y > b[4]) {
							s.boxIndex++;
							s.x = Integer.MIN_VALUE;
							break;
						}
					}
				}
			}
		}
	}

	/** Nach einem vollen Durchgang: Tagesstart merken (für „heute +N“). */
	private static void finishPass(Plan plan) {
		if (!plan.fullyScanned()) {
			return;
		}
		Day d = day(plan.id);
		if (d.base < 0) {
			d.base = plan.correct;
			save();
		}
	}

	/** Heute gesetzte Blöcke (seit dem ersten vollen Durchgang des Tages); -1 = noch unbekannt. */
	public static long gainedToday(Plan plan) {
		Day d = day(plan.id);
		return d.base < 0 ? -1 : Math.max(0, plan.correct - d.base);
	}

	/** Geschätzte Restzeit nach dem heutigen Bautempo (ms); -1 = noch zu wenig Daten. */
	public static long remainingMs(Plan plan) {
		long gained = gainedToday(plan);
		Day d = day(plan.id);
		if (gained < 20 || d.activeMs < 5 * 60_000L) {
			return -1;
		}
		long left = Math.max(0, plan.totalBlocks() - plan.correct);
		return (long) (left * (double) d.activeMs / gained);
	}

	// ------------------------------------------------------------------ Tageswerte (config/loothub-bau.json)

	private static final class Day {
		String date;
		long base = -1;
		long activeMs;
	}

	private static Map<String, Day> days;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-bau.json");
	}

	private static Day day(String id) {
		if (days == null) {
			load();
		}
		String today = LocalDate.now().toString();
		Day d = days.computeIfAbsent(id, k -> new Day());
		if (!today.equals(d.date)) {
			d.date = today;
			d.base = -1;
			d.activeMs = 0;
		}
		return d;
	}

	private static void load() {
		days = new HashMap<>();
		try {
			Path p = file();
			if (!Files.exists(p)) {
				return;
			}
			JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject prog = root.has("progress") ? root.getAsJsonObject("progress") : new JsonObject();
			for (var e : prog.entrySet()) {
				JsonObject o = e.getValue().getAsJsonObject();
				Day d = new Day();
				d.date = o.get("day").getAsString();
				d.base = o.get("base").getAsLong();
				d.activeMs = o.get("active").getAsLong();
				days.put(e.getKey(), d);
			}
		} catch (Exception e) {
			Loothub.LOGGER.debug("Bau-Daten nicht lesbar", e);
		}
	}

	private static long lastSave;

	/** Tageswerte speichern (höchstens alle 30 s; der Rest beim nächsten Mal). */
	static void save() {
		long now = System.currentTimeMillis();
		if (days == null || now - lastSave < 30_000) {
			return;
		}
		lastSave = now;
		JsonObject prog = new JsonObject();
		days.forEach((id, d) -> {
			JsonObject o = new JsonObject();
			o.addProperty("day", d.date);
			o.addProperty("base", d.base);
			o.addProperty("active", d.activeMs);
			prog.add(id, o);
		});
		JsonObject root = new JsonObject();
		root.add("progress", prog);
		try {
			Files.writeString(file(), root.toString(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			Loothub.LOGGER.debug("Bau-Daten nicht gespeichert", e);
		}
	}

	// ------------------------------------------------------------------ Inventar

	/** Items im Inventar, Shulkerkisten darin mitgezählt (Item-ID → Anzahl). */
	public static Map<String, Long> inventory() {
		Map<String, Long> out = new HashMap<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return out;
		}
		var inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			add(out, inv.getItem(i));
		}
		return out;
	}

	/** Ein Stack samt Inhalt (Shulkerkiste) zählen. */
	static void add(Map<String, Long> out, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		out.merge(id(stack.getItem()), (long) stack.getCount(), Long::sum);
		if (present()) {
			for (ItemStack inner : LitematicaBridge.shulkerContents(stack)) {
				if (!inner.isEmpty()) {
					out.merge(id(inner.getItem()), (long) inner.getCount() * stack.getCount(), Long::sum);
				}
			}
		}
	}

	static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	// ------------------------------------------------------------------ Litematica-Fenster (für den Bau-Reiter)

	public static void openMainMenu(net.minecraft.client.gui.screens.Screen parent) {
		LitematicaBridge.openMainMenu(parent);
	}

	public static void openLoad(net.minecraft.client.gui.screens.Screen parent) {
		LitematicaBridge.openLoad(parent);
	}

	public static void openPlacements(net.minecraft.client.gui.screens.Screen parent) {
		LitematicaBridge.openPlacements(parent);
	}

	public static void openPlacement(Plan plan, net.minecraft.client.gui.screens.Screen parent) {
		LitematicaBridge.openPlacement(plan, parent);
	}

	public static void openMaterialList(Plan plan, net.minecraft.client.gui.screens.Screen parent) {
		LitematicaBridge.openMaterialList(plan, parent);
	}

	/** „Was fehlt noch“ neu zählen lassen (Litematica vergleicht dabei Schematic und Welt). */
	public static void recount(Plan plan) {
		LitematicaBridge.recount(plan);
		refreshSoon();
	}
}
