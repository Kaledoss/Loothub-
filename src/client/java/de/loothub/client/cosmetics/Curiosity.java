package de.loothub.client.cosmetics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Kleine Eigenheiten der Haustiere: ab und zu fliegen/hüpfen sie neugierig zu einem Tier oder Spieler in der Nähe
 * und kommen nach ein paar Sekunden zurück; der Schleim wirbelt beim Aufkommen etwas Bodenstaub auf.
 */
public final class Curiosity {
	private static final class C {
		int target = -1;
		long until, next;
		boolean airborne;
	}

	private static final Map<Long, C> MAP = new HashMap<>();

	private Curiosity() {
	}

	private static C of(AvatarRenderState s, int slot) {
		return MAP.computeIfAbsent(((long) s.id << 3) | slot, k -> new C());
	}

	/** Ziel im Ebenen-Raum, wenn das Haustier gerade neugierig ist – sonst null. */
	public static float[] target(AvatarRenderState s, int slot, boolean ground) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		C c = of(s, slot);
		long now = System.currentTimeMillis();
		if (c.target >= 0 && now < c.until) {
			var e = mc.level.getEntity(c.target);
			if (e != null && e.isAlive() && e.distanceToSqr(s.x, s.y, s.z) < 100) {
				// knapp neben dem Ziel, auf Kopfhöhe (Schwebende) bzw. am Boden (Schleim)
				double wy = ground ? e.getY() : e.getY() + e.getBbHeight() + 0.2;
				float[] l = Followers.worldToLayer(s, e.getX(), wy, e.getZ());
				l[0] += slot == 0 ? 0.6F : -0.6F;
				l[1] -= 0.0F;
				return l;
			}
			c.target = -1;
		}
		if (now < c.next) {
			return null;
		}
		c.next = now + 15_000L + (long) (Math.random() * 15_000L);
		if (Math.random() < 0.5) {
			return null;
		}
		List<LivingEntity> near = mc.level.getEntitiesOfClass(LivingEntity.class,
				new AABB(s.x - 6, s.y - 2, s.z - 6, s.x + 6, s.y + 3, s.z + 6),
				e -> e.getId() != s.id && e.isAlive() && !e.isInvisible() && e.getType() != net.minecraft.world.entity.EntityType.ARMOR_STAND);
		if (near.isEmpty()) {
			return null;
		}
		c.target = near.get((int) (Math.random() * near.size())).getId();
		c.until = now + 3_500L;
		return null;
	}

	/** Schleim: beim Aufkommen (Übergang Luft → Boden) etwas Staub vom Block darunter. */
	public static void landing(AvatarRenderState s, int slot, boolean onGround, float[] layerPos) {
		C c = of(s, slot);
		boolean landed = c.airborne && onGround;
		c.airborne = !onGround;
		Minecraft mc = Minecraft.getInstance();
		if (!landed || mc.level == null) {
			return;
		}
		double[] w = Followers.layerToWorld(s, layerPos[0], layerPos[1], layerPos[2]);
		double gy = Followers.groundY(w[0], s.y + 0.2, w[2]);
		var block = mc.level.getBlockState(BlockPos.containing(w[0], gy - 0.01, w[2]));
		if (block.isAir()) {
			return;
		}
		BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, block);
		for (int i = 0; i < 5; i++) {
			double a = Math.random() * Math.PI * 2;
			mc.level.addParticle(dust, w[0] + Math.cos(a) * 0.25, gy + 0.02, w[2] + Math.sin(a) * 0.25,
					Math.cos(a) * 0.08, 0.06, Math.sin(a) * 0.08);
		}
	}
}
