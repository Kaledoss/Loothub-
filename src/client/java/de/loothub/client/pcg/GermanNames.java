package de.loothub.client.pcg;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Deutsche Pokémon-Namen über die freie PokéAPI (pokeapi.co). Einmal pro Name abgefragt und gemerkt;
 * solange nichts da ist oder nichts gefunden wird, bleibt der englische Name stehen.
 */
public final class GermanNames {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	/** Englischer Name (klein) → deutscher Name; leerer Text = nicht gefunden. */
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();

	private GermanNames() {
	}

	/** Deutscher Name, falls bekannt – sonst der englische. Startet bei Bedarf die Abfrage im Hintergrund. */
	public static String of(String english) {
		if (english == null || english.isBlank()) {
			return english;
		}
		String key = english.strip().toLowerCase(Locale.ROOT);
		String de = CACHE.get(key);
		if (de != null) {
			return de.isEmpty() ? english : de;
		}
		fetch(key);
		return english;
	}

	/** „Mr. Mime“ → „mr-mime“, „Nidoran♀“ → „nidoran-f“, „Farfetch'd“ → „farfetchd“. */
	static String slug(String name) {
		return name.toLowerCase(Locale.ROOT)
				.replace("♀", "-f").replace("♂", "-m")
				.replace("é", "e")
				.replaceAll("[.'’:]", "")
				.strip()
				.replaceAll("\\s+", "-");
	}

	private static void fetch(String key) {
		if (PENDING.putIfAbsent(key, Boolean.TRUE) != null) {
			return;
		}
		String slug = slug(key);
		// Sonderformen wie „Alolan Vulpix“ oder „Shiny …“: Basisname = letztes Wort
		String base = slug.contains("-") && !slug.startsWith("mr-") && !slug.startsWith("mime-")
				&& !slug.startsWith("tapu-") && !slug.endsWith("-f") && !slug.endsWith("-m")
				? slug.substring(slug.lastIndexOf('-') + 1) : slug;
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://pokeapi.co/api/v2/pokemon-species/" + slug))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "Loothub-Mod")
				.GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(r -> r.statusCode() == 200 ? germanFrom(r.body()) : null)
				.thenCompose(de -> {
					if (de != null || base.equals(slug)) {
						return java.util.concurrent.CompletableFuture.completedFuture(de);
					}
					HttpRequest retry = HttpRequest.newBuilder(URI.create("https://pokeapi.co/api/v2/pokemon-species/" + base))
							.timeout(Duration.ofSeconds(10)).header("User-Agent", "Loothub-Mod").GET().build();
					return HTTP.sendAsync(retry, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
							.thenApply(r -> r.statusCode() == 200 ? germanFrom(r.body()) : null);
				})
				.whenComplete((de, error) -> {
					if (error == null) {
						CACHE.put(key, de == null ? "" : de);
					}
					PENDING.remove(key); // bei Netzfehler später erneut versuchen
				});
	}

	private static String germanFrom(String body) {
		try {
			JsonElement root = JsonParser.parseString(body);
			for (JsonElement e : root.getAsJsonObject().getAsJsonArray("names")) {
				JsonObject o = e.getAsJsonObject();
				if ("de".equals(o.getAsJsonObject("language").get("name").getAsString())) {
					return o.get("name").getAsString();
				}
			}
		} catch (RuntimeException ignored) {
			// unbekanntes Format → englisch lassen
		}
		return null;
	}
}
