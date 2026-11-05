package de.loothub.client.cosmetics;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Kleine Gesten der Mini-Mes: winken, wenn der Spieler eine Weile stillsteht; jubeln beim Job-Level-Up;
 * sich strecken und gähnen, wenn sie in der AFK-Ansicht mitschlafen. Jedes Mini-Me zu seiner eigenen Zeit.
 */
public final class Emotes {
	public static final int NONE = 0, WAVE = 1, CHEER = 2, YAWN = 3;
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

	/** Welche Geste Mini-Me {@code slot} des Spielers gerade macht: {Art, Fortschritt}. */
	public static float[] pick(Object playerState, int slot, boolean sleeping) {
		if (!Cosmetics.has(playerState, Cosmetics.EMOTES)) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (Motion.local(playerState) && now - cheerAt < CHEER_MS) {
			return new float[]{CHEER, (now - cheerAt) / (float) CHEER_MS};
		}
		if (sleeping) {
			float p = window(now, 17_000L, slot * 2_700L, 2_200L);
			return p < 0 ? null : new float[]{YAWN, p};
		}
		if (Motion.idle(playerState) > 6.0F) {
			float p = window(now, 13_000L, slot * 2_100L, 2_400L);
			return p < 0 ? null : new float[]{WAVE, p};
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
