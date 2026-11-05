package de.loothub.client.stats;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Speichert die eigene Statistik pro Tag in {@code config/loothub-stats.json}:
 * {@code {"2026-11-02": {"mined.minecraft:stone": 120, "general.jumps": 33, …}, …}}.
 *
 * <p>Schlüssel: {@code general.*} (Strecken in cm, Schaden in Zehntel-Lebenspunkten, Zeit in ms),
 * {@code mined.<block>}, {@code placed.<block>}, {@code picked.<item>}, {@code eaten.<item>}, {@code killed.<mob>}.
 */
public final class StatsStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long SAVE_INTERVAL_MS = 60_000;

	public enum Range {
		TODAY, WEEK, TOTAL
	}

	private static Map<String, Map<String, Long>> days;
	private static boolean dirty;
	private static long lastSave;
	private static int version;

	private StatsStore() {
	}

	public static synchronized void add(String key, long amount) {
		if (amount <= 0) {
			return;
		}
		ensureLoaded();
		days.computeIfAbsent(LocalDate.now().toString(), d -> new HashMap<>()).merge(key, amount, Long::sum);
		dirty = true;
		version++;
	}

	/** Zählt sich bei jeder Änderung hoch – so weiß das Menü, wann es neu rechnen muss. */
	public static synchronized int version() {
		return version;
	}

	/** Summe aller Zähler im gewählten Zeitraum. */
	public static synchronized Map<String, Long> sum(Range range) {
		ensureLoaded();
		LocalDate today = LocalDate.now();
		LocalDate from = switch (range) {
			case TODAY -> today;
			case WEEK -> today.minusDays(6);
			case TOTAL -> LocalDate.MIN;
		};
		Map<String, Long> out = new HashMap<>();
		for (Map.Entry<String, Map<String, Long>> day : days.entrySet()) {
			LocalDate d;
			try {
				d = LocalDate.parse(day.getKey());
			} catch (RuntimeException e) {
				continue;
			}
			if (d.isBefore(from)) {
				continue;
			}
			day.getValue().forEach((k, v) -> out.merge(k, v, Long::sum));
		}
		return out;
	}

	/** Erster Tag mit gezählten Werten, oder null, wenn noch nichts gezählt wurde. */
	public static synchronized LocalDate firstDay() {
		ensureLoaded();
		LocalDate first = null;
		for (String key : days.keySet()) {
			try {
				LocalDate d = LocalDate.parse(key);
				if (first == null || d.isBefore(first)) {
					first = d;
				}
			} catch (RuntimeException ignored) {
				// ungültiger Eintrag
			}
		}
		return first;
	}

	/** Ab welchem Tag der Zeitraum zählt – frühestens ab dem ersten gezählten Tag. Null ohne Daten. */
	public static synchronized LocalDate since(Range range) {
		LocalDate first = firstDay();
		if (first == null) {
			return null;
		}
		LocalDate today = LocalDate.now();
		LocalDate from = switch (range) {
			case TODAY -> today;
			case WEEK -> today.minusDays(6);
			case TOTAL -> first;
		};
		return from.isBefore(first) ? first : from;
	}

	public static synchronized void clear() {
		ensureLoaded();
		days.clear();
		dirty = true;
		version++;
		saveIfDirty(true);
	}

	/** Speichert höchstens einmal pro Minute – außer {@code force}. */
	public static synchronized void saveIfDirty(boolean force) {
		long now = System.currentTimeMillis();
		if (!dirty || days == null || (!force && now - lastSave < SAVE_INTERVAL_MS)) {
			return;
		}
		de.loothub.client.AsyncSave.write(path(), GSON.toJson(new TreeMap<>(days)));
		dirty = false;
		lastSave = now;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(Loothub.MOD_ID + "-stats.json");
	}

	private static void ensureLoaded() {
		if (days != null) {
			return;
		}
		days = new HashMap<>();
		lastSave = System.currentTimeMillis();
		Path p = path();
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			Map<String, Map<String, Long>> loaded = GSON.fromJson(r, new TypeToken<Map<String, Map<String, Long>>>() {
			}.getType());
			if (loaded != null) {
				loaded.forEach((day, values) -> {
					if (values != null) {
						days.put(day, new HashMap<>(values));
					}
				});
			}
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.warn("Statistik konnte nicht gelesen werden", e);
		}
	}
}
