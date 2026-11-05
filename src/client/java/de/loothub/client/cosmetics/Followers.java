package de.loothub.client.cosmetics;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/**
 * Eigenständige Bewegung für Begleiter (fliegende/krabbelnde Mini-Mes, Haustiere): jeder hat eine eigene Position in
 * der Welt, die weich gefedert seinem Platz neben dem Spieler folgt und dabei etwas herumschwebt. So kleben sie nicht
 * an jeder Bewegung, sondern hängen nach, überholen, drehen sich in ihre Flugrichtung.
 */
public final class Followers {
	private static final class F {
		double x, y, z, vx, vy, vz;
		float yaw;
		long last;
		boolean init;
	}

	private static final Map<Long, F> MAP = new HashMap<>();

	private Followers() {
	}

	/**
	 * @param tx,ty,tz Zielplatz im Ebenen-Raum (aufrecht, y nach unten, Füße bei 1,5, +z = hinter dem Spieler)
	 * @param stiffness wie fest die Feder zieht (höher = folgt enger)
	 * @param wander wie weit er um seinen Platz herumschwebt (Blöcke)
	 * @param wanderY Anteil des Herumschwebens in der Höhe (0 = bleibt am Boden)
	 * @return {x, y, z, Drehung relativ zum Spieler in Grad}
	 */
	public static float[] follow(AvatarRenderState s, int slot, float tx, float ty, float tz, float stiffness, float wander,
			float wanderY) {
		long key = ((long) s.id << 5) | slot;
		F f = MAP.computeIfAbsent(key, k -> new F());
		long now = System.nanoTime();
		float t = now / 1.0e9F;
		float seed = slot * 1.73F + (s.id % 97) * 0.31F;
		// Zielplatz mit Herumschweben (jeder Begleiter in seinem eigenen Takt)
		float wx = tx + (float) Math.sin(t * 0.55F + seed) * wander;
		float wy = ty + (float) Math.sin(t * 0.83F + seed * 2.1F) * wander * wanderY;
		float wz = tz + (float) Math.cos(t * 0.47F + seed * 0.7F) * wander;
		double theta = Math.toRadians(180.0F - s.bodyRot);
		double c = Math.cos(theta), sn = Math.sin(theta);
		// Ebenen-Raum → Welt
		double X = -wx, Z = wz;
		double gx = s.x + X * c + Z * sn;
		double gz = s.z - X * sn + Z * c;
		double gy = s.y + 1.501 - wy;
		float dt = f.last == 0 ? 0.0F : Math.min(0.1F, (now - f.last) / 1.0e9F);
		double far = (gx - f.x) * (gx - f.x) + (gy - f.y) * (gy - f.y) + (gz - f.z) * (gz - f.z);
		if (!f.init || far > 64.0 || f.last == 0 || (now - f.last) > 2_000_000_000L) {
			f.x = gx;
			f.y = gy;
			f.z = gz;
			f.vx = f.vy = f.vz = 0;
			f.yaw = s.bodyRot;
			f.init = true;
			f.last = now;
		} else if (dt > 0.002F) {
			f.last = now;
			// gedämpfte Feder: weich, aber ohne langes Nachschwingen
			double damp = 2.0 * Math.sqrt(stiffness) * 0.85;
			f.vx += (stiffness * (gx - f.x) - damp * f.vx) * dt;
			f.vy += (stiffness * (gy - f.y) - damp * f.vy) * dt;
			f.vz += (stiffness * (gz - f.z) - damp * f.vz) * dt;
			f.x += f.vx * dt;
			f.y += f.vy * dt;
			f.z += f.vz * dt;
			// Blickrichtung: beim Fliegen/Krabbeln in Bewegungsrichtung, sonst wie der Spieler
			double speed = Math.sqrt(f.vx * f.vx + f.vz * f.vz);
			float want = speed > 1.2 ? (float) Math.toDegrees(Math.atan2(-f.vx, f.vz)) : s.bodyRot;
			f.yaw += Mth.wrapDegrees(want - f.yaw) * (1.0F - (float) Math.exp(-dt * 4.0F));
		}
		// Welt → Ebenen-Raum
		double dx = f.x - s.x, dz = f.z - s.z;
		double lx = dx * c - dz * sn, lz = dx * sn + dz * c;
		if (MAP.size() > 400) {
			long cut = now - 30_000_000_000L;
			MAP.values().removeIf(v -> v.last < cut);
		}
		return new float[]{(float) -lx, (float) (s.y + 1.501 - f.y), (float) lz, Mth.wrapDegrees(f.yaw - s.bodyRot)};
	}
}
