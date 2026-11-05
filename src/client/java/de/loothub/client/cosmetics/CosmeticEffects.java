package de.loothub.client.cosmetics;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Partikel-Cosmetics (nur Darstellung, nur bei einem selbst sichtbar): Fußspuren aus Funken oder Flammen beim Laufen
 * und eine Energie-Spur hinter Spielern in der Flug-Haltung.
 */
public final class CosmeticEffects {
	private CosmeticEffects() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(CosmeticEffects::tick);
	}

	private static int blend(int a, int b, float t) {
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused() || !WidgetConfig.get().cosmetics) {
			return;
		}
		RandomSource r = mc.level.getRandom();
		for (Player p : mc.level.players()) {
			if (p.isInvisible() || p.isSpectator()) {
				continue;
			}
			Cosmetics.Snapshot s = Cosmetics.of(p.getId());
			if (s == null) {
				continue;
			}
			double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
			double speed = Math.sqrt(dx * dx + dz * dz);
			// Fußspuren: abwechselnd links und rechts, nur am Boden in Bewegung
			String steps = s.values().get(Cosmetics.FOOTSTEPS);
			if (steps != null && s.set().contains(Cosmetics.FOOTSTEPS) && p.onGround() && speed > 0.03
					&& p.tickCount % 2 == 0) {
				double yaw = Math.toRadians(p.yBodyRot);
				double side = (p.tickCount / 2 % 2 == 0 ? 0.18 : -0.18);
				double x = p.getX() + Math.cos(yaw) * side, z = p.getZ() + Math.sin(yaw) * side, y = p.getY() + 0.05;
				if (steps.equals("flames")) {
					for (int i = 0; i < 3; i++) {
						mc.level.addParticle(ParticleTypes.SMALL_FLAME, x + (r.nextDouble() - 0.5) * 0.2, y,
								z + (r.nextDouble() - 0.5) * 0.2, 0.0, 0.01 + r.nextDouble() * 0.015, 0.0);
					}
					if (r.nextInt(2) == 0) {
						mc.level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.008, 0.0);
					}
				} else if (steps.equals("sparks")) {
					for (int i = 0; i < 4; i++) {
						mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + (r.nextDouble() - 0.5) * 0.2, y,
								z + (r.nextDouble() - 0.5) * 0.2, (r.nextDouble() - 0.5) * 0.08, 0.05, (r.nextDouble() - 0.5) * 0.08);
					}
				}
			}
			// Flug-Spur: leuchtender Streifen in Aura-Farbe (oder weiß) entlang des Weges
			// Flug-Spur: nur mit eingeschalteter Aura – ein Schweif aus Aura-Flammen (heller Kern, weicher Rand)
			String aura = s.values().get(Cosmetics.AURA);
			if (s.set().contains(Cosmetics.FLY_TRAIL) && s.set().contains(Cosmetics.AURA) && aura != null
					&& Motion.flyOf(p.getId()) > 0.5F && speed > 0.05) {
				int rgb = AuraTextures.parse(aura);
				int core = blend(rgb, 0xFFFFFF, 0.55F);
				DustParticleOptions outer = new DustParticleOptions(rgb, 2.4F);
				DustParticleOptions inner = new DustParticleOptions(core, 1.3F);
				double cy = p.getY() + 0.45;
				for (int i = 0; i < 6; i++) {
					double f = i / 6.0;
					double x = p.xo + (p.getX() - p.xo) * f - dx * 1.2, z = p.zo + (p.getZ() - p.zo) * f - dz * 1.2;
					double spread = 0.35;
					mc.level.addParticle(outer, x + (r.nextDouble() - 0.5) * spread, cy + (r.nextDouble() - 0.5) * spread,
							z + (r.nextDouble() - 0.5) * spread, 0.0, 0.0, 0.0);
					if (i % 2 == 0) {
						mc.level.addParticle(inner, x, cy + (r.nextDouble() - 0.5) * 0.1, z, 0.0, 0.0, 0.0);
					}
				}
			}
		}
	}
}
