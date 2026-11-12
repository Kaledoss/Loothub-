package de.loothub.client.jobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;

/**
 * OPSUCHT-Fangton stummschalten. Welcher Ton das ist, lernt Loothub selbst: Töne, die der Server immer zusammen
 * mit der Fang-Meldung schickt, werden gezählt; ab drei Treffern gelten sie als Fangton. Stumm geschaltet werden sie
 * nur, solange man eine Angel in der Hand hält – überall sonst bleiben sie hörbar. Nur die eigene Wiedergabe.
 */
public final class FishSounds {
	private static final long WINDOW_MS = 400;
	private static final int LEARNED_AFTER = 3;
	private static final Pattern ID = Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+)");
	/** Gewöhnliche Angel-Geräusche von Minecraft selbst – werden nie als Fangton gelernt. */
	private static final Set<String> VANILLA = Set.of("minecraft:entity.fishing_bobber.retrieve",
			"minecraft:entity.fishing_bobber.splash", "minecraft:entity.fishing_bobber.throw", "minecraft:entity.item.pickup",
			"minecraft:entity.experience_orb.pickup", "minecraft:entity.generic.splash", "minecraft:entity.player.splash");

	private record Heard(String id, long at) {
	}

	private static final Deque<Heard> RECENT = new ArrayDeque<>();
	private static long lastCatch;
	private static final Set<String> COUNTED = new HashSet<>();

	private FishSounds() {
	}

	/** Lesbare ID eines Tons, z. B. "minecraft:entity.player.levelup". */
	public static String id(Holder<SoundEvent> h) {
		String t = h.unwrapKey().map(Object::toString).orElseGet(() -> h.value().toString());
		Matcher m = ID.matcher(t);
		String last = t;
		while (m.find()) {
			last = m.group(1);
		}
		return last;
	}

	/** Ton vom Server (Spiel-Thread). true = nicht abspielen. */
	public static boolean onSound(Holder<SoundEvent> sound) {
		String id = id(sound);
		long now = System.currentTimeMillis();
		RECENT.addLast(new Heard(id, now));
		while (!RECENT.isEmpty() && now - RECENT.peekFirst().at() > WINDOW_MS) {
			RECENT.pollFirst();
		}
		// kurz nach einer Fang-Meldung: mitzählen
		if (now - lastCatch <= WINDOW_MS && COUNTED.add(id)) {
			count(id);
		}
		WidgetConfig c = WidgetConfig.get();
		Minecraft mc = Minecraft.getInstance();
		return c.fishSoundMute && learned(id) && !c.fishSoundAllowed.contains(id)
				&& de.loothub.client.quick.AfkView.fishing(mc.player);
	}

	/** Fang-Meldung (Spiel-Thread): Töne der letzten Augenblicke zählen. */
	static void onCatch() {
		long now = System.currentTimeMillis();
		lastCatch = now;
		COUNTED.clear();
		WidgetConfig c = WidgetConfig.get();
		c.fishSoundCatches++;
		for (Heard h : RECENT) {
			if (now - h.at() <= WINDOW_MS && COUNTED.add(h.id())) {
				count(h.id());
			}
		}
		c.save();
	}

	private static void count(String id) {
		if (VANILLA.contains(id)) {
			return;
		}
		WidgetConfig c = WidgetConfig.get();
		c.fishSoundSeen.merge(id, 1, Integer::sum);
	}

	/** Als Fangton erkannt? */
	public static boolean learned(String id) {
		WidgetConfig c = WidgetConfig.get();
		Integer n = c.fishSoundSeen.get(id);
		if (n == null || n < LEARNED_AFTER) {
			return false;
		}
		// muss bei den meisten Fängen dabei sein, nicht nur zufällig ein paarmal
		return n >= 0.6 * Math.min(c.fishSoundCatches, 30);
	}

	/** Alle erkannten Fangtöne (für die Einstellungen). */
	public static List<String> learnedSounds() {
		List<String> out = new ArrayList<>();
		Map<String, Integer> seen = new HashMap<>(WidgetConfig.get().fishSoundSeen);
		for (String id : seen.keySet()) {
			if (learned(id)) {
				out.add(id);
			}
		}
		out.sort(String::compareTo);
		return out;
	}

	/** Gelerntes vergessen (z. B. wenn OPSUCHT den Ton ändert). */
	public static void reset() {
		WidgetConfig c = WidgetConfig.get();
		c.fishSoundSeen.clear();
		c.fishSoundCatches = 0;
		c.fishSoundAllowed.clear();
		c.save();
	}
}
