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
		float charge;
		long lastMove;
		int seenTick;
		// Flügel: Öffnung als gedämpfte Feder (schwingt beim Anhalten nach), Schlag-Phase, Schlag-Stärke
		float open = 0.3F, openV;
		float phase, amp = 0.3F;
		float jumpKick;
		boolean wasOnGround = true;
		// Umgebung (alle 10 Ticks neu): 1 Regen, 2 Schnee, 4 Wasser, 8 Lava in der Nähe
		int env;
		int envTick = -100;
		float rise;
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
		// nur bei schnellem Vorwärtsflug (z. B. Sprint-Flug) – normales Schweben/Bewegen bleibt aufrecht
		boolean fast = fwd > 0.75F || (p == Minecraft.getInstance().player && p.isSprinting() && fwd > 0.4F);
		boolean target = WidgetConfig.get().flyPose && flying && fast && !p.isFallFlying() && !p.isInWater()
				&& !p.isPassenger() && !p.isSleeping();
		tr.fly = Mth.clamp(tr.fly + (target ? dt : -dt) / 0.35F, 0.0F, 1.0F);
		float flyEased = tr.fly * tr.fly * (3.0F - 2.0F * tr.fly);
		// Aura-Stufen: Sprinten oder Fliegen lädt in ~6 s auf Stufe 3, Stillstand entlädt in ~3 s
		boolean charging = p.isSprinting() || tr.fly > 0.5F;
		tr.charge = Mth.clamp(tr.charge + (charging ? dt / 2.0F : -dt), 0.0F, 3.0F);
		// Stillstand (für Mini-Me-Emotes)
		if (horiz > 0.01 || Math.abs(dy) > 0.01 || Math.abs(lag) > 2.0F || tr.lastMove == 0) {
			tr.lastMove = now;
		}
		float idle = (now - tr.lastMove) / 1.0e9F;
		boolean local = p == Minecraft.getInstance().player;
		// ---- Flügel ----
		boolean flyingNow = tr.fly > 0.5F || (local && p.getAbilities().flying);
		boolean falling = !p.onGround() && dy < -0.25 && !flyingNow;
		boolean jumped = tr.wasOnGround && !p.onGround() && dy > 0.05;
		tr.wasOnGround = p.onGround();
		if (jumped) {
			tr.jumpKick = 1.0F; // ein kräftiger Schlag beim Absprung
		}
		tr.jumpKick = Math.max(0.0F, tr.jumpKick - dt * 2.2F);
		float targetOpen;
		if (falling) {
			targetOpen = 1.0F; // weit aufgespannt zum Gleiten
		} else if (flyingNow) {
			targetOpen = 0.95F;
		} else if (horiz > 0.2) {
			targetOpen = 0.85F;
		} else if (horiz > 0.03) {
			targetOpen = 0.6F;
		} else {
			targetOpen = 0.2F; // im Stand halb angelegt
		}
		// Feder mit wenig Dämpfung: schwingt beim abrupten Anhalten kurz nach
		float kOpen = 28.0F, dOpen = 2.0F * (float) Math.sqrt(kOpen) * 0.45F;
		tr.openV += (kOpen * (targetOpen - tr.open) - dOpen * tr.openV) * dt;
		tr.open += tr.openV * dt;
		// Schlag: steigen = schnell und kräftig, sinken/gleiten = kaum, im Stand = langsames Atmen
		float rate, targetAmp;
		if (falling) {
			rate = 0.03F;
			targetAmp = 0.08F;
		} else if (flyingNow && dy > 0.05) {
			rate = 0.42F;
			targetAmp = 1.0F;
		} else if (flyingNow && dy < -0.05) {
			rate = 0.06F;
			targetAmp = 0.2F;
		} else if (flyingNow) {
			rate = 0.2F + (float) Math.min(0.15, horiz * 0.2);
			targetAmp = 0.7F;
		} else if (horiz > 0.03) {
			rate = 0.14F + (float) Math.min(0.12, horiz * 0.4);
			targetAmp = 0.45F;
		} else {
			rate = 0.06F;
			targetAmp = 0.25F;
		}
		tr.amp += (targetAmp - tr.amp) * (1.0F - (float) Math.exp(-dt * 3.0F));
		tr.phase += dt * 20.0F * (rate + tr.jumpKick * 0.5F);
		// ---- Steig-/Sinkflug (ohne schnellen Vorwärtsflug): Stärke −1 (runter) … +1 (hoch), weich ein-/ausgeblendet
		boolean airborneFlight = local ? p.getAbilities().flying : (!p.onGround() && Math.abs(dy) > 0.05 && !p.isFallFlying());
		float riseTarget = airborneFlight && tr.fly < 0.5F && !p.isFallFlying() && !p.isInWater()
				? Mth.clamp((float) dy / 0.3F, -1.0F, 1.0F) : 0.0F;
		if (Math.abs(riseTarget) < 0.25F) {
			riseTarget = 0.0F; // nur bei deutlichem Hoch-/Runterfliegen
		}
		tr.rise += (riseTarget - tr.rise) * (1.0F - (float) Math.exp(-dt * 6.0F));
		// ---- Umgebung ----
		// nur nötig, wenn Mini-Mes oder Haustiere darauf reagieren können
		if (Math.abs(p.tickCount - tr.envTick) >= 10
				&& (Cosmetics.has(state, Cosmetics.MINI_ME) || Cosmetics.has(state, Cosmetics.PETS))) {
			tr.envTick = p.tickCount;
			tr.env = environment(p);
		}
		STATES.put(state, new float[]{lag, tr.fwd, tr.vy, flyEased, tr.charge, idle, local ? 1.0F : 0.0F,
				Mth.clamp(tr.open, -0.15F, 1.2F), tr.phase, tr.amp + tr.jumpKick * 0.8F, (float) tr.env,
				p.onGround() ? 1.0F : 0.0F, tr.rise});
		if (TRACKS.size() > 200) {
			TRACKS.values().removeIf(t -> Math.abs(p.tickCount - t.seenTick) > 1200);
		}
	}

	public static final int ENV_RAIN = 1, ENV_SNOW = 2, ENV_WATER = 4, ENV_LAVA = 8;

	private static int environment(Player p) {
		var level = p.level();
		net.minecraft.core.BlockPos pos = p.blockPosition();
		int env = 0;
		if (level.isRainingAt(pos.above())) {
			env |= ENV_RAIN;
		}
		var below = level.getBlockState(pos.below());
		var at = level.getBlockState(pos);
		if (isSnow(below) || isSnow(at)) {
			env |= ENV_SNOW;
		}
		if (p.isInWater()) {
			env |= ENV_WATER;
		}
		// Lava im Umkreis von 2 Blöcken (75 Abfragen, alle 10 Ticks)
		outer:
		for (int x = -2; x <= 2; x++) {
			for (int y = -1; y <= 1; y++) {
				for (int z = -2; z <= 2; z++) {
					if (level.getFluidState(pos.offset(x, y, z)).is(net.minecraft.tags.FluidTags.LAVA)) {
						env |= ENV_LAVA;
						break outer;
					}
				}
			}
		}
		return env;
	}

	private static boolean isSnow(net.minecraft.world.level.block.state.BlockState s) {
		return s.is(net.minecraft.world.level.block.Blocks.SNOW) || s.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)
				|| s.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW) || s.is(net.minecraft.world.level.block.Blocks.ICE)
				|| s.is(net.minecraft.world.level.block.Blocks.PACKED_ICE) || s.is(net.minecraft.world.level.block.Blocks.BLUE_ICE);
	}

	/** Flügel-Öffnung (0 = angelegt, 1 = weit offen; schwingt leicht über). */
	public static float wingOpen(Object state) {
		float[] v = STATES.get(state);
		return v == null ? 0.6F : v[7];
	}

	/** Flügel-Schlagphase (läuft mit dem Tempo des Schlags) und -stärke. */
	public static float wingPhase(Object state) {
		return get(state, 8);
	}

	public static float wingAmp(Object state) {
		float[] v = STATES.get(state);
		return v == null ? 0.5F : v[9];
	}

	/** Umgebung als Bitmaske ({@link #ENV_RAIN} …). */
	public static int env(Object state) {
		return (int) get(state, 10);
	}

	/** Steig-/Sinkflug: +1 = steigt, −1 = sinkt, 0 = keins. */
	public static float rise(Object state) {
		float[] v = STATES.get(state);
		return v == null || v.length < 13 ? 0.0F : v[12];
	}

	public static boolean onGround(Object state) {
		float[] v = STATES.get(state);
		return v == null || v[11] > 0.5F;
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

	/** Aura-Stufe 0..3 (steigt beim Sprinten/Fliegen). */
	public static float charge(Object state) {
		return get(state, 4);
	}

	/** Sekunden ohne Bewegung. */
	public static float idle(Object state) {
		return get(state, 5);
	}

	/** Ob der Zustand zum eigenen Spieler gehört. */
	public static boolean local(Object state) {
		return get(state, 6) > 0.5F;
	}

	/** Flug-Haltung eines Spielers nach Entity-ID (für Partikel im Tick). */
	public static float flyOf(int entityId) {
		Track t = TRACKS.get(entityId);
		return t == null ? 0.0F : t.fly;
	}

	/** Flug-Haltung 0..1 (0 = aufrecht, 1 = waagerecht wie beim Superheldenflug). */
	public static float fly(Object state) {
		return get(state, 3);
	}
}
