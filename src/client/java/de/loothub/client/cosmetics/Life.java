package de.loothub.client.cosmetics;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Lebendigkeit der Mini-Mes (nur Anzeige): Atmen, baumelnde Beine, Nachschwingen bei Drehungen/Sprüngen, Stauchen
 * beim Landen, ein natürlicher Blick (schnelle Blickwechsel, schaut zu Lebewesen in der Nähe und auf den Block, den
 * man abbaut) und Reaktionen (zucken bei Schaden, ducken beim Schleichen, mithüpfen, jubeln nach einem Sieg).
 * Stärke über „Lebendigkeit“ (0–100 %).
 */
public final class Life {
	/** Zustand je Mini-Me (Spieler-ID × Platz). */
	private static final class S {
		float lastT = -1;
		float prevVy;
		float squash, squashVel; // Feder: <0 gestaucht, >0 gestreckt
		float lagYaw; // nachschwingender Kopf
		float breathPhase; // Atemzüge mal schneller, mal langsamer
		float armSwing, armSwingVel; // Arme schwingen bei Sprung/Landung nach
		float gazeYaw, gazePitch, gazeToYaw, gazeToPitch;
		float nextGaze;
		int gazeMode;
		float flinch;
		float hop; // eigener Hüpfer (Krabbler), 0..1 läuft ab
		float kick; // gelegentliches Strampeln
		float nextKick;
		long seed;
		float[] special;
		float specialAt = -100;
		float gazeVY, gazeVP; // Blick als Feder (leichtes Überschwingen wie bei echten Kopfbewegungen)
		float bodyYaw, bodyYawVel; // Körper dreht verzögert hinter dem Kopf her
		float armFwd, armFwdVel, prevFwd; // Arme schwingen beim Anlaufen/Anhalten nach
	}

	/** Werte für das Modell (in {@code setupAnim}) je Mini-Render-Zustand. */
	public static final class Pose {
		public float breath, legL, legR, armDrag, armSpread, flinch, duck, armSwing;
		/** Trägheit beim Anlaufen/Anhalten, kleine eigene Armbewegungen, Kopf gegen die Gewichtsverlagerung. */
		public float armFwd, armIdleL, armIdleR, headTilt;
		/** +1 = Mini-Me sitzt/fliegt links vom Spieler (Modell-x positiv), −1 = rechts. */
		public float side = 1F;
		/** Platz des Mini-Mes (0 …). */
		public int slot;
	}

	private static final Map<Long, S> STATES = new HashMap<>();
	private static final Map<AvatarRenderState, Pose> POSES = new IdentityHashMap<>();

	private Life() {
	}

	public static float amount() {
		return Mth.clamp(WidgetConfig.get().miniMeLife, 0, 100) / 100F;
	}

	private static S state(int playerId, int slot) {
		long key = ((long) playerId << 4) | slot;
		S s = STATES.get(key);
		if (s == null) {
			if (STATES.size() > 600) {
				STATES.clear();
			}
			s = new S();
			s.seed = key * 0x9E3779B97F4A7C15L;
			s.nextGaze = 0;
			STATES.put(key, s);
		}
		return s;
	}

	/**
	 * Glattes Rauschen (−1..1): wie ein Sinus, aber ohne Wiederholung – jede Welle anders hoch und lang. Zwei Lagen
	 * übereinander, damit es organisch wirkt. {@code seed} trennt die Bewegungen voneinander.
	 */
	public static float noise(float x, int seed) {
		return value(x, seed) * 0.7F + value(x * 2.13F + 17.7F, seed * 31 + 7) * 0.3F;
	}

	private static float value(float x, int seed) {
		int i = Mth.floor(x);
		float f = x - i;
		float u = f * f * (3F - 2F * f);
		return Mth.lerp(u, hash(i, seed), hash(i + 1, seed));
	}

	private static float hash(int i, int seed) {
		int h = i * 0x27D4EB2D ^ seed * 0x165667B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return (h & 0xFFFF) / 32767.5F - 1F;
	}

	private static float rnd(S s) {
		s.seed ^= s.seed << 13;
		s.seed ^= s.seed >>> 7;
		s.seed ^= s.seed << 17;
		return (s.seed >>> 40) / (float) (1L << 24);
	}

	/** Ergebnis für einen Frame: Lage-Versatz und Skalierung des ganzen Mini-Mes. */
	public record Frame(float dy, float sx, float sy, float rz, float lookYaw, float lookPitch, boolean look, float ry) {
	}

	/**
	 * Ein Schritt für Mini-Me {@code slot}. {@code kind}: 0 Schulter, 1 Flieger, 2 Krabbler.
	 * {@code baseYaw}/{@code basePitch}: bisheriger Blick (zum Spieler usw.), wird hier natürlicher gemacht.
	 */
	public static Frame step(AvatarRenderState player, AvatarRenderState mini, int slot, int kind, float t, boolean sleeping,
			float baseYaw, float basePitch, float side, String trait, float tx, float ty0, float tz) {
		float L = amount();
		S s = state(player.id, slot);
		boolean shy = "shy".equals(trait), brave = "brave".equals(trait), curious = "curious".equals(trait),
				playful = "playful".equals(trait), lazy = "lazy".equals(trait);
		float dt = s.lastT < 0 ? 0 : Mth.clamp((t - s.lastT) / 20F, 0, 0.1F);
		s.lastT = t;
		Pose p = POSES.computeIfAbsent(mini, k -> new Pose());
		p.side = side;
		p.slot = slot;
		if (L <= 0 || sleeping) {
			p.breath = p.legL = p.legR = p.armDrag = p.armSpread = p.flinch = p.duck = p.armSwing = 0;
			p.armFwd = p.armIdleL = p.armIdleR = p.headTilt = 0;
			return new Frame(0, 1, 1, 0, baseYaw, basePitch, false, 0);
		}
		float phase = slot * 1.37F;
		// Atmen: jedes Mini-Me in seinem eigenen Takt
		int ns = (int) (s.seed >>> 33) ^ slot * 977; // eigenes Rauschen je Mini-Me
		s.breathPhase += dt * 20F * (0.10F + slot * 0.007F) * (1F + 0.35F * noise(t * 0.01F, ns));
		float breath = (float) Math.sin(s.breathPhase + phase);
		p.breath = breath * L;

		// Landen und Springen: Feder für Stauchen/Strecken, Arme schwingen nach
		float vy = Motion.vertical(player);
		if (s.prevVy < -0.15F && vy > -0.04F) {
			float hit = Mth.clamp(-s.prevVy * 2.2F, 0.3F, 1.2F);
			s.squashVel -= hit * 9F;
			s.armSwingVel += hit * 6F;
		}
		if (s.prevVy < 0.12F && vy > 0.22F) {
			s.squashVel += 5F; // Absprung: kurz gestreckt
			s.armSwingVel -= 4F;
			if (kind == 2 && s.hop <= 0) {
				s.hop = 1F; // Krabbler hüpfen mit
			}
		}
		s.prevVy = vy;
		if (dt > 0) {
			s.squashVel += (-90F * s.squash - 11F * s.squashVel) * dt;
			s.squash += s.squashVel * dt;
			s.armSwingVel += (-60F * s.armSwing - 7F * s.armSwingVel) * dt;
			s.armSwing += s.armSwingVel * dt;
			s.hop = Math.max(0, s.hop - dt * 2.4F);
		}
		s.squash = Mth.clamp(s.squash, -0.6F, 0.6F);
		p.armSwing = s.armSwing * L;
		// Trägheit: beim Anlaufen schwingen Arme (und baumelnde Beine) nach hinten, beim Anhalten nach vorn aus
		float fwd = Motion.forward(player);
		if (dt > 0) {
			s.armFwdVel += Mth.clamp(fwd - s.prevFwd, -0.3F, 0.3F) * 22F;
			s.armFwdVel += (-55F * s.armFwd - 5.5F * s.armFwdVel) * dt;
			s.armFwd = Mth.clamp(s.armFwd + s.armFwdVel * dt, -0.9F, 0.9F);
		}
		s.prevFwd = fwd;
		p.armFwd = s.armFwd * L;
		// kleine, nie ganz gleiche Eigenbewegungen der Arme (links und rechts in anderem Takt)
		p.armIdleL = noise(t * 0.03F, ns + 3) * 0.07F * L;
		p.armIdleR = noise(t * 0.027F, ns + 4) * 0.07F * L;

		// Kopf schwingt bei Drehungen nach
		float lag = Motion.yawLag(player);
		s.lagYaw += (Mth.clamp(-lag * 0.6F, -35F, 35F) - s.lagYaw) * Math.min(1, dt * 6F);

		// Beine baumeln (Schulter), gelegentlich strampeln
		if (kind == 0) {
			if (t > s.nextKick) {
				s.kick = 1F;
				s.nextKick = t + 120 + rnd(s) * 260;
			}
			s.kick = Math.max(0, s.kick - dt * 1.2F);
			float k = s.kick > 0 ? (float) Math.sin(t * 0.9F) * 0.45F * s.kick : 0;
			// baumeln ohne festen Takt: mal ruhig, mal stärker, die Beine nicht im Gleichschritt
			p.legR = (noise(t * 0.045F, ns + 1) * 0.3F + k) * L;
			p.legL = (noise(t * 0.041F, ns + 2) * 0.3F - k) * L;
		} else {
			p.legL = p.legR = 0;
		}

		// Flieger: Arme/Beine ziehen nach hinten, Körper wippt mit jedem Flügelschlag
		float fly = Motion.fly(player);
		float dy = 0, rz = 0;
		if (kind == 1 || fly > 0.5F) {
			float spd = Mth.clamp(Math.max(0, Motion.forward(player)) * 2.5F + fly * 0.4F, 0, 1);
			p.armDrag = spd * L;
			p.armSpread = (0.15F + 0.2F * spd) * L;
			dy -= (float) Math.sin(t * 0.25F + slot * 1.3F) * 0.03F * L * (0.7F + 0.3F * noise(t * 0.02F, ns + 6));
			rz = Mth.clamp(lag * 0.25F, -15F, 15F) * L; // in die Kurve legen
		} else {
			p.armDrag = 0;
			p.armSpread = 0;
		}

		// Gewicht verlagern: langsames, unregelmäßiges Wiegen; der Kopf gleicht leicht dagegen aus
		float sway = noise(t * 0.012F, ns + 5) * 2.2F * (kind == 0 ? 0.8F : 1.3F) * L;
		rz += sway;
		p.headTilt = (float) Math.toRadians(-sway * 0.7F);

		// Reaktionen
		if (player.hasRedOverlay || System.currentTimeMillis() - lightningAt < 400L) {
			s.flinch = 1F;
		}
		s.flinch = Math.max(0, s.flinch - dt * (brave ? 4.5F : shy ? 1.6F : 2.5F));
		float fk = brave ? 0.45F : shy ? 1.3F : 1F;
		p.flinch = Math.min(1F, s.flinch * fk) * L;
		p.duck = player.isCrouching ? L : 0;
		if (s.hop > 0) {
			dy -= (float) Math.sin(s.hop * Math.PI) * 0.35F * L;
		}
		dy -= s.flinch * 0.06F;

		// Blick: schnelle Blickwechsel statt festem Takt; Ziele: Spieler, Blickrichtung, Lebewesen in der Nähe, Block
		if (t >= s.nextGaze) {
			float r = rnd(s);
			// Charakter: schüchtern schaut meist zum Spieler, neugierig/mutig öfter zu anderen, verspielt wechselt oft
			float toMe = shy ? 0.55F : curious ? 0.15F : 0.3F;
			float toDir = toMe + (lazy ? 0.35F : 0.25F);
			float toOther = toDir + (curious || brave ? 0.4F : shy ? 0.12F : 0.3F);
			s.gazeMode = r < toMe ? 0 : r < toDir ? 1 : r < toOther ? 2 : 3;
			float pace = playful || curious ? 0.6F : lazy ? 1.7F : 1F;
			s.nextGaze = t + (16 + rnd(s) * (s.gazeMode == 2 ? 70 : 50)) * pace;
			if (s.gazeMode == 3) {
				s.gazeToYaw = (rnd(s) - 0.5F) * 120F;
				s.gazeToPitch = (rnd(s) - 0.5F) * 40F;
			}
		}
		float ty = baseYaw, tp = basePitch;
		if (t - s.specialAt > 5 || t < s.specialAt) { // Umgebung nur alle paar Ticks absuchen
			s.special = specialTarget(player, s);
			s.specialAt = t;
		}
		float[] special = s.special;
		if (Motion.local(player) && Emotes.mentioned()) {
			special = new float[]{baseYaw * 0.3F, -35F}; // erwähnt: aufschauen
		}
		if (special != null) {
			ty = special[0];
			tp = special[1];
		} else if (s.gazeMode == 1) {
			ty = Mth.clamp(player.yRot, -60F, 60F);
			tp = Mth.clamp(player.xRot, -40F, 40F);
		} else if (s.gazeMode == 3) {
			ty = s.gazeToYaw;
			tp = s.gazeToPitch;
		}
		// Sakkade: bei einem neuen Blickziel schnell hin, beim Verfolgen (Ziel wandert in kleinen Schritten, z. B. ein
		// laufendes Tier – es wird nur alle paar Ticks neu gesucht) weich hinterher, damit der Kopf nicht ruckelt/zittert
		// Feder mit leichtem Überschwingen: neue Ziele zügig, Verfolgen weich
		float dist = Math.max(Math.abs(ty - s.gazeYaw), Math.abs(tp - s.gazePitch));
		float stiff = dist > 20F ? 150F : 40F, damp = dist > 20F ? 17F : 11F;
		if (dt > 0) {
			s.gazeVY += ((ty - s.gazeYaw) * stiff - s.gazeVY * damp) * dt;
			s.gazeVP += ((tp - s.gazePitch) * stiff - s.gazeVP * damp) * dt;
			s.gazeYaw += s.gazeVY * dt;
			s.gazePitch += s.gazeVP * dt;
		}
		// kleines, ruhiges Abdriften des Blicks – nie ganz starr, nie im Takt
		float drift = noise(t * 0.02F, ns + 7) * 4F;
		float lookYaw = baseYaw + (s.gazeYaw + s.lagYaw + drift - baseYaw) * L;
		float lookPitch = basePitch + (s.gazePitch + noise(t * 0.017F, ns + 8) * 2.5F - basePitch) * L + (player.isCrouching ? 15F * L : 0) + s.flinch * 20F * L;

		// unter Wasser: kleine Luftblasen
		if ((Motion.env(player) & Motion.ENV_WATER) != 0 && dt > 0 && rnd(s) < dt * 1.5F * L) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				double th = Math.toRadians(180.0 - player.bodyRot);
				double X = -tx, Z = tz, c = Math.cos(th), sn = Math.sin(th);
				double wx = player.x + X * c + Z * sn, wz = player.z - X * sn + Z * c, wy = player.y + 1.5 - ty0 + 0.1;
				mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE, wx, wy, wz, 0, 0.05, 0);
			}
		}
		// Erst schauen Kopf/Augen, der Körper dreht sich etwas später ein Stück mit
		if (dt > 0) {
			float bodyTarget = Mth.clamp(lookYaw * 0.35F, -25F, 25F);
			s.bodyYawVel += ((bodyTarget - s.bodyYaw) * 22F - s.bodyYawVel * 8.5F) * dt;
			s.bodyYaw += s.bodyYawVel * dt;
		}
		float ry = s.bodyYaw * L;
		lookYaw -= ry;
		float sq = s.squash * L;
		float sy = 1F + sq * 0.45F + breath * 0.012F * L - (player.isCrouching ? 0.06F * L : 0);
		float sx = 1F - sq * 0.25F;
		return new Frame(dy, sx, sy, rz, lookYaw, lookPitch, true, ry);
	}

	/** Block, den der Spieler abbaut, oder ein Lebewesen in der Nähe: {Gier, Neigung} relativ zum Körper. */
	private static float[] specialTarget(AvatarRenderState player, S s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		Vec3 eye = new Vec3(player.x, player.y + 1.6, player.z);
		Vec3 target = null;
		if (Motion.local(player) && mc.options.keyAttack.isDown() && mc.hitResult instanceof BlockHitResult bhr
				&& mc.hitResult.getType() == HitResult.Type.BLOCK) {
			target = Vec3.atCenterOf(bhr.getBlockPos());
		} else if (s.gazeMode == 2) {
			Entity best = null;
			double bestD = 64;
			for (Entity e : mc.level.getEntities((Entity) null, new AABB(eye, eye).inflate(8), e -> e instanceof LivingEntity)) {
				if (e.getId() == player.id) {
					continue;
				}
				double d = e.position().distanceToSqr(eye);
				if (d < bestD) {
					bestD = d;
					best = e;
				}
			}
			if (best != null) {
				target = best.getEyePosition();
			}
		}
		if (target == null) {
			return null;
		}
		Vec3 d = target.subtract(eye);
		float worldYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float yaw = Mth.wrapDegrees(worldYaw - player.bodyRot);
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		return new float[]{Mth.clamp(yaw, -75F, 75F), Mth.clamp(pitch, -50F, 50F)};
	}

	// Jubeln nach einem Sieg: zuletzt geschlagenes Lebewesen beobachten – stirbt es kurz danach, jubeln die Mini-Mes
	private static int hitId = -1;
	private static long hitAt;

	public static void register() {
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClientSide() && entity instanceof LivingEntity && player == Minecraft.getInstance().player) {
				hitId = entity.getId();
				hitAt = System.currentTimeMillis();
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.de.loothub.client.util.Guard.tick("Life", mc -> {
			if (mc.level == null || mc.player == null) {
				return;
			}
			scanNear(mc);
			if (hitId < 0) {
				return;
			}
			if (System.currentTimeMillis() - hitAt > 2500) {
				hitId = -1;
				return;
			}
			Entity e = mc.level.getEntity(hitId);
			if (e instanceof LivingEntity le && le.isDeadOrDying()) {
				hitId = -1;
				if (amount() > 0) {
					Emotes.cheer();
				}
			}
		});
	}

	// Blitz in der Nähe (alle zucken), Spieler mit Mini-Mes in der Nähe anderer Spieler mit Mini-Mes (winken)
	private static volatile long lightningAt;
	private static final java.util.Set<Integer> SEEN_BOLTS = new java.util.HashSet<>();
	private static volatile java.util.Set<Integer> FOREIGN = java.util.Set.of();
	private static int scanTick;

	private static void scanNear(Minecraft mc) {
		if (++scanTick % 5 != 0) {
			return;
		}
		java.util.Set<Integer> bolts = new java.util.HashSet<>();
		java.util.List<net.minecraft.world.entity.player.Player> withMinis = new java.util.ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof net.minecraft.world.entity.LightningBolt && e.distanceToSqr(mc.player) < 96 * 96) {
				bolts.add(e.getId());
				if (!SEEN_BOLTS.contains(e.getId())) {
					lightningAt = System.currentTimeMillis();
				}
			} else if (e instanceof net.minecraft.world.entity.player.Player pl && e.distanceToSqr(mc.player) < 48 * 48) {
				Cosmetics.Snapshot snap = Cosmetics.of(pl.getId());
				if (snap != null && snap.set().contains(Cosmetics.MINI_ME)) {
					withMinis.add(pl);
				}
			}
		}
		SEEN_BOLTS.retainAll(bolts);
		SEEN_BOLTS.addAll(bolts);
		java.util.Set<Integer> near = new java.util.HashSet<>();
		for (int i = 0; i < withMinis.size(); i++) {
			for (int j = i + 1; j < withMinis.size(); j++) {
				if (withMinis.get(i).distanceToSqr(withMinis.get(j)) < 10 * 10) {
					near.add(withMinis.get(i).getId());
					near.add(withMinis.get(j).getId());
				}
			}
		}
		FOREIGN = near;
	}

	/** Ist ein anderer Spieler mit Mini-Mes in der Nähe dieses Spielers? */
	public static boolean foreignNear(Object playerState) {
		return playerState instanceof AvatarRenderState st && FOREIGN.contains(st.id) && amount() > 0;
	}

	public static Pose pose(AvatarRenderState mini) {
		return POSES.get(mini);
	}
}
