package de.loothub.client.cosmetics;

/**
 * Herumstreifen: Begleiter bleiben nicht starr an ihrem Platz, sondern suchen sich alle paar Sekunden einen neuen
 * Punkt in der Nähe ihres Platzes und gleiten weich dorthin. Rein aus der Zeit berechnet (keine Zufallszustände), für
 * jeden Begleiter in eigenem Takt.
 */
public final class Roam {
	private Roam() {
	}

	/**
	 * @param side   Seite des Begleiters (1 rechts, -1 links) – er bleibt überwiegend auf seiner Seite
	 * @param radius wie weit er sich vom Platz entfernt (Blöcke)
	 * @return {dx, dz} Versatz im Ebenen-Raum des Spielers
	 */
	public static float[] offset(int playerId, int slot, float ticks, float side, float radius) {
		float period = 160.0F + (slot * 37 % 60); // 8–11 Sekunden je Ziel
		float pos = ticks / period + slot * 0.37F + (playerId % 13) * 0.11F;
		int seg = (int) Math.floor(pos);
		float f = pos - seg;
		// erst eine Weile stehen bleiben, dann weich zum nächsten Ziel
		float k = f < 0.55F ? 0.0F : smooth((f - 0.55F) / 0.45F);
		float[] a = point(playerId, slot, seg, side, radius);
		float[] b = point(playerId, slot, seg + 1, side, radius);
		return new float[]{a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k};
	}

	private static float[] point(int playerId, int slot, int seg, float side, float radius) {
		long h = (seg * 0x9E3779B97F4A7C15L) ^ (slot * 0xC2B2AE3D27D4EB4FL) ^ (playerId * 0x165667B19E3779F9L);
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		float r1 = (h & 0xFFFF) / 65535.0F, r2 = ((h >>> 16) & 0xFFFF) / 65535.0F;
		float dx = side * (r1 - 0.3F) * radius; // eher nach außen als zum Spieler hin
		float dz = (r2 - 0.4F) * radius * 1.2F; // eher hinter als vor dem Spieler
		return new float[]{dx, dz};
	}

	private static float smooth(float x) {
		x = Math.max(0.0F, Math.min(1.0F, x));
		return x * x * (3.0F - 2.0F * x);
	}
}
