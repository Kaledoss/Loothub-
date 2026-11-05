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
	private static volatile long lastFetch;
	private static volatile boolean loading;

	/** Cosmetics je Render-Zustand (nur für den aktuellen Frame). */
	private static final Map<Object, Set<String>> STATES = new WeakHashMap<>();

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
					owners = parse(r.body());
				}
			} catch (RuntimeException e) {
				Loothub.LOGGER.warn("Cosmetics-Liste nicht lesbar", e);
			} finally {
				loading = false;
			}
		});
	}

	/** {"minime": ["Kaledoss", "…"], "…": […]} */
	private static Map<String, Set<String>> parse(String json) {
		Map<String, Set<String>> out = new HashMap<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		for (String cosmetic : root.keySet()) {
			if (!root.get(cosmetic).isJsonArray()) {
				continue;
			}
			for (JsonElement who : root.getAsJsonArray(cosmetic)) {
				String key = who.getAsString().strip().toLowerCase(Locale.ROOT);
				out.computeIfAbsent(key, k -> new HashSet<>()).add(cosmetic.toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	/** Beim Vorbereiten des Renderns: Cosmetics des Spielers an seinen Render-Zustand hängen. */
	public static void mark(Object state, LivingEntity entity) {
		if (!(entity instanceof Player p) || !WidgetConfig.get().cosmetics) {
			STATES.remove(state);
			return;
		}
		Map<String, Set<String>> o = owners;
		Set<String> set = o.get(p.getUUID().toString());
		if (set == null) {
			set = o.get(p.getGameProfile().name().toLowerCase(Locale.ROOT));
		}
		if (set == null || set.isEmpty()) {
			STATES.remove(state);
		} else {
			STATES.put(state, set);
		}
	}

	public static boolean has(Object state, String cosmetic) {
		Set<String> set = STATES.get(state);
		return set != null && set.contains(cosmetic);
	}
}
