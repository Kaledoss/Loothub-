package de.loothub.client.cosmetics;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Skin eines beliebigen Spielers per Name laden (Mojang-Profil wie bei Spielerköpfen) – für Mini-Me mit fremdem Skin.
 * Bedrock-Spieler (Name mit vorangestelltem „.“ oder „*“, wie sie über Geyser/Floodgate auf Java-Servern heißen) werden
 * über die öffentliche Geyser-Schnittstelle nachgeschlagen: Gamertag → XUID → zuletzt umgewandelter Skin.
 * Ergebnisse werden zwischengespeichert.
 */
public final class SkinLookup {
	public enum State { LOADING, READY, NOT_FOUND }

	/** attempts/retryAt: bei Fehlschlag später erneut versuchen (Mojang drosselt viele Anfragen kurz hintereinander). */
	private record Result(State state, PlayerSkin skin, int attempts, long retryAt) {
	}

	private static final int MAX_ATTEMPTS = 6;
	private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
			.connectTimeout(java.time.Duration.ofSeconds(8)).build();

	/** Bedrock-Spieler? (Floodgate-Präfix „.“ oder „*“) */
	public static boolean bedrock(String name) {
		String n = name == null ? "" : name.strip();
		return n.length() > 1 && (n.charAt(0) == '.' || n.charAt(0) == '*');
	}

	/** Bedrock-Skin über die Geyser-Schnittstelle; null = Gamertag unbekannt bzw. kein Skin hinterlegt. */
	private static GameProfile bedrockProfile(String name) {
		try {
			String tag = name.strip().substring(1);
			// Floodgate ersetzt Leerzeichen im Gamertag durch „_“ – für die Suche wieder zurück
			String query = java.net.URLEncoder.encode(tag.replace('_', ' '), java.nio.charset.StandardCharsets.UTF_8)
					.replace("+", "%20");
			JsonObject x = fetchJson("https://api.geysermc.org/v2/xbox/xuid/" + query);
			if ((x == null || !x.has("xuid")) && tag.contains("_")) {
				x = fetchJson("https://api.geysermc.org/v2/xbox/xuid/" + java.net.URLEncoder.encode(tag,
						java.nio.charset.StandardCharsets.UTF_8));
			}
			if (x == null || !x.has("xuid")) {
				return null;
			}
			long xuid = x.get("xuid").getAsLong();
			JsonObject skin = fetchJson("https://api.geysermc.org/v2/skin/" + xuid);
			if (skin == null || !skin.has("value") || skin.get("value").getAsString().isEmpty()) {
				return null;
			}
			String sig = skin.has("signature") && !skin.get("signature").isJsonNull() ? skin.get("signature").getAsString() : null;
			PropertyMap props = new PropertyMap(ImmutableMultimap.of("textures",
					new Property("textures", skin.get("value").getAsString(), sig)));
			String shown = tag.replaceAll("[^A-Za-z0-9_]", "_");
			if (shown.length() > 16) {
				shown = shown.substring(0, 16);
			}
			// dieselbe UUID, die Floodgate dem Spieler gibt (XUID in den unteren 64 Bit)
			return new GameProfile(new java.util.UUID(0L, xuid), shown.isEmpty() ? "Bedrock" : shown, props);
		} catch (java.io.IOException | InterruptedException | RuntimeException e) {
			throw new IllegalStateException("Geyser-Abfrage fehlgeschlagen", e);
		}
	}

	private static JsonObject fetchJson(String url) throws java.io.IOException, InterruptedException {
		var r = HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(java.time.Duration.ofSeconds(10)).header("User-Agent", "Loothub-Mod").GET().build(),
				java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
		if (r.statusCode() == 400 || r.statusCode() == 404) {
			return null; // unbekannt
		}
		if (r.statusCode() != 200) {
			throw new java.io.IOException("HTTP " + r.statusCode());
		}
		var e = JsonParser.parseString(r.body());
		return e.isJsonObject() ? e.getAsJsonObject() : null;
	}
	private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

	private SkinLookup() {
	}

	/** Skin zu diesem Namen, oder null solange er lädt bzw. nicht gefunden wurde. Startet das Laden bei Bedarf. */
	public static PlayerSkin get(String name) {
		Result r = request(name);
		return r == null || r.state() != State.READY ? null : r.skin();
	}

	/** Endgültig nicht gefunden (Name existiert nicht oder viele Versuche gescheitert)? */
	public static boolean gaveUp(String name) {
		Result r = request(name);
		return r == null || r.state() == State.NOT_FOUND && r.attempts() >= MAX_ATTEMPTS;
	}

	public static State state(String name) {
		Result r = request(name);
		return r == null ? State.NOT_FOUND : r.state();
	}

	private static Result request(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String key = name.strip().toLowerCase(Locale.ROOT);
		Result existing = CACHE.get(key);
		int attempts = 0;
		if (existing != null) {
			if (existing.state() != State.NOT_FOUND || existing.attempts() >= MAX_ATTEMPTS
					|| System.currentTimeMillis() < existing.retryAt()) {
				return existing;
			}
			attempts = existing.attempts(); // fehlgeschlagen – jetzt erneut versuchen
		}
		int tries = attempts + 1;
		Result loading = new Result(State.LOADING, null, attempts, 0);
		CACHE.put(key, loading);
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture.supplyAsync(() -> {
			if (bedrock(name)) {
				return bedrockProfile(name);
			}
			Optional<GameProfile> found = mc.services().profileResolver().fetchByName(name.strip());
			if (found.isEmpty()) {
				return null;
			}
			GameProfile profile = found.get();
			if (!profile.properties().containsKey("textures")) {
				var full = mc.services().sessionService().fetchProfile(profile.id(), true);
				if (full != null && full.profile() != null) {
					profile = full.profile();
				}
			}
			// Ohne Textur-Daten käme nur der Standard-Skin heraus – das wäre ein falscher Skin, lieber neu versuchen
			if (!profile.properties().containsKey("textures")) {
				throw new IllegalStateException("keine Textur-Daten");
			}
			return profile;
		}).thenCompose(profile -> profile == null
				? CompletableFuture.completedFuture(Optional.<PlayerSkin>empty())
				: mc.getSkinManager().get(profile)).whenComplete((skin, t) -> {
					if (t == null && (skin == null || skin.isEmpty())) {
						// Name gibt es nicht: nicht weiter versuchen
						CACHE.put(key, new Result(State.NOT_FOUND, null, MAX_ATTEMPTS, 0));
					} else if (t != null) {
						long wait = Math.min(10 * 60_000L, 30_000L << Math.min(5, tries - 1));
						CACHE.put(key, new Result(State.NOT_FOUND, null, tries, System.currentTimeMillis() + wait));
					} else {
						CACHE.put(key, new Result(State.READY, skin.get(), tries, 0));
					}
				});
		return loading;
	}

	/** Erneut versuchen (z. B. nach Tippfehler korrigiert). */
	public static void forget(String name) {
		if (name != null) {
			CACHE.remove(name.strip().toLowerCase(Locale.ROOT));
		}
	}
}
