package de.loothub.client.progress;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.loothub.client.stats.StatsStore;
import net.minecraft.client.Minecraft;

/**
 * Geheimnisse (Easter Eggs): die Mod erkennt sie im Spiel und meldet sie einmal an den Loothub-Server. Hinweise dazu stehen
 * unter Erfolge → Heldentaten beim Geheimnis-Knoten.
 */
final class Secrets {
	private Secrets() {
	}

	private static final Set<String> SENT = new HashSet<>();
	private static int ticks;
	private static int fishSeen = -1;
	private static final Deque<long[]> JUMPS = new ArrayDeque<>();
	private static float lastYaw = Float.NaN;
	private static final Deque<long[]> TURNS = new ArrayDeque<>();

	private static boolean has(String id) {
		JsonObject s = Progress.state();
		if (s == null || !s.has("secrets")) {
			return true; // ohne Stand vom Server nichts melden
		}
		for (JsonElement e : s.getAsJsonArray("secrets")) {
			if (e.getAsString().equals(id)) {
				return true;
			}
		}
		return SENT.contains(id);
	}

	private static void found(String id) {
		if (!has(id) && !Progress.busy()) {
			SENT.add(id);
			Progress.secret(id);
		}
	}

	static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || !Progress.enabled()) {
			return;
		}
		ticks++;
		long now = System.currentTimeMillis();
		int hour = LocalTime.now().getHour();
		double y = mc.player.getY();
		if (y >= 300) {
			found("sky_high");
		}
		if (y <= -60) {
			found("deep_down");
		}
		// Karussell: fünf ganze Drehungen in fünf Sekunden
		float yaw = mc.player.getYRot();
		if (!Float.isNaN(lastYaw)) {
			TURNS.addLast(new long[]{now, (long) (Math.min(Math.abs(yaw - lastYaw), 90F) * 100)});
		}
		lastYaw = yaw;
		long sum = 0;
		while (!TURNS.isEmpty() && now - TURNS.peekFirst()[0] > 5000) {
			TURNS.pollFirst();
		}
		for (long[] t : TURNS) {
			sum += t[1];
		}
		if (sum >= 180_000) {
			found("dizzy");
		}
		if (ticks % 10 != 0) {
			return;
		}
		if (hour == 3 && !de.loothub.client.stats.OpsuchtAfk.afk(mc)) {
			found("night_owl");
		}
		// Gummibeine: 100 Sprünge in einer Minute
		long jumps = StatsStore.sum(StatsStore.Range.TOTAL).getOrDefault("general.jumps", 0L);
		JUMPS.addLast(new long[]{now, jumps});
		while (!JUMPS.isEmpty() && now - JUMPS.peekFirst()[0] > 60_000) {
			JUMPS.pollFirst();
		}
		if (!JUMPS.isEmpty() && jumps - JUMPS.peekFirst()[1] >= 100) {
			found("jumper");
		}
		// Fang: um Mitternacht bzw. im Gewitter
		int fish = de.loothub.client.jobs.FishingLog.all().size();
		if (fishSeen >= 0 && fish > fishSeen) {
			if (hour == 0) {
				found("midnight_fish");
			}
			if (mc.level.isThundering()) {
				found("storm_fisher");
			}
		}
		fishSeen = fish;
		// Glückspilz: genau 7.777 Taler
		JsonObject s = Progress.state();
		if (s != null && s.has("balance") && s.get("balance").getAsLong() == 7777) {
			found("lucky");
		}
	}

	/** Eigene Chat-Nachricht: wer „Loothub“ schreibt, ruft den Namen. */
	static void chat(String message) {
		if (message != null && message.toLowerCase(Locale.ROOT).contains("loothub")) {
			found("chatty");
		}
	}
}
