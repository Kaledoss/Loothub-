package de.loothub.client.cosmetics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Loothub-Cosmetics, die alle Spieler mit der Mod sehen. Wer was trägt, steht in {@code cosmetics.json} im
 * GitHub-Repository (Cosmetic → Liste von Spielernamen oder UUIDs); die Mod lädt die Datei beim Start und alle
 * 10 Minuten neu. Es wird nichts hochgeladen – jede Mod liest nur diese Liste.
 */
public final class Cosmetics {
	public static final String MINI_ME = "minime";

	private static final String URL = "https://raw.githubusercontent.com/Kaledoss/Loothub-/main/cosmetics.json";
	private static final long REFRESH_MS = 10 * 60_000L;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	/** Spieler (Name klein oder UUID) → seine Cosmetics. */
	private static volatile Map<String, Set<String>> owners = Map.of();
	/** Spieler → Name des Spielers, dessen Skin sein Mini-Me trägt (fehlt = eigener Skin). */
	private static volatile Map<String, String> miniMeSkins = Map.of();
	private static volatile long lastFetch;
	private static volatile boolean loading;

	/** Cosmetics je Render-Zustand (nur für den aktuellen Frame). */
	private static final Map<Object, Set<String>> STATES = new WeakHashMap<>();
	private static final Map<Object, String> SKINS = new WeakHashMap<>();

	private Cosmetics() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (WidgetConfig.get().cosmetics && !loading && System.currentTimeMillis() - lastFetch > REFRESH_MS) {
				fetch();
			}
		});
	}

	private static void fetch() {
		loading = true;
		lastFetch = System.currentTimeMillis();
		HttpRequest req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(15))
				.header("User-Agent", "Loothub-Mod").GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((r, t) -> {
			try {
				if (t == null && r.statusCode() == 200) {
					Map<String, String> skins = new HashMap<>();
					owners = parse(r.body(), skins);
					miniMeSkins = skins;
				}
			} catch (RuntimeException e) {
				Loothub.LOGGER.warn("Cosmetics-Liste nicht lesbar", e);
			} finally {
				loading = false;
			}
		});
	}

	/**
	 * {"minime": ["Kaledoss", …]} – Mini-Me mit eigenem Skin, oder {"minime": {"Kaledoss": "Notch"}} – Mini-Me mit dem
	 * Skin eines anderen Spielers (leerer Text = eigener Skin). Weitere Cosmetics als Liste.
	 */
	private static Map<String, Set<String>> parse(String json, Map<String, String> skins) {
		Map<String, Set<String>> out = new HashMap<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		for (String cosmetic : root.keySet()) {
			String c = cosmetic.toLowerCase(Locale.ROOT);
			JsonElement value = root.get(cosmetic);
			if (value.isJsonArray()) {
				for (JsonElement who : value.getAsJsonArray()) {
					String key = who.getAsString().strip().toLowerCase(Locale.ROOT);
					out.computeIfAbsent(key, k -> new HashSet<>()).add(c);
				}
			} else if (value.isJsonObject()) {
				JsonObject map = value.getAsJsonObject();
				for (String who : map.keySet()) {
					String key = who.strip().toLowerCase(Locale.ROOT);
					out.computeIfAbsent(key, k -> new HashSet<>()).add(c);
					String skin = map.get(who).isJsonPrimitive() ? map.get(who).getAsString().strip() : "";
					if (c.equals(MINI_ME) && !skin.isEmpty()) {
						skins.put(key, skin);
					}
				}
			}
		}
		return out;
	}

	/** Beim Vorbereiten des Renderns: Cosmetics des Spielers an seinen Render-Zustand hängen. */
	public static void mark(Object state, LivingEntity entity) {
		SKINS.remove(state);
		if (!(entity instanceof Player p) || !WidgetConfig.get().cosmetics) {
			STATES.remove(state);
			return;
		}
		Map<String, Set<String>> o = owners;
		String uuid = p.getUUID().toString();
		String name = p.getGameProfile().name().toLowerCase(Locale.ROOT);
		Set<String> set = o.get(uuid);
		String key = uuid;
		if (set == null) {
			set = o.get(name);
			key = name;
		}
		String skin = miniMeSkins.get(key);
		// Eigene Wahl in den Einstellungen: eigenes Mini-Me (mit gewähltem Skin) – sofort sichtbar, für andere erst
		// nach Eintrag in cosmetics.json
		String own = WidgetConfig.get().miniMeSkin;
		if (p == net.minecraft.client.Minecraft.getInstance().player && own != null && !own.isBlank()) {
			Set<String> mine = new HashSet<>(set == null ? Set.of() : set);
			mine.add(MINI_ME);
			set = mine;
			skin = own.strip();
		}
		if (set == null || set.isEmpty()) {
			STATES.remove(state);
		} else {
			STATES.put(state, set);
			if (skin != null && !skin.isEmpty()) {
				SKINS.put(state, skin);
			}
		}
	}

	/** Name des Spielers, dessen Skin das Mini-Me tragen soll (null = eigener Skin). */
	public static String miniMeSkin(Object state) {
		return SKINS.get(state);
	}

	public static boolean has(Object state, String cosmetic) {
		Set<String> set = STATES.get(state);
		return set != null && set.contains(cosmetic);
	}
}
