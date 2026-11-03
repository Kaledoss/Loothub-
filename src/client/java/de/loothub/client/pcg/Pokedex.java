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
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Eigener kleiner Pokédex: merkt sich, welche Pokémon man laut Bot-Nachrichten gefangen hat
 * (bzw. laut {@code !pokecheck} noch nicht). Gespeichert in {@code config/loothub-pokedex.json}.
 */
public final class Pokedex {
	public enum State { CAUGHT, NOT_CAUGHT, UNKNOWN }

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Englischer Name (klein) → gefangen ja/nein. */
	private static Map<String, Boolean> entries;

	private Pokedex() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-pokedex.json");
	}

	private static synchronized void load() {
		if (entries != null) {
			return;
		}
		entries = new HashMap<>();
		Path p = file();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				Map<String, Boolean> m = GSON.fromJson(r, new TypeToken<Map<String, Boolean>>() { }.getType());
				if (m != null) {
					entries.putAll(m);
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.warn("Pokédex konnte nicht gelesen werden", e);
			}
		}
	}

	private static synchronized void save() {
		try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
			GSON.toJson(new TreeMap<>(entries), w);
		} catch (IOException e) {
			Loothub.LOGGER.warn("Pokédex konnte nicht gespeichert werden", e);
		}
	}

	private static String key(String name) {
		return name.strip().toLowerCase(Locale.ROOT);
	}

	public static synchronized State state(String name) {
		if (name == null) {
			return State.UNKNOWN;
		}
		load();
		Boolean b = entries.get(key(name));
		return b == null ? State.UNKNOWN : b ? State.CAUGHT : State.NOT_CAUGHT;
	}

	public static synchronized void set(String name, boolean caught) {
		load();
		Boolean old = entries.put(key(name), caught);
		if (old == null || old != caught) {
			save();
		}
	}

	public static synchronized int caughtCount() {
		load();
		return (int) entries.values().stream().filter(Boolean::booleanValue).count();
	}
}
