package de.loothub.client.pcg;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Eigener Pokédex: beginnt leer und bekommt automatisch einen Eintrag, sobald der Bot meldet, dass man
 * selbst ein Pokémon gefangen hat. Gespeichert in {@code config/loothub-pokedex.json} (Name → Zeitpunkt).
 */
public final class Pokedex {
	public enum State { CAUGHT, NOT_CAUGHT }

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Englischer Name (klein) → Zeitpunkt des ersten Fangs (0 = unbekannt). */
	private static Map<String, Long> caught;

	private Pokedex() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-pokedex.json");
	}

	private static synchronized void load() {
		if (caught != null) {
			return;
		}
		caught = new HashMap<>();
		Path p = file();
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(r);
			if (root != null && root.isJsonObject()) {
				JsonObject o = root.getAsJsonObject();
				for (String k : o.keySet()) {
					JsonElement v = o.get(k);
					if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
						if (v.getAsBoolean()) {
							caught.put(k, 0L); // ältere Datei (ja/nein)
						}
					} else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
						caught.put(k, v.getAsLong());
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.warn("Pokédex konnte nicht gelesen werden", e);
		}
	}

	private static synchronized void save() {
		de.loothub.client.AsyncSave.write(file(), GSON.toJson(new TreeMap<>(caught)));
	}

	private static String key(String name) {
		return name.strip().toLowerCase(Locale.ROOT);
	}

	public static synchronized State state(String name) {
		if (name == null) {
			return State.NOT_CAUGHT;
		}
		load();
		return caught.containsKey(key(name)) ? State.CAUGHT : State.NOT_CAUGHT;
	}

	/** Neuer Fang – nur beim ersten Mal gespeichert. */
	public static synchronized void addCaught(String name) {
		load();
		if (caught.putIfAbsent(key(name), System.currentTimeMillis()) == null) {
			save();
		}
	}

	/** Alle gefangenen Pokémon (englischer Name klein → Zeitpunkt). */
	public static synchronized Map<String, Long> all() {
		load();
		return new HashMap<>(caught);
	}
}
