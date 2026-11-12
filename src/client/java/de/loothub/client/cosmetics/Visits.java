package de.loothub.client.cosmetics;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Besuch: Stehen zwei Spieler mit Mini-Mes nah beieinander, laufen/fliegen ihre freien Mini-Mes ab und zu zueinander,
 * treffen sich in der Mitte, winken und klatschen ab. Der Zeitplan hängt nur an den beiden UUIDs – beide Spieler sehen
 * den Besuch also zur selben Zeit (wenn beide Loothub haben).
 */
public final class Visits {
	private static final double RANGE = 7.0;
	private static final long PERIOD_MS = 45_000L, VISIT_MS = 9_000L;

	/** Ein laufender Besuch: Treffpunkt im Ebenen-Raum des Spielers, Blickrichtung (Grad) und Fortschritt 0..1. */
	public record Visit(float x, float z, float yaw, float progress) {
	}

	private Visits() {
	}

	/** Besuch für Mini-Me {@code slot} (nur freie Plätze 2–5) oder null. */
	public static Visit of(AvatarRenderState s, int slot, float side) {
		if (slot < 2) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		Entity me = mc.level.getEntity(s.id);
		if (!(me instanceof Player self)) {
			return null;
		}
		Player best = null;
		double bestD = RANGE * RANGE;
		for (Player p : mc.level.players()) {
			if (p == self || p.isSpectator() || p.isInvisible()) {
				continue;
			}
			double dx = p.getX() - s.x, dz = p.getZ() - s.z;
			double d = dx * dx + dz * dz;
			if (d < bestD && d > 1.0 && Math.abs(p.getY() - s.y) < 3.0 && Cosmetics.playerHas(p, Cosmetics.MINI_ME)) {
				best = p;
				bestD = d;
			}
		}
		if (best == null) {
			return null;
		}
		UUID a = self.getUUID(), b = best.getUUID();
		long pair = a.getLeastSignificantBits() ^ b.getLeastSignificantBits() ^ a.getMostSignificantBits() ^ b.getMostSignificantBits();
		long now = System.currentTimeMillis();
		long phase = Math.floorMod(now + Math.floorMod(pair, PERIOD_MS), PERIOD_MS);
		if (phase >= VISIT_MS) {
			return null;
		}
		float progress = phase / (float) VISIT_MS;
		// Treffpunkt: in der Mitte, die Mini-Mes nebeneinander quer zur Verbindungslinie, eigene Seite etwas näher
		double dx = best.getX() - s.x, dz = best.getZ() - s.z;
		double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
		double ux = dx / len, uz = dz / len;
		double spread = (slot - 2.5) * 0.45;
		double wx = s.x + dx * 0.5 - ux * 0.35 + -uz * spread;
		double wz = s.z + dz * 0.5 - uz * 0.35 + ux * spread;
		float[] local = worldToLayer(s, wx, wz);
		// Blick zum anderen Spieler (relativ zum Spieler-Körper)
		float worldYaw = (float) Math.toDegrees(Math.atan2(-ux, uz));
		float yaw = net.minecraft.util.Mth.wrapDegrees(worldYaw - s.bodyRot);
		return new Visit(local[0], local[1], yaw, progress);
	}

	/** Umkehrung von {@link Followers#layerToWorld}: Weltpunkt (x, z) → Ebenen-Raum des Spielers. */
	static float[] worldToLayer(AvatarRenderState s, double wx, double wz) {
		double theta = Math.toRadians(180.0F - s.bodyRot);
		double c = Math.cos(theta), sn = Math.sin(theta);
		double dx = wx - s.x, dz = wz - s.z;
		double X = dx * c - dz * sn;
		double Z = dx * sn + dz * c;
		return new float[]{(float) -X, (float) Z};
	}
}
