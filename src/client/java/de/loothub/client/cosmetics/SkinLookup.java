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

	/** attempts/retryAt: bei Fehlschlag später erneut versuchen (Mojang drosselt viele Anfragen kurz hintereinander). */
	private record Result(State state, PlayerSkin skin, int attempts, long retryAt) {
	}

	private static final int MAX_ATTEMPTS = 6;
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
