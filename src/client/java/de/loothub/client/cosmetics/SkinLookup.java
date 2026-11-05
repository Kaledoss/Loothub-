package de.loothub.client.cosmetics;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Skin eines beliebigen Spielers per Name laden (Mojang-Profil wie bei Spielerköpfen) – für Mini-Me mit fremdem Skin.
 * Ergebnisse werden zwischengespeichert.
 */
public final class SkinLookup {
	public enum State { LOADING, READY, NOT_FOUND }

	private record Result(State state, PlayerSkin skin) {
	}

	private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

	private SkinLookup() {
	}

	/** Skin zu diesem Namen, oder null solange er lädt bzw. nicht gefunden wurde. Startet das Laden bei Bedarf. */
	public static PlayerSkin get(String name) {
		Result r = request(name);
		return r == null || r.state() != State.READY ? null : r.skin();
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
		if (existing != null) {
			return existing;
		}
		Result loading = new Result(State.LOADING, null);
		CACHE.put(key, loading);
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture.supplyAsync(() -> {
			Optional<GameProfile> found = mc.services().profileResolver().fetchByName(name.strip());
			if (found.isEmpty()) {
				return null;
			}
			GameProfile profile = found.get();
			if (!profile.properties().containsKey("textures")) {
				try {
					var full = mc.services().sessionService().fetchProfile(profile.id(), true);
					if (full != null && full.profile() != null) {
						profile = full.profile();
					}
				} catch (RuntimeException ignored) {
					// ohne Textur-Daten bleibt es beim Standard-Skin
				}
			}
			return profile;
		}).thenCompose(profile -> profile == null
				? CompletableFuture.completedFuture(Optional.<PlayerSkin>empty())
				: mc.getSkinManager().get(profile)).whenComplete((skin, t) -> {
					if (t != null || skin == null || skin.isEmpty()) {
						CACHE.put(key, new Result(State.NOT_FOUND, null));
					} else {
						CACHE.put(key, new Result(State.READY, skin.get()));
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
