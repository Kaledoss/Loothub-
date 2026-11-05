package de.loothub.client.cosmetics;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Weiche Bewegungswerte je Spieler für die Darstellung: wie stark die Mini-Mes beim Drehen und Laufen nachziehen
 * und wie weit der Spieler in der Flug-Haltung liegt (nur Bewegung beim Fliegen, wie beim Schwimmen).
 */
public final class Motion {
	/** Werte je Render-Zustand: {Dreh-Nachzug in Grad, Vorwärts-Tempo, Steig-Tempo, Flug-Haltung 0..1}. */
	private static final Map<Object, float[]> STATES = new WeakHashMap<>();
	private static final Map<Integer, Track> TRACKS = new HashMap<>();

	private static final class Track {
		long last;
		float yaw;
		float fwd;
		float vy;
		float fly;
		int seenTick;
	}

	private Motion() {
	}

	public static void update(Object state, LivingEntity entity, float partialTick) {
		if (!(entity instanceof Player p)) {
			STATES.remove(state);
			return;
		}
		Track tr = TRACKS.computeIfAbsent(p.getId(), k -> new Track());
		long now = System.nanoTime();
		float dt = tr.last == 0 ? 0.0F : Math.min(0.25F, (now - tr.last) / 1.0e9F);
		tr.last = now;
		tr.seenTick = p.tickCount;
		float yaw = Mth.rotLerp(partialTick, p.yBodyRotO, p.yBodyRot);
		if (dt == 0.0F) {
			tr.yaw = yaw;
		}
		tr.yaw += Mth.wrapDegrees(yaw - tr.yaw) * (1.0F - (float) Math.exp(-dt * 5.0F));
		float lag = Mth.clamp(Mth.wrapDegrees(yaw - tr.yaw), -55.0F, 55.0F);
		// Tempo aus der Positionsänderung (klappt auch für andere Spieler)
		double dx = p.getX() - p.xo, dz = p.getZ() - p.zo, dy = p.getY() - p.yo;
		double rad = Math.toRadians(yaw);
		float fwd = (float) (-dx * Math.sin(rad) + dz * Math.cos(rad));
		float k = 1.0F - (float) Math.exp(-dt * 4.0F);
		tr.fwd += (fwd - tr.fwd) * k;
		tr.vy += ((float) dy - tr.vy) * k;
		// Flug-Haltung: nur beim Fliegen in Bewegung, nie mit Elytra, im Wasser oder beim Reiten
		double horiz = Math.sqrt(dx * dx + dz * dz);
		boolean flying;
		if (p == Minecraft.getInstance().player) {
			flying = p.getAbilities().flying;
		} else {
			flying = !p.onGround() && Math.abs(dy) < 0.12 && horiz > 0.12; // andere: schwebend und schnell
		}
		boolean target = WidgetConfig.get().flyPose && flying && horiz > 0.06 && !p.isFallFlying() && !p.isInWater()
				&& !p.isPassenger() && !p.isSleeping();
		tr.fly = Mth.clamp(tr.fly + (target ? dt : -dt) / 0.35F, 0.0F, 1.0F);
		float flyEased = tr.fly * tr.fly * (3.0F - 2.0F * tr.fly);
		STATES.put(state, new float[]{lag, tr.fwd, tr.vy, flyEased});
		if (TRACKS.size() > 200) {
			TRACKS.values().removeIf(t -> Math.abs(p.tickCount - t.seenTick) > 1200);
		}
	}

	private static float get(Object state, int i) {
		float[] v = STATES.get(state);
		return v == null ? 0.0F : v[i];
	}

	/** Wie weit sich der Körper gerade gedreht hat, ohne dass die Mini-Mes schon folgen (Grad). */
	public static float yawLag(Object state) {
		return get(state, 0);
	}

	/** Geglättetes Vorwärts-Tempo (Blöcke pro Tick). */
	public static float forward(Object state) {
		return get(state, 1);
	}

	/** Geglättetes Steig-/Sinktempo (Blöcke pro Tick, positiv = nach oben). */
	public static float vertical(Object state) {
		return get(state, 2);
	}

	/** Flug-Haltung 0..1 (0 = aufrecht, 1 = waagerecht wie beim Superheldenflug). */
	public static float fly(Object state) {
		return get(state, 3);
	}
}
