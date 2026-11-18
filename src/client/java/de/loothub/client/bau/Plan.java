package de.loothub.client.bau;

import java.util.HashMap;
import java.util.Map;

/** Eine platzierte Schematic aus Litematica, mit dem, was Loothub+ dazu weiß (Fortschritt, Materialien). */
public final class Plan {
	final String id;
	/** Die Litematica-Platzierung (nur in {@link LitematicaBridge} benutzt). */
	volatile Object handle;
	String name = "";
	String file = "";
	boolean enabled;
	int minX, minY, minZ, maxX, maxY, maxZ;
	/** Materialien der ganzen Schematic (Item-ID → Anzahl); null, solange gezählt wird. */
	volatile Map<String, Long> totals;
	/** Was noch nicht steht (Litematica-Materialliste); null = (noch) nicht gezählt. */
	volatile Map<String, Long> missing;
	/** Hat Loothub+ Litematica schon zählen lassen? */
	boolean countRequested;

	/** Fortschritt je Chunk: {Blöcke, richtig, falsch}. */
	final Map<Long, int[]> chunkCounts = new HashMap<>();
	int chunkTotal;
	long correct, wrong, counted;

	Plan(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String file() {
		return file;
	}

	void setBox(int x0, int y0, int z0, int x1, int y1, int z1) {
		minX = x0;
		minY = y0;
		minZ = z0;
		maxX = x1;
		maxY = y1;
		maxZ = z1;
	}

	/** Abstand (Blöcke) eines Punktes zum Rahmen der Schematic; 0 = innen. */
	double distance(double x, double y, double z) {
		double dx = Math.max(0, Math.max(minX - x, x - (maxX + 1)));
		double dy = Math.max(0, Math.max(minY - y, y - (maxY + 1)));
		double dz = Math.max(0, Math.max(minZ - z, z - (maxZ + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Blöcke der ganzen Schematic (aus der Materialliste; sonst die bisher gezählten). */
	long totalBlocks() {
		Map<String, Long> t = totals;
		if (t == null || t.isEmpty()) {
			return counted;
		}
		long sum = 0;
		for (long v : t.values()) {
			sum += v;
		}
		return Math.max(sum, counted);
	}

	/** Alle Chunks der Schematic schon einmal angesehen? */
	boolean fullyScanned() {
		return chunkTotal > 0 && chunkCounts.size() >= chunkTotal;
	}

	void sumUp() {
		long c = 0, w = 0, n = 0;
		for (int[] v : chunkCounts.values()) {
			n += v[0];
			c += v[1];
			w += v[2];
		}
		counted = n;
		correct = c;
		wrong = w;
	}
}
