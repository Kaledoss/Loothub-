package de.loothub.client.cosmetics;

import java.util.Locale;

import de.loothub.client.jobs.WidgetConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Haustier-Level: Jedes Haustier, das dir folgt, sammelt mit dir Erfahrung – 1 Punkt je aktive Spielminute (nicht AFK)
 * und 10 Punkte, wenn es sich mit dir freut (Level-up, sensationeller Fisch, Heldentat). Belohnungen:
 * Lv 2 Herzchen bei Freude · ab Lv 3 wächst es ein Stück (auch für andere sichtbar) · Lv 5 Salto bei Freude ·
 * Lv 7 Funkeln um dich · Lv 10 Goldglanz.
 */
public final class PetLevels {
	public static final int MAX = 10;
	public static final int HEARTS = 2, GROW = 3, FLIP = 5, SPARKLE = 7, GOLD = 10;
	private static final int JOY_XP = 10;
	private static long minuteAt;
	private static double lastX, lastZ;
	private static float lastYaw;
	private static long movedAt;
	private static long sparkleAt;

	private PetLevels() {
	}

	public static void register() {
		de.loothub.client.util.Guard.tick("PetLevels", PetLevels::tick);
	}

	/** Gesamt-Erfahrung, die für ein Level nötig ist: Lv 2 = 60 (1 Std.), Lv 5 = 600, Lv 10 = 2700. */
	public static int xpFor(int level) {
		return 30 * level * (level - 1);
	}

	public static int level(int xp) {
		int l = 1;
		while (l < MAX && xp >= xpFor(l + 1)) {
			l++;
		}
		return l;
	}

	/** Fortschritt zum nächsten Level in 0..1 (1 bei Maximum). */
	public static float progress(int xp) {
		int l = level(xp);
		if (l >= MAX) {
			return 1F;
		}
		return Mth.clamp((xp - xpFor(l)) / (float) (xpFor(l + 1) - xpFor(l)), 0F, 1F);
	}

	/** Haustier-Art auf einem folgenden Platz, oder "". */
	public static String type(int slot) {
		String[] p = (WidgetConfig.get().pets == null ? "" : WidgetConfig.get().pets).split(",", -1);
		return slot < p.length ? p[slot].strip() : "";
	}

	public static int xp(String type) {
		Integer v = WidgetConfig.get().petXp.get(type);
		return v == null ? 0 : v;
	}

	/** Höchstes Level der eigenen folgenden Haustiere (für Funkeln/Goldglanz). */
	public static int bestOwnLevel() {
		int best = 0;
		for (int s = 0; s < Cosmetics.MAX_PETS; s++) {
			if (!type(s).isEmpty()) {
				best = Math.max(best, level(xp(type(s))));
			}
		}
		return best;
	}

	/** Level je Platz als "3,5" für das Cosmetics-Profil (andere sehen die Größe). */
	public static String ownProfileValue() {
		StringBuilder sb = new StringBuilder();
		boolean any = false;
		for (int s = 0; s < Cosmetics.MAX_PETS; s++) {
			if (s > 0) {
				sb.append(',');
			}
			if (!type(s).isEmpty()) {
				int l = level(xp(type(s)));
				sb.append(l);
				any |= l > 1;
			}
		}
		return any ? sb.toString() : "";
	}

	/** Level eines Haustiers eines (beliebigen) Spielers aus dessen Profil; 1 wenn unbekannt. */
	public static int level(Object playerState, int slot) {
		String raw = Cosmetics.raw(playerState, Cosmetics.PET_LEVELS);
		if (raw == null || raw.isBlank()) {
			return 1;
		}
		String[] p = raw.split(",", -1);
		try {
			return slot < p.length && !p[slot].isBlank() ? Mth.clamp(Integer.parseInt(p[slot].strip()), 1, MAX) : 1;
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	/** Größe nach Level: ab Lv 3 je Level +3 % (Lv 10 = +24 %). */
	public static float growth(int level) {
		return level < GROW ? 1F : 1F + (level - GROW + 1) * 0.03F;
	}

	/** Extra-Erfahrung (z. B. Treue-Kalender, Geschenk) für alle eigenen folgenden Haustiere. */
	public static void bonus(int amount) {
		if (amount > 0) {
			add(amount);
		}
	}

	/** Freude: Erfahrung für alle eigenen folgenden Haustiere. */
	public static void joy() {
		add(JOY_XP);
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || WidgetConfig.get().pets == null || WidgetConfig.get().pets.replace(",", "").isBlank()) {
			return;
		}
		long now = System.currentTimeMillis();
		double dx = mc.player.getX() - lastX, dz = mc.player.getZ() - lastZ;
		if (dx * dx + dz * dz > 0.01 || Math.abs(mc.player.getYRot() - lastYaw) > 1F) {
			movedAt = now;
			lastX = mc.player.getX();
			lastZ = mc.player.getZ();
			lastYaw = mc.player.getYRot();
		}
		if (minuteAt == 0) {
			minuteAt = now;
		}
		if (now - minuteAt >= 60_000L) {
			minuteAt = now;
			if (now - movedAt < 120_000L) { // nur wer wirklich spielt, nicht AFK
				add(1);
				maybeGift(mc);
			}
		}
		// Funkeln (Lv 7) bzw. Goldglanz (Lv 10): ab und zu ein paar Funken um dich
		int best = bestOwnLevel();
		if (best >= SPARKLE && mc.level != null && now - sparkleAt > (best >= GOLD ? 1_500L : 3_000L)) {
			sparkleAt = now;
			var r = mc.level.random;
			for (int i = 0; i < (best >= GOLD ? 4 : 2); i++) {
				mc.level.addParticle(best >= GOLD ? ParticleTypes.WAX_ON : ParticleTypes.END_ROD,
						mc.player.getX() + (r.nextDouble() - 0.5) * 1.6, mc.player.getY() + 0.3 + r.nextDouble() * 1.2,
						mc.player.getZ() + (r.nextDouble() - 0.5) * 1.6, 0, best >= GOLD ? 0 : 0.01, 0);
			}
		}
	}

	/** Ganz selten (im Schnitt nach gut einer aktiven Stunde, höchstens einmal am Tag) bringt das Haustier etwas mit. */
	private static void maybeGift(Minecraft mc) {
		WidgetConfig c = WidgetConfig.get();
		String today = java.time.LocalDate.now().toString();
		if (today.equals(c.petGiftDay) || Math.random() >= 1.0 / 75.0 || mc.level == null) {
			return;
		}
		c.petGiftDay = today;
		c.save();
		PetMood.joyQuiet();
		var r = mc.level.random;
		for (int i = 0; i < 10; i++) {
			mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, mc.player.getX() + (r.nextDouble() - 0.5) * 1.5,
					mc.player.getY() + 0.2 + r.nextDouble(), mc.player.getZ() + (r.nextDouble() - 0.5) * 1.5, 0, 0.05, 0);
		}
		mc.getToastManager().addToast(new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.translatable("loothub.petgift.title"), Component.translatable("loothub.petgift.xp", GIFT_XP)));
		add(GIFT_XP);
		de.loothub.client.progress.Progress.petGift(); // Taler gibt es vom Loothub-Server (einmal am Tag)
	}

	private static final int GIFT_XP = 15;

	private static void add(int amount) {
		WidgetConfig c = WidgetConfig.get();
		boolean changed = false;
		java.util.Set<String> done = new java.util.HashSet<>();
		for (int s = 0; s < Cosmetics.MAX_PETS; s++) {
			String t = type(s);
			if (t.isEmpty() || !done.add(t)) {
				continue;
			}
			int before = xp(t), after = before + amount;
			c.petXp.put(t, after);
			changed = true;
			int lb = level(before), la = level(after);
			if (la > lb) {
				levelUp(t, la);
			}
		}
		if (changed) {
			c.save();
		}
	}

	private static void levelUp(String type, int level) {
		Minecraft mc = Minecraft.getInstance();
		Component name = Component.translatable("loothub.cosmetics.pets.type." + type);
		Component title = Component.translatable("loothub.petlevel.toast", name, level);
		String key = switch (level) {
			case HEARTS -> "hearts";
			case FLIP -> "flip";
			case SPARKLE -> "sparkle";
			case GOLD -> "gold";
			default -> level >= GROW ? "grow" : "";
		};
		Component text = key.isEmpty() ? Component.empty() : Component.translatable("loothub.petlevel.unlock." + key);
		mc.getToastManager().addToast(new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, text));
		PetMood.joyQuiet();
	}

	/** Nächste Belohnung als Text für die Anzeige, oder leer bei Maximum. */
	public static Component nextReward(int level) {
		for (int l = level + 1; l <= MAX; l++) {
			String key = switch (l) {
				case HEARTS -> "hearts";
				case GROW -> "grow";
				case FLIP -> "flip";
				case SPARKLE -> "sparkle";
				case GOLD -> "gold";
				default -> "";
			};
			if (!key.isEmpty()) {
				return Component.translatable("loothub.petlevel.next", l, Component.translatable("loothub.petlevel.unlock." + key));
			}
		}
		return Component.translatable("loothub.petlevel.max");
	}

	static String fmtPct(float f) {
		return String.format(Locale.ROOT, "%d %%", Math.round(f * 100));
	}
}
