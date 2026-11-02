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
 * Deutsche Pokémon-Namen und Fangrate über die freie PokéAPI (pokeapi.co). Einmal pro Name abgefragt und
 * gemerkt; solange nichts da ist oder nichts gefunden wird, bleibt der englische Name stehen.
 */
public final class GermanNames {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	/** Englischer Name (klein) → deutscher Name; leerer Text = nicht gefunden. */
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();
	/** Englischer Name (klein) → Fangdaten. */
	private static final Map<String, Info> INFO = new ConcurrentHashMap<>();

	/** Fangrate laut Spieldaten (3 = sehr schwer … 255 = sehr leicht) und ob legendär/mysteriös. */
	public record Info(int captureRate, boolean legendary, boolean mythical) {
	}

	/** Typen (englisch, z. B. „water“) und Gewicht in kg – aus /pokemon/{id}. */
	public record Body(java.util.List<String> types, double weightKg) {
	}

	/** Sonderball: Anzeigename, Befehls-ID und Grund. */
	public record Special(String label, String id, String reason) {
	}

	private static final Map<String, Body> BODY = new ConcurrentHashMap<>();

	/**
	 * Passender Sonderball nach Typ und Gewicht, oder null, wenn keiner einen Vorteil bringt (bzw. Daten fehlen).
	 * Regeln wie in den Hauptspielen: Netzball für Wasser/Käfer, Schwerball ab 200 kg, Federball bis 10 kg.
	 */
	public static Special specialBall(String english) {
		if (english == null) {
			return null;
		}
		of(english);
		Body b = BODY.get(english.strip().toLowerCase(Locale.ROOT));
		if (b == null) {
			return null;
		}
		if (b.types().contains("water") || b.types().contains("bug")) {
			return new Special("Netzball", "netball", b.types().contains("water") ? "Wasser-Pokémon" : "Käfer-Pokémon");
		}
		if (b.weightKg() >= 200) {
			return new Special("Schwerball", "heavyball", "sehr schwer (" + Math.round(b.weightKg()) + " kg)");
		}
		if (b.weightKg() <= 10) {
			return new Special("Federball", "featherball", "sehr leicht (" + b.weightKg() + " kg)");
		}
		return null;
	}

	/** Ballempfehlung: 0 = Pokéball, 1 = Superball, 2 = Hyperball; -1 = noch unbekannt. */
	public static int recommendedBall(String english) {
		if (english == null) {
			return -1;
		}
		of(english); // stößt die Abfrage an, falls nötig
		Info info = INFO.get(english.strip().toLowerCase(Locale.ROOT));
		if (info == null) {
			return -1;
		}
		if (info.legendary() || info.mythical() || info.captureRate() < 45) {
			return 2;
		}
		return info.captureRate() < 120 ? 1 : 0;
	}

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
				.thenApply(r -> r.statusCode() == 200 ? germanFrom(key, r.body()) : null)
				.thenCompose(de -> {
					if (de != null || base.equals(slug)) {
						return java.util.concurrent.CompletableFuture.completedFuture(de);
					}
					HttpRequest retry = HttpRequest.newBuilder(URI.create("https://pokeapi.co/api/v2/pokemon-species/" + base))
							.timeout(Duration.ofSeconds(10)).header("User-Agent", "Loothub-Mod").GET().build();
					return HTTP.sendAsync(retry, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
							.thenApply(r -> r.statusCode() == 200 ? germanFrom(key, r.body()) : null);
				})
				.whenComplete((de, error) -> {
					if (error == null) {
						CACHE.put(key, de == null ? "" : de);
					}
					PENDING.remove(key); // bei Netzfehler später erneut versuchen
				});
	}

	/** Typen und Gewicht nachladen (Standardform hat dieselbe ID wie die Art). */
	private static void fetchBody(String key, int id) {
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://pokeapi.co/api/v2/pokemon/" + id))
				.timeout(Duration.ofSeconds(10)).header("User-Agent", "Loothub-Mod").GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenAccept(r -> {
			if (r.statusCode() != 200) {
				return;
			}
			try {
				JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
				java.util.List<String> types = new java.util.ArrayList<>();
				for (JsonElement t : o.getAsJsonArray("types")) {
					types.add(t.getAsJsonObject().getAsJsonObject("type").get("name").getAsString());
				}
				BODY.put(key, new Body(java.util.List.copyOf(types), o.get("weight").getAsInt() / 10.0));
			} catch (RuntimeException ignored) {
				// keine Typdaten → kein Sonderball
			}
		});
	}

	private static String germanFrom(String key, String body) {
		try {
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			try {
				fetchBody(key, root.get("id").getAsInt());
			} catch (RuntimeException ignored) {
				// ohne ID keine Typdaten
			}
			try {
				INFO.put(key, new Info(root.get("capture_rate").getAsInt(),
						root.has("is_legendary") && root.get("is_legendary").getAsBoolean(),
						root.has("is_mythical") && root.get("is_mythical").getAsBoolean()));
			} catch (RuntimeException ignored) {
				// keine Fangdaten → keine Empfehlung
			}
			for (JsonElement e : root.getAsJsonArray("names")) {
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
