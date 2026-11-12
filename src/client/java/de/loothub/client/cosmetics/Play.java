package de.loothub.client.cosmetics;

import java.util.List;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Haustiere spielen mit den Mini-Mes: Ab und zu rennt das erste krabbelnde Mini-Me im Kreis um den Spieler, und die
 * laufenden Haustiere (Wolf, Pinguin, Panda, Mini-Zombie) jagen hinterher – der Pinguin rutscht dabei auf dem Bauch.
 * Nur im Stand am Boden, nicht beim Fliegen, Schlafen oder in der Vorschau.
 */
public final class Play {
	private static final long PERIOD_MS = 50_000L, PLAY_MS = 9_000L;
	/** Kreisradius (Blöcke) und Winkeltempo (Bogenmaß je Tick). */
	public static final float RADIUS = 1.6F, SPEED = 0.07F;

	private Play() {
	}

	public static boolean walker(String type) {
		return "wolf".equals(type) || "penguin".equals(type) || "panda".equals(type) || "zombie".equals(type);
	}

	/** Welches Mini-Me gejagt wird (erstes krabbelndes), oder -1. */
	public static int runner(AvatarRenderState s) {
		if (!Cosmetics.has(s, Cosmetics.MINI_ME)) {
			return -1;
		}
		List<String> skins = Cosmetics.miniMes(s);
		for (int i = 4; i < skins.size() && i < 6; i++) {
			if (skins.get(i) != null) {
				return i;
			}
		}
		return -1;
	}

	private static boolean hasWalker(AvatarRenderState s) {
		if (!Cosmetics.has(s, Cosmetics.PETS)) {
			return false;
		}
		for (int slot = 0; slot < Cosmetics.MAX_PETS; slot++) {
			if (walker(Cosmetics.pet(s, slot))) {
				return true;
			}
		}
		return false;
	}

	/** Winkel auf dem Kreis (Bogenmaß) während eines Spiels, sonst NaN. */
	public static float angle(AvatarRenderState s) {
		if (CosmeticsPreviewScreen.is(s) || Motion.fly(s) > 0.3F || de.loothub.client.quick.AfkView.lying(s) > 0.1F
				|| runner(s) < 0 || !hasWalker(s)) {
			return Float.NaN;
		}
		long phase = Math.floorMod(System.currentTimeMillis() + (long) s.id * 7_919L, PERIOD_MS);
		if (phase >= PLAY_MS) {
			return Float.NaN;
		}
		return s.ageInTicks * SPEED;
	}

	/** Punkt auf dem Kreis im Ebenen-Raum des Spielers: {x, z}. */
	public static float[] at(float angle) {
		return new float[]{(float) Math.sin(angle) * RADIUS, (float) Math.cos(angle) * RADIUS};
	}
}
