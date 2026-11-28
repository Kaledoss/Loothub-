package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import de.loothub.client.jobs.WidgetConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;

/**
 * Stimmung der eigenen Haustiere: Freude (drei Freudensprünge mit einer Drehung, dazu Herzchen) bei Level-up, seltenem
 * Fisch oder Heldentat; Schreck (zusammenzucken und zittern), wenn man mit wenig Leben getroffen wird – bei sehr wenig
 * Leben zittern sie leise weiter.
 */
public final class PetMood {
	private static final long JOY_MS = 1_600L, SCARED_MS = 1_500L;
	private static volatile long joyAt = -JOY_MS;
	private static volatile long scaredAt = -SCARED_MS;
	private static volatile boolean veryLow;
	private static boolean heartsPending;
	private static int lastHurt;

	private PetMood() {
	}

	public static void register() {
		de.loothub.client.util.Guard.tick("PetMood", PetMood::tick);
	}

	public static void joy() {
		joyQuiet();
		PetLevels.joy();
	}

	/** Freuen ohne Erfahrung (z. B. beim Haustier-Level-up selbst). */
	public static void joyQuiet() {
		joyAt = System.currentTimeMillis();
		heartsPending = PetLevels.ownBest() >= PetLevels.HEARTS; // Herzchen ab Haustier-Level 2
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		boolean on = WidgetConfig.get().petReactions && !WidgetConfig.get().pets.isEmpty();
		float frac = mc.player.getHealth() / Math.max(1F, mc.player.getMaxHealth());
		veryLow = on && frac <= 0.2F && mc.player.isAlive();
		int hurt = mc.player.hurtTime;
		if (on && hurt > lastHurt && frac <= 0.3F) {
			scaredAt = System.currentTimeMillis();
		}
		lastHurt = hurt;
		if (heartsPending) {
			heartsPending = false;
			if (on && System.currentTimeMillis() - joyAt < JOY_MS) {
				var r = mc.level.random;
				for (int i = 0; i < 6; i++) {
					mc.level.addParticle(ParticleTypes.HEART, mc.player.getX() + (r.nextDouble() - 0.5) * 2.0,
							mc.player.getY() + 0.6 + r.nextDouble() * 0.8, mc.player.getZ() + (r.nextDouble() - 0.5) * 2.0, 0, 0.05, 0);
				}
			}
		}
	}

	/** Vor dem Skalieren aufrufen, im Koordinatensystem des Haustiers (y negativ = nach oben). */
	public static void apply(PoseStack pose, int slot, float t) {
		if (!WidgetConfig.get().petReactions) {
			return;
		}
		long now = System.currentTimeMillis();
		// leicht versetzt je Haustier, damit nicht alle exakt gleich springen
		long sinceJoy = now - joyAt - slot * 90L;
		if (sinceJoy >= 0 && sinceJoy < JOY_MS) {
			float ph = sinceJoy / (float) JOY_MS;
			float env = Mth.sin(ph * Mth.PI);
			float hop = Math.abs(Mth.sin(ph * 3F * Mth.PI)) * 0.45F * Math.min(1F, env * 2F);
			pose.translate(0.0F, -hop, 0.0F);
			if (PetLevels.ownLevel(slot) >= PetLevels.FLIP) {
				// ab Haustier-Level 5: Salto statt Drehung
				pose.translate(0.0F, -0.5F, 0.0F);
				pose.mulPose(Axis.XP.rotationDegrees(smooth(ph) * 360F));
				pose.translate(0.0F, 0.5F, 0.0F);
			} else {
				pose.mulPose(Axis.YP.rotationDegrees(smooth(ph) * 360F));
			}
			return;
		}
		long sinceScare = now - scaredAt;
		if (sinceScare >= 0 && sinceScare < SCARED_MS) {
			float ph = sinceScare / (float) SCARED_MS;
			float k = 1F - ph;
			// kurzes Zusammenzucken nach hinten, dann Zittern, das abklingt
			float jolt = ph < 0.15F ? Mth.sin(ph / 0.15F * Mth.PI) * 0.12F : 0F;
			pose.translate(Mth.sin(t * 2.6F + slot) * 0.04F * k, -jolt * 0.5F, jolt);
			return;
		}
		if (veryLow) {
			pose.translate(Mth.sin(t * 2.2F + slot) * 0.015F, 0.0F, 0.0F);
		}
	}

	private static float smooth(float x) {
		return x * x * (3F - 2F * x);
	}
}
