package de.loothub.client.pcg;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Liste aller Pokémon mit Nummer im Nationalen Pokédex sowie deutschem und englischem Namen.
 * Einmal als CSV aus den offenen PokéAPI-Daten geladen und in {@code config/loothub-pokedex-names.csv} gemerkt.
 */
public final class PokedexData {
	private static final String URL =
			"https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv";
	private static final int LANG_DE = 6;
	private static final int LANG_EN = 9;

	public record Entry(int number, String german, String english) {
	}

	private static volatile List<Entry> entries = List.of();
	private static volatile boolean loading;
	private static volatile boolean failed;

	private PokedexData() {
	}

	public static List<Entry> entries() {
		ensureLoaded();
		return entries;
	}

	public static boolean isLoading() {
		return loading;
	}

	public static boolean failed() {
		return failed;
	}

	private static Path cacheFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-pokedex-names.csv");
	}

	public static synchronized void ensureLoaded() {
		if (!entries.isEmpty() || loading) {
			return;
		}
		Path cache = cacheFile();
		if (Files.exists(cache)) {
			try {
				entries = parse(Files.readString(cache, StandardCharsets.UTF_8));
				if (!entries.isEmpty()) {
					return;
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.warn("Pokédex-Liste aus dem Zwischenspeicher nicht lesbar", e);
			}
		}
		loading = true;
		failed = false;
		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
				.sendAsync(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(30))
						.header("User-Agent", "Loothub-Mod").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.whenComplete((r, error) -> {
					try {
						if (error != null || r.statusCode() != 200) {
							throw new IllegalStateException(error != null ? error.toString() : "HTTP " + r.statusCode());
						}
						List<Entry> list = parse(r.body());
						if (list.isEmpty()) {
							throw new IllegalStateException("leer");
						}
						entries = list;
						Files.writeString(cacheFile(), r.body(), StandardCharsets.UTF_8);
					} catch (IOException | RuntimeException e) {
						Loothub.LOGGER.warn("Pokédex-Liste konnte nicht geladen werden: {}", e.toString());
						failed = true;
					} finally {
						loading = false;
					}
				});
	}

	/** CSV: pokemon_species_id,local_language_id,name,genus */
	private static List<Entry> parse(String csv) {
		Map<Integer, String[]> byId = new HashMap<>();
		String[] lines = csv.split("\r?\n");
		for (int i = 1; i < lines.length; i++) {
			String[] f = splitCsv(lines[i]);
			if (f.length < 3) {
				continue;
			}
			try {
				int id = Integer.parseInt(f[0].strip());
				int lang = Integer.parseInt(f[1].strip());
				if (lang != LANG_DE && lang != LANG_EN) {
					continue;
				}
				String[] names = byId.computeIfAbsent(id, k -> new String[2]);
				names[lang == LANG_DE ? 0 : 1] = f[2];
			} catch (NumberFormatException ignored) {
				// Kopfzeile o. Ä.
			}
		}
		List<Entry> out = new ArrayList<>();
		for (Map.Entry<Integer, String[]> e : byId.entrySet()) {
			String en = e.getValue()[1];
			if (en == null) {
				continue;
			}
			String de = e.getValue()[0] == null ? en : e.getValue()[0];
			out.add(new Entry(e.getKey(), de, en));
		}
		out.sort(Comparator.comparingInt(Entry::number));
		return List.copyOf(out);
	}

	/** Einfache CSV-Zerlegung mit Anführungszeichen. */
	private static String[] splitCsv(String line) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '"') {
				if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					cur.append('"');
					i++;
				} else {
					quoted = !quoted;
				}
			} else if (c == ',' && !quoted) {
				out.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		out.add(cur.toString());
		return out.toArray(new String[0]);
	}
}
