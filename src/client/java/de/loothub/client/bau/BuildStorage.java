package de.loothub.client.bau;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Bau-Lager: Fässer, die „Loothub“ heißen (Lager für alle Schematics in der Nähe) oder wie eine Schematic (nur für
 * diese). Den Inhalt kennt die Mod erst, wenn man das Fass einmal geöffnet hat – danach merkt sie sich Ort, Name und
 * Inhalt und aktualisiert sie bei jedem Öffnen. Gilt im Umkreis von {@link #RADIUS} Blöcken um die Schematic.
 */
public final class BuildStorage {
	public static final int RADIUS = 200;
	public static final String GENERAL = "loothub";

	/** Ein gemerktes Fass. */
	public record Barrel(String dim, int x, int y, int z, String name, Map<String, Long> items, long at) {
	}

	private static Map<String, Barrel> barrels;
	/** Gerade offenes Fass (Ort und Name), Inhalt wird beim Schließen übernommen. */
	private static String openKey;
	private static Barrel openInfo;

	private BuildStorage() {
	}

	static void register() {
		// selbst abgebautes Lager-Fass vergessen
		net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			if (state.getBlock() instanceof BarrelBlock && load().remove(key(level.dimension().toString(), pos)) != null) {
				save();
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof AbstractContainerScreen<?> acs) || !(acs.getMenu() instanceof ChestMenu)) {
				return;
			}
			BlockPos pos = lookedAtBarrel(client);
			if (pos == null) {
				return;
			}
			String dim = client.level.dimension().toString();
			String key = key(dim, pos);
			// umbenannt? Standardname („Fass“) = kein Lager (mehr)
			boolean custom = !(screen.getTitle().getContents() instanceof TranslatableContents t
					&& t.getKey().startsWith("container."));
			String name = custom ? screen.getTitle().getString().strip() : "";
			if (!custom || !counts(name)) {
				if (load().remove(key) != null) {
					save();
				}
				return;
			}
			openKey = key;
			openInfo = new Barrel(dim, pos.getX(), pos.getY(), pos.getZ(), name, Map.of(), 0);
			ScreenEvents.remove(screen).register(s -> close(acs));
		});
	}

	/** Zählt ein Fass mit diesem Namen als Lager? „Loothub“ oder der Name einer platzierten Schematic. */
	private static boolean counts(String name) {
		if (name.equalsIgnoreCase(GENERAL)) {
			return true;
		}
		for (Plan p : Build.plans()) {
			if (p.name.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	private static void close(AbstractContainerScreen<?> screen) {
		if (openInfo == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Map<String, Long> items = new HashMap<>();
		for (Slot slot : screen.getMenu().slots) {
			if (mc.player != null && slot.container == mc.player.getInventory()) {
				continue;
			}
			Build.add(items, slot.getItem());
		}
		Barrel b = openInfo;
		load().put(openKey, new Barrel(b.dim(), b.x(), b.y(), b.z(), b.name(), items, System.currentTimeMillis()));
		openInfo = null;
		openKey = null;
		save();
	}

	private static BlockPos lookedAtBarrel(Minecraft mc) {
		if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		return mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof BarrelBlock ? hit.getBlockPos() : null;
	}

	private static String key(String dim, BlockPos pos) {
		return dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	// ------------------------------------------------------------------ Auswertung

	/** Lager einer Schematic: eigene Fässer und die allgemeinen („Loothub“) im Umkreis. */
	public record Stock(List<Barrel> own, List<Barrel> general) {
	}

	/** Fässer im Umkreis der Schematic, nach Art getrennt. */
	public static Stock stockFor(Plan plan) {
		Minecraft mc = Minecraft.getInstance();
		List<Barrel> own = new ArrayList<>(), general = new ArrayList<>();
		if (mc.level == null) {
			return new Stock(own, general);
		}
		String dim = mc.level.dimension().toString();
		boolean changed = false;
		for (var it = load().entrySet().iterator(); it.hasNext();) {
			Barrel b = it.next().getValue();
			if (!b.dim().equals(dim) || plan.distance(b.x() + 0.5, b.y() + 0.5, b.z() + 0.5) > RADIUS) {
				continue;
			}
			// Kein Löschen, nur weil dort gerade kein Fass steht: In anderen Welten (z. B. Farmwelt) gibt es dieselben
			// Koordinaten, aber ohne das Fass. Entfernt wird ein Lager nur, wenn man das Fass selbst abbaut.
			if (b.name().equalsIgnoreCase(plan.name)) {
				own.add(b);
			} else if (b.name().equalsIgnoreCase(GENERAL)) {
				general.add(b);
			}
		}
		if (changed) {
			save();
		}
		return new Stock(own, general);
	}

	/**
	 * Lagerbestand je Schematic, ohne doppelt zu zählen: Jede Schematic bekommt ihre eigenen Fässer; die allgemeinen
	 * („Loothub“) werden der Reihe nach verteilt – zuerst an die Schematic, die dem Spieler am nächsten ist.
	 */
	public static Map<String, Map<String, Long>> allocate(List<Plan> plans, Map<String, Map<String, Long>> needs) {
		Minecraft mc = Minecraft.getInstance();
		List<Plan> order = new ArrayList<>(plans);
		if (mc.player != null) {
			order.sort(Comparator.comparingDouble(p -> p.distance(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
		}
		Map<String, Map<String, Long>> out = new HashMap<>();
		Map<String, Long> pool = new HashMap<>();
		java.util.Set<String> pooled = new java.util.HashSet<>();
		for (Plan p : order) {
			Stock s = stockFor(p);
			Map<String, Long> mine = new HashMap<>();
			for (Barrel b : s.own()) {
				b.items().forEach((k, v) -> mine.merge(k, v, Long::sum));
			}
			for (Barrel b : s.general()) {
				if (pooled.add(key(b.dim(), new BlockPos(b.x(), b.y(), b.z())))) {
					b.items().forEach((k, v) -> pool.merge(k, v, Long::sum));
				}
			}
			// aus dem allgemeinen Lager nur so viel nehmen, wie noch gebraucht wird
			Map<String, Long> need = needs.getOrDefault(p.id, Map.of());
			for (var e : need.entrySet()) {
				long still = e.getValue() - mine.getOrDefault(e.getKey(), 0L);
				long avail = pool.getOrDefault(e.getKey(), 0L);
				long take = Math.min(Math.max(0, still), avail);
				if (take > 0) {
					mine.merge(e.getKey(), take, Long::sum);
					pool.put(e.getKey(), avail - take);
				}
			}
			out.put(p.id, mine);
		}
		return out;
	}

	/** Wann das älteste Fass dieser Schematic zuletzt geöffnet wurde (0 = keins). */
	public static long oldest(Stock s) {
		long min = 0;
		for (List<Barrel> l : List.of(s.own(), s.general())) {
			for (Barrel b : l) {
				if (min == 0 || b.at() < min) {
					min = b.at();
				}
			}
		}
		return min;
	}

	// ------------------------------------------------------------------ Speichern (config/loothub-bau-lager.json)

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-bau-lager.json");
	}

	private static Map<String, Barrel> load() {
		if (barrels != null) {
			return barrels;
		}
		barrels = new LinkedHashMap<>();
		try {
			Path p = file();
			if (Files.exists(p)) {
				JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var e : root.entrySet()) {
					JsonObject o = e.getValue().getAsJsonObject();
					Map<String, Long> items = new HashMap<>();
					for (var i : o.getAsJsonObject("items").entrySet()) {
						items.put(i.getKey(), i.getValue().getAsLong());
					}
					barrels.put(e.getKey(), new Barrel(o.get("dim").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(),
							o.get("z").getAsInt(), o.get("name").getAsString(), items, o.get("at").getAsLong()));
				}
			}
		} catch (Exception e) {
			Loothub.LOGGER.debug("Bau-Lager nicht lesbar", e);
		}
		return barrels;
	}

	private static void save() {
		JsonObject root = new JsonObject();
		load().forEach((k, b) -> {
			JsonObject o = new JsonObject();
			o.addProperty("dim", b.dim());
			o.addProperty("x", b.x());
			o.addProperty("y", b.y());
			o.addProperty("z", b.z());
			o.addProperty("name", b.name());
			o.addProperty("at", b.at());
			JsonObject items = new JsonObject();
			b.items().forEach(items::addProperty);
			o.add("items", items);
			root.add(k, o);
		});
		try {
			Files.writeString(file(), root.toString(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			Loothub.LOGGER.debug("Bau-Lager nicht gespeichert", e);
		}
	}

	/** Anzahl gemerkter Fässer (für Hinweise). */
	public static int size() {
		return load().size();
	}

	static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
