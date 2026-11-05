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
					&& p.tickCount % (p.isSprinting() ? 2 : 3) == 0) {
				double yaw = Math.toRadians(p.yBodyRot);
				double side = (p.tickCount / 3 % 2 == 0 ? 0.18 : -0.18);
				double x = p.getX() + Math.cos(yaw) * side, z = p.getZ() + Math.sin(yaw) * side, y = p.getY() + 0.05;
				if (steps.equals("flames")) {
					mc.level.addParticle(ParticleTypes.SMALL_FLAME, x, y, z, 0.0, 0.012, 0.0);
					if (r.nextInt(3) == 0) {
						mc.level.addParticle(ParticleTypes.SMALL_FLAME, x + (r.nextDouble() - 0.5) * 0.15, y,
								z + (r.nextDouble() - 0.5) * 0.15, 0.0, 0.02, 0.0);
					}
				} else if (steps.equals("sparks")) {
					for (int i = 0; i < 2; i++) {
						mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + (r.nextDouble() - 0.5) * 0.2, y,
								z + (r.nextDouble() - 0.5) * 0.2, (r.nextDouble() - 0.5) * 0.08, 0.05, (r.nextDouble() - 0.5) * 0.08);
					}
				}
			}
			// Flug-Spur: leuchtender Streifen in Aura-Farbe (oder weiß) entlang des Weges
			if (s.set().contains(Cosmetics.FLY_TRAIL) && Motion.flyOf(p.getId()) > 0.5F && speed > 0.05) {
				String aura = s.values().get(Cosmetics.AURA);
				int rgb = aura != null ? AuraTextures.parse(aura) : 0xE8F4FF;
				DustParticleOptions dust = new DustParticleOptions(rgb, 1.6F);
				double cy = p.getY() + 0.5;
				for (int i = 0; i < 4; i++) {
					double f = i / 4.0;
					double x = p.xo + (p.getX() - p.xo) * f - dx * 1.5, z = p.zo + (p.getZ() - p.zo) * f - dz * 1.5;
					mc.level.addParticle(dust, x + (r.nextDouble() - 0.5) * 0.25, cy + (r.nextDouble() - 0.5) * 0.25,
							z + (r.nextDouble() - 0.5) * 0.25, 0.0, 0.0, 0.0);
				}
				if (r.nextInt(2) == 0) {
					mc.level.addParticle(ParticleTypes.END_ROD, p.getX() - dx * 2, cy, p.getZ() - dz * 2,
							-dx * 0.1, 0.0, -dz * 0.1);
				}
			}
		}
	}
}
