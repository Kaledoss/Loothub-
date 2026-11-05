package de.loothub.client.quick;

/** Ein Wert, der zeitbasiert sanft zwischen 0 und 1 wandert (für Animationen, unabhängig von der Bildrate). */
public final class Transition {
	private double from;
	private double to;
	private long start;
	private long duration = 1;

	/** Ab jetzt in {@code ms} Millisekunden zum Ziel 0 oder 1 laufen (vom aktuellen Wert aus). */
	public void to(double target, long ms) {
		double now = raw();
		if (target == to && now == target) {
			return;
		}
		from = now;
		to = target;
		start = System.currentTimeMillis();
		// Restweg anteilig – bricht man mitten in der Animation ab, geht es gleich schnell zurück
		duration = Math.max(1, (long) (ms * Math.abs(target - now)));
	}

	/** Linearer Fortschritt 0 … 1. */
	public double raw() {
		double t = Math.min(1.0, (System.currentTimeMillis() - start) / (double) duration);
		return from + (to - from) * t;
	}

	/** Weich an- und auslaufend (Smoothstep). */
	public float eased() {
		double v = raw();
		return (float) (v * v * (3 - 2 * v));
	}

	public boolean atZero() {
		return to == 0 && raw() <= 0.0001;
	}
}
