package de.loothub.client.cosmetics;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Kleine Gesten der Mini-Mes: winken, wenn der Spieler eine Weile stillsteht; jubeln beim Job-Level-Up;
 * sich strecken und gähnen, wenn sie in der AFK-Ansicht mitschlafen. Jedes Mini-Me zu seiner eigenen Zeit.
 */
public final class Emotes {
	public static final int NONE = 0, WAVE = 1, CHEER = 2, YAWN = 3, SCRATCH = 4, HOLD = 5, RAIN = 6, SHIVER = 7, FAN = 8,
			DANGLE = 9;
	/** Dauerhafte Gesten (kein Ein-/Ausblenden über den Verlauf). */
	public static boolean steady(int type) {
		return type == HOLD || type == RAIN || type == SHIVER || type == DANGLE;
	}
	private static final long CHEER_MS = 3200L;
	private static volatile long cheerAt;
	/** Geste je Mini-Me-Zustand: {Art, Fortschritt 0..1}. */
	private static final Map<Object, float[]> STATES = new WeakHashMap<>();

	private Emotes() {
	}

	/** Eigener Job-Level-Up: alle eigenen Mini-Mes jubeln. */
	public static void cheer() {
		cheerAt = System.currentTimeMillis();
	}

	/**
	 * Welche Geste Mini-Me {@code slot} des Spielers gerade macht: {Art, Fortschritt}. Reaktionen auf die Umgebung
	 * (festhalten beim Sprinten, Regen, Lava, Schnee) kommen immer; Gesten im Stand nur mit eingeschalteten Gesten.
	 */
	public static float[] pick(Object playerState, int slot, boolean sleeping, boolean sitting) {
		long now = System.currentTimeMillis();
		float tick = (now % 100_000L) / 1000.0F;
		boolean gestures = Cosmetics.has(playerState, Cosmetics.EMOTES);
		if (gestures && Motion.local(playerState) && now - cheerAt < CHEER_MS) {
			return new float[]{CHEER, (now - cheerAt) / (float) CHEER_MS};
		}
		if (sleeping) {
			float p = window(now, 17_000L, slot * 2_700L, 2_200L);
			return !gestures || p < 0 ? null : new float[]{YAWN, p};
		}
		int env = Motion.env(playerState);
		// auf der Schulter beim Sprinten: am Kopf festhalten
		if (slot < 2 && Motion.forward(playerState) > 0.18F) {
			return new float[]{HOLD, tick};
		}
		if ((env & Motion.ENV_RAIN) != 0 && (env & Motion.ENV_WATER) == 0) {
			return new float[]{RAIN, tick};
		}
		if ((env & Motion.ENV_LAVA) != 0) {
			float p = window(now, 5_000L, slot * 900L, 2_500L);
			if (p >= 0) {
				return new float[]{FAN, p};
			}
		}
		if ((env & Motion.ENV_SNOW) != 0) {
			return new float[]{SHIVER, tick};
		}
		if (gestures && Motion.idle(playerState) > 4.0F) {
			float p = window(now, 16_000L, slot * 2_100L, 2_400L);
			if (p >= 0) {
				return new float[]{WAVE, p};
			}
			p = window(now, 16_000L, slot * 2_100L + 6_000L, 2_000L);
			if (p >= 0) {
				return new float[]{SCRATCH, p};
			}
			if (sitting) {
				return new float[]{DANGLE, tick}; // Beine baumeln lassen
			}
		}
		return null;
	}

	/** Fortschritt 0..1 im Zeitfenster eines Zyklus, sonst −1. */
	private static float window(long now, long cycle, long offset, long length) {
		long pos = Math.floorMod(now - offset, cycle);
		return pos < length ? pos / (float) length : -1.0F;
	}

	public static void attach(Object miniState, float[] emote) {
		if (emote == null) {
			STATES.remove(miniState);
		} else {
			STATES.put(miniState, emote);
		}
	}

	public static float[] of(Object miniState) {
		return STATES.get(miniState);
	}

	/** Weiches Ein- und Ausblenden über den Verlauf (0 → 1 → 0). */
	public static float envelope(float p) {
		return (float) Math.sin(Math.PI * Math.min(1.0F, Math.max(0.0F, p)));
	}
}
