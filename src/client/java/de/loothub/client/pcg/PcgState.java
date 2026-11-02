package de.loothub.client.pcg;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Erkennt Spawns des Pokémon Community Game im Twitch-Chat (Nachrichten des Bots) und merkt sich
 * Name und Ablaufzeit. Der Wortlaut ist nicht dokumentiert, daher bewusst locker erkannt.
 */
public final class PcgState {
	public static final String BOT = "pokemoncommunitygame";
	/** Fangzeit, wenn die Nachricht keine Zeit nennt. */
	private static final long DEFAULT_WINDOW_MS = 90_000;
	/** So lange bleibt das Ergebnis (gefangen/entkommen) stehen. */
	private static final long RESULT_MS = 12_000;

	private static final Pattern WILD = Pattern.compile("(?i)wild\\s+(.+?)\\s+(?:appears|appeared|has appeared|spawned)");
	private static final Pattern SECONDS = Pattern.compile("(?i)(\\d{1,3})\\s*(?:s\\b|sec|seconds|sekunden)");

	public record Spawn(String name, long start, long end, String result, long resultUntil) {
		public long remainingMs() {
			return Math.max(0, end - System.currentTimeMillis());
		}
	}

	/** Abstand zwischen zwei Spawns (laut Spiel alle 15 Minuten). */
	public static final long SPAWN_INTERVAL_MS = 15 * 60_000L;

	private static volatile Spawn current;
	/** Beginn des letzten erkannten Spawns, 0 = noch keiner gesehen. */
	private static volatile long lastSpawnStart;

	private PcgState() {
	}

	/** Aktueller Spawn zur Anzeige, oder null. */
	public static Spawn current() {
		Spawn s = current;
		if (s == null) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (s.result() != null ? now > s.resultUntil() : now > s.end() + 5_000) {
			current = null;
			return null;
		}
		return s;
	}

	static void onChat(String sender, String text) {
		if (!BOT.equalsIgnoreCase(sender)) {
			return;
		}
		Matcher m = WILD.matcher(text);
		if (m.find()) {
			String name = clean(m.group(1));
			long window = DEFAULT_WINDOW_MS;
			Matcher sec = SECONDS.matcher(text);
			if (sec.find()) {
				window = Long.parseLong(sec.group(1)) * 1000L;
			}
			long now = System.currentTimeMillis();
			current = new Spawn(name, now, now + window, null, 0);
			lastSpawnStart = now;
			GermanNames.of(name); // deutschen Namen schon mal nachschlagen
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f)));
			return;
		}
		Spawn s = current;
		if (s == null || s.result() != null) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		String result = null;
		if (lower.contains("caught") || lower.contains("gefangen")) {
			result = "gefangen";
		} else if (lower.contains("escaped") || lower.contains("fled") || lower.contains("ran away") || lower.contains("broke free")) {
			result = "entkommen";
		}
		if (result != null) {
			long now = System.currentTimeMillis();
			current = new Spawn(s.name(), s.start(), now, result, now + RESULT_MS);
		}
	}

	/**
	 * Millisekunden bis zum nächsten Spawn: -1, solange noch kein Spawn gesehen wurde,
	 * 0, wenn er überfällig ist (kommt gleich).
	 */
	public static long untilNextMs() {
		long last = lastSpawnStart;
		if (last == 0) {
			return -1;
		}
		long now = System.currentTimeMillis();
		long next = last + SPAWN_INTERVAL_MS;
		// Verpasste Spawns (z. B. Spiel war zu) überspringen, solange mehr als ein Intervall vergangen ist
		while (next + 60_000 < now && now - next > SPAWN_INTERVAL_MS) {
			next += SPAWN_INTERVAL_MS;
		}
		return Math.max(0, next - now);
	}

	private static String clean(String name) {
		// Emotes/Sonderzeichen am Rand entfernen, z. B. „a wild ✨ Pikachu“ → „Pikachu“
		String n = name.replaceAll("^(?i)(a|an)\\s+", "").replaceAll("[^\\p{L}\\p{N} .'\\-♀♂]", "").strip();
		return n.isEmpty() ? name.strip() : n;
	}

	/** Für den Editor/Test. */
	public static void demo() {
		long now = System.currentTimeMillis();
		current = new Spawn("Beispiel", now, now + DEFAULT_WINDOW_MS, null, 0);
	}
}
