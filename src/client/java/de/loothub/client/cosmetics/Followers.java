package de.loothub.client.cosmetics;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/**
 * Eigenständige Bewegung für Begleiter (fliegende/krabbelnde Mini-Mes, Haustiere). Ihre Position wird relativ zum
 * Spieler geführt (Abstand in Weltrichtungen): Läuft, sprintet oder fliegt der Spieler, bewegen sie sich exakt mit –
 * kein Zurückfallen, kein Ruckeln. Gefedert wird nur der Platz um den Spieler: beim Drehen schwenken sie weich
 * hinterher, dazu schweben sie in ihrem eigenen Takt herum und schauen in ihre Bewegungsrichtung.
 */
public final class Followers {
	private static final class F {
		/** Abstand zum Spieler (Welt-Achsen) und dessen Änderungstempo. */
		double ox, oy, oz, vx, vy, vz;
		float yaw;
		long last;
		boolean init;
		int player;
	}

	private static final Map<Long, F> MAP = new HashMap<>();
	/** Mindestabstand zwischen zwei Begleitern bzw. zum Spieler (Blöcke). */
	private static final double SPACING = 0.75, BODY = 0.65;

	private Followers() {
	}

	/** Oberkante des Bodens an (x, z), gesucht von etwas über {@code refY} nach unten; sonst refY. */
	public static double groundY(double x, double refY, double z) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) {
			return refY;
		}
		int bx = Mth.floor(x), bz = Mth.floor(z);
		int top = Mth.floor(refY + 1.2);
		for (int by = top; by >= top - 4; by--) {
			net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(bx, by, bz);
			var shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (!shape.isEmpty()) {
				double h = by + shape.max(net.minecraft.core.Direction.Axis.Y);
				if (h <= refY + 1.25) {
					return h;
				}
			}
		}
		return refY;
	}

	/** Weltpunkt → Ebenen-Raum des Spielers (aufrecht; für Ziele wie andere Tiere). */
	public static float[] worldToLayer(AvatarRenderState s, double wx, double wy, double wz) {
		double theta = Math.toRadians(180.0F - s.bodyRot);
		double c = Math.cos(theta), sn = Math.sin(theta);
		double dx = wx - s.x, dz = wz - s.z;
		double lx = dx * c - dz * sn, lz = dx * sn + dz * c;
		return new float[]{(float) -lx, (float) (1.501 - (wy - s.y)), (float) lz};
	}

	/** Ebenen-Raum des Spielers → Weltpunkt. */
	public static double[] layerToWorld(AvatarRenderState s, float x, float y, float z) {
		double theta = Math.toRadians(180.0F - s.bodyRot);
		double c = Math.cos(theta), sn = Math.sin(theta);
		double X = -x, Z = z;
		return new double[]{s.x + X * c + Z * sn, s.y + 1.501 - y, s.z - X * sn + Z * c};
	}

	/**
	 * @param tx,ty,tz Zielplatz im Ebenen-Raum (aufrecht, y nach unten, Füße bei 1,5, +z = hinter dem Spieler)
	 * @param stiffness wie fest die Feder zieht (höher = folgt enger)
	 * @param wander wie weit er um seinen Platz herumschwebt (Blöcke)
	 * @param wanderY Anteil des Herumschwebens in der Höhe (0 = bleibt am Boden)
	 * @return {x, y, z, Drehung relativ zum Spieler in Grad, eigenes Tempo relativ zum Spieler (Blöcke/s)}
	 */
	public static float[] follow(AvatarRenderState s, int slot, float tx, float ty, float tz, float stiffness, float wander,
			float wanderY) {
		return follow(s, slot, tx, ty, tz, stiffness, wander, wanderY, false);
	}

	/** Mit {@code ground}: folgt der Bodenhöhe unter sich (klettert Stufen hoch, geht Absätze hinunter). */
	public static float[] follow(AvatarRenderState s, int slot, float tx, float ty, float tz, float stiffness, float wander,
			float wanderY, boolean ground) {
		long key = ((long) s.id << 5) | slot;
		F f = MAP.computeIfAbsent(key, k -> new F());
		f.player = s.id;
		long now = System.nanoTime();
		float t = now / 1.0e9F;
		float seed = slot * 1.73F + (s.id % 97) * 0.31F;
		// Zielplatz mit Herumschweben (jeder Begleiter in seinem eigenen Takt)
		float wx = tx + (float) Math.sin(t * 0.55F + seed) * wander;
		float wy = ty + (float) Math.sin(t * 0.83F + seed * 2.1F) * wander * wanderY;
		float wz = tz + (float) Math.cos(t * 0.47F + seed * 0.7F) * wander;
		double theta = Math.toRadians(180.0F - s.bodyRot);
		double c = Math.cos(theta), sn = Math.sin(theta);
		// Ziel-Abstand in Weltrichtungen (Ebenen-Raum → Welt, ohne Spielerposition)
		double X = -wx, Z = wz;
		double gx = X * c + Z * sn;
		double gz = -X * sn + Z * c;
		double gy = 1.501 - wy;
		if (ground && f.init) {
			gy += Mth.clamp(groundY(s.x + f.ox, s.y, s.z + f.oz) - s.y, -2.0, 1.2);
		}
		float dt = f.last == 0 ? 0.0F : Math.min(0.05F, (now - f.last) / 1.0e9F);
		if (!f.init || (now - f.last) > 2_000_000_000L) {
			f.ox = gx;
			f.oy = gy;
			f.oz = gz;
			f.vx = f.vy = f.vz = 0;
			f.yaw = s.bodyRot;
			f.init = true;
			f.last = now;
		} else if (dt > 0.002F) {
			f.last = now;
			// gedämpfte Feder auf den Abstand: weich, ohne langes Nachschwingen
			double damp = 2.0 * Math.sqrt(stiffness) * 0.85;
			f.vx += (stiffness * (gx - f.ox) - damp * f.vx) * dt;
			f.vy += (stiffness * (gy - f.oy) - damp * f.vy) * dt;
			f.vz += (stiffness * (gz - f.oz) - damp * f.vz) * dt;
			// Abstand halten: Begleiter desselben Spielers schieben sich sanft auseinander, und keiner läuft durch
			// den Spieler hindurch – so schneiden sie sich nicht
			double push = Math.max(12.0, stiffness * 3.0);
			for (F o : MAP.values()) {
				if (o == f || o.player != f.player || !o.init || now - o.last > 500_000_000L) {
					continue;
				}
				double dx = f.ox - o.ox, dy = f.oy - o.oy, dz = f.oz - o.oz;
				double d = Math.sqrt(dx * dx + dy * dy * 0.5 + dz * dz);
				if (d < SPACING) {
					if (d < 1.0e-4) {
						dx = (f.player + slot) % 2 == 0 ? 0.01 : -0.01;
						d = 0.01;
					}
					double k = push * (SPACING - d) / d;
					f.vx += dx * k * dt;
					f.vz += dz * k * dt;
				}
			}
			double hd = Math.sqrt(f.ox * f.ox + f.oz * f.oz);
			if (hd < BODY && f.oy > -0.3 && f.oy < 2.0) {
				double k = push * (BODY - hd) / Math.max(hd, 1.0e-3);
				f.vx += (hd < 1.0e-3 ? 0.01 : f.ox) * k * dt;
				f.vz += (hd < 1.0e-3 ? 0.0 : f.oz) * k * dt;
			}
			f.ox += f.vx * dt;
			f.oy += f.vy * dt;
			f.oz += f.vz * dt;
			// Blickrichtung: bewegt er sich selbst (Aufholen, Herumschweben), in diese Richtung – sonst wie der Spieler
			double own = Math.sqrt(f.vx * f.vx + f.vz * f.vz);
			float want = own > 1.0 ? (float) Math.toDegrees(Math.atan2(-f.vx, f.vz)) : s.bodyRot;
			f.yaw += Mth.wrapDegrees(want - f.yaw) * (1.0F - (float) Math.exp(-dt * 4.0F));
		}
		// Welt-Abstand → Ebenen-Raum
		double lx = f.ox * c - f.oz * sn, lz = f.ox * sn + f.oz * c;
		if (MAP.size() > 400) {
			long cut = now - 30_000_000_000L;
			MAP.values().removeIf(v -> v.last < cut);
		}
		return new float[]{(float) -lx, (float) (1.501 - f.oy), (float) lz, Mth.wrapDegrees(f.yaw - s.bodyRot),
				(float) Math.sqrt(f.vx * f.vx + f.vz * f.vz)};
	}
}
