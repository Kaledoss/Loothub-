package de.loothub.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leistungsmessung der Loothub-Funktionen: wie viel Zeit jede Funktion je Sekunde braucht (über 10 Sekunden gemittelt)
 * und ihr langsamster Einzelaufruf. Kostet selbst fast nichts (zwei Zeitstempel je Aufruf).
 */
public final class Perf {
	private static final long WINDOW_NS = 10_000_000_000L;
	/** laufendes Fenster: {Summe ns, Aufrufe, max ns} */
	private static final Map<String, long[]> CUR = new ConcurrentHashMap<>();
	private static volatile List<Entry> last = List.of();
	private static long windowStart = System.nanoTime();

	public record Entry(String name, double msPerSec, double avgUs, double maxMs, long callsPerSec) {
	}

	private Perf() {
	}

	public static long start() {
		return System.nanoTime();
	}

	public static void end(String name, long t0) {
		long now = System.nanoTime(), dt = now - t0;
		long[] a = CUR.computeIfAbsent(name, k -> new long[3]);
		a[0] += dt;
		a[1]++;
		if (dt > a[2]) {
			a[2] = dt;
		}
		if (now - windowStart > WINDOW_NS) {
			roll(now);
		}
	}

	private static synchronized void roll(long now) {
		double secs = (now - windowStart) / 1e9;
		if (secs < 1) {
			return;
		}
		List<Entry> out = new ArrayList<>();
		for (var e : CUR.entrySet()) {
			long[] a = e.getValue();
			if (a[1] == 0) {
				continue;
			}
			out.add(new Entry(e.getKey(), a[0] / 1e6 / secs, a[0] / 1e3 / a[1], a[2] / 1e6, Math.round(a[1] / secs)));
		}
		out.sort((x, y) -> Double.compare(y.msPerSec(), x.msPerSec()));
		last = out;
		CUR.clear();
		windowStart = now;
	}

	/** Ergebnis der letzten 10 Sekunden, teuerste zuerst. */
	public static List<Entry> snapshot() {
		return last;
	}

	/** Kurzfassung für Meldungen: die 8 teuersten Funktionen. */
	public static String summary() {
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Entry e : last) {
			if (n++ >= 8) {
				break;
			}
			sb.append(String.format(java.util.Locale.ROOT, "%s: %.2f ms/s (max %.1f ms)%n", e.name(), e.msPerSec(), e.maxMs()));
		}
		return sb.toString();
	}
}
