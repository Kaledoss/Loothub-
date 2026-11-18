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
		boolean force = FORCE.remove(key);
		CompletableFuture.supplyAsync(() -> {
			// gemerkter Skin (höchstens einen Tag alt): gar nicht erst bei Mojang fragen – schont das Anfrage-Limit
			Saved saved = saved(key);
			if (saved != null && !force && System.currentTimeMillis() - saved.at() < FRESH_MS) {
				return saved.profile();
			}
			try {
				GameProfile fresh = lookup(name, mc);
				if (fresh != null) {
					remember(key, fresh);
					return fresh;
				}
				// „nicht gefunden“ kann bei gedrosselten Anfragen auch nur ein Fehler sein: dann den gemerkten nehmen
				return saved != null ? saved.profile() : null;
			} catch (RuntimeException e) {
				if (saved != null) {
					return saved.profile(); // Mojang überlastet (z. B. Fehler 429): gemerkten Skin nehmen
				}
				throw e;
			}
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

	/** Profil samt Skin-Daten bei Mojang (bzw. Geyser für Bedrock) nachschlagen; null = Name unbekannt. */
	private static GameProfile lookup(String name, Minecraft mc) {
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
	}

	// ------------------------------------------------------------------ gemerkte Skins (config/loothub-skins.json)

	/** Gemerkte Skin-Daten eines Namens; at = wann zuletzt bei Mojang geholt. */
	private record Saved(GameProfile profile, long at) {
	}

	private static final long FRESH_MS = 24L * 60 * 60 * 1000;
	private static final java.util.Set<String> FORCE = ConcurrentHashMap.newKeySet();
	private static JsonObject disk;

	private static java.nio.file.Path diskFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("loothub-skins.json");
	}

	private static synchronized JsonObject disk() {
		if (disk == null) {
			disk = new JsonObject();
			try {
				java.nio.file.Path p = diskFile();
				if (java.nio.file.Files.exists(p)) {
					var e = JsonParser.parseString(java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
					if (e.isJsonObject()) {
						disk = e.getAsJsonObject();
					}
				}
			} catch (java.io.IOException | RuntimeException e) {
				de.loothub.Loothub.LOGGER.debug("Gemerkte Skins nicht lesbar", e);
			}
		}
		return disk;
	}

	private static synchronized Saved saved(String key) {
		try {
			JsonObject o = disk().getAsJsonObject(key);
			if (o == null) {
				return null;
			}
			String sig = o.has("sig") && !o.get("sig").isJsonNull() ? o.get("sig").getAsString() : null;
			PropertyMap props = new PropertyMap(ImmutableMultimap.of("textures",
					new Property("textures", o.get("value").getAsString(), sig)));
			GameProfile profile = new GameProfile(java.util.UUID.fromString(o.get("id").getAsString()),
					o.get("name").getAsString(), props);
			return new Saved(profile, o.get("at").getAsLong());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static synchronized void remember(String key, GameProfile profile) {
		Property tex = profile.properties().get("textures").stream().findFirst().orElse(null);
		if (tex == null) {
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", profile.id().toString());
		o.addProperty("name", profile.name());
		o.addProperty("value", tex.value());
		if (tex.signature() != null) {
			o.addProperty("sig", tex.signature());
		}
		o.addProperty("at", System.currentTimeMillis());
		disk().add(key, o);
		try {
			java.nio.file.Files.writeString(diskFile(), disk().toString(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			de.loothub.Loothub.LOGGER.debug("Gemerkte Skins nicht gespeichert", e);
		}
	}

	/** Neu laden auf Knopfdruck: frisch bei Mojang fragen (klappt das nicht, bleibt der gemerkte Skin). */
	public static void reload(String name) {
		if (name == null || name.isBlank()) {
			return;
		}
		String key = name.strip().toLowerCase(Locale.ROOT);
		FORCE.add(key);
		CACHE.remove(key);
		request(name);
	}

	/** Erneut versuchen (z. B. nach Tippfehler korrigiert). */
	public static void forget(String name) {
		if (name != null) {
			CACHE.remove(name.strip().toLowerCase(Locale.ROOT));
		}
	}
}
