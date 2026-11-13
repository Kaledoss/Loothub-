package de.loothub.client.cosmetics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Gesten aus dem Emote-Rad mit anderen Loothub-Spielern teilen. Die eigene Geste wird – mit dem von Mojang
 * beglaubigten Spieler-Schlüssel unterschrieben – kurz beim Cosmetics-Server gemeldet; Gesten von Loothub-Spielern
 * in der Nähe werden abgefragt, aber nur, wenn überhaupt jemand mit Loothub in Sichtweite ist – anfangs alle 1,5
 * Sekunden; passiert nichts, wird schrittweise seltener gefragt (bis 6 s), bei Fehlern noch seltener.
 * Nur Darstellung: niemand sieht dadurch etwas anderes im Spiel als Figuren, die winken.
 */
public final class EmoteShare {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
	private static final long POLL_MS = 1_500L, POLL_IDLE_MS = 6_000L, POLL_ERROR_MS = 20_000L, SEND_GAP_MS = 1_500L;
	/** So lange läuft eine Geste (wie beim eigenen Emote-Rad). */
	private static final long PLAY_MS = 4_000L;
	private static final double RANGE = 48.0;

	/** UUID → {Art, Startzeit (lokal, ms)}. */
	private static final Map<UUID, long[]> REMOTE = new ConcurrentHashMap<>();
	/** Standort, an dem der andere Spieler seine Geste begonnen hat – bewegt er sich, endet sie. */
	private static final Map<UUID, double[]> REMOTE_POS = new ConcurrentHashMap<>();
	private static volatile boolean polling, sending;
	private static long lastPoll, lastSend;
	/** Aktueller Abstand zwischen zwei Abfragen (passt sich an). */
	private static volatile long interval = POLL_MS;

	private EmoteShare() {
	}

	private static boolean active() {
		return CosmeticsServer.enabled() && WidgetConfig.get().cosmetics;
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level == null || mc.player == null) {
				return;
			}
			Emotes.stopIfMoved(mc.player);
			stopRemoteIfMoved(mc);
			if (!active()) {
				return;
			}
			long now = System.currentTimeMillis();
			if (polling || now - lastPoll < interval) {
				return;
			}
			lastPoll = now;
			List<String> ids = new ArrayList<>();
			for (Player p : mc.level.players()) {
				if (p != mc.player && p.distanceToSqr(mc.player) < RANGE * RANGE && CosmeticsServer.isUser(p.getUUID())) {
					ids.add(p.getUUID().toString().toLowerCase(Locale.ROOT));
					if (ids.size() >= 20) {
						break;
					}
				}
			}
			if (!ids.isEmpty()) {
				poll(ids);
			} else {
				// niemand in der Nähe: beim nächsten Treffen wieder zügig anfangen
				interval = POLL_MS;
			}
		});
	}

	/** Gesten anderer laufen in Schleife, bis sich der Spieler bewegt oder außer Sicht ist. */
	private static void stopRemoteIfMoved(net.minecraft.client.Minecraft mc) {
		for (UUID id : REMOTE.keySet()) {
			Player p = mc.level.getPlayerByUUID(id);
			if (p == null) {
				REMOTE.remove(id);
				REMOTE_POS.remove(id);
				continue;
			}
			double[] at = REMOTE_POS.computeIfAbsent(id, k -> new double[]{p.getX(), p.getY(), p.getZ()});
			if (Emotes.moved(at, p)) {
				REMOTE.remove(id);
				REMOTE_POS.remove(id);
			}
		}
	}

	private static void poll(List<String> ids) {
		polling = true;
		HttpRequest req = HttpRequest.newBuilder(URI.create(CosmeticsServer.BASE + "/emotes?ids=" + String.join(",", ids)))
				.timeout(Duration.ofSeconds(6)).header("User-Agent", "Loothub-Mod").GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((r, t) -> {
			try {
				if (t != null || r.statusCode() != 200) {
					interval = POLL_ERROR_MS;
					return;
				}
				boolean any = false;
				JsonElement e = JsonParser.parseString(r.body());
				if (!e.isJsonObject()) {
					return;
				}
				long now = System.currentTimeMillis();
				for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
					if (!en.getValue().isJsonObject()) {
						continue;
					}
					JsonObject v = en.getValue().getAsJsonObject();
					int type = v.has("e") ? v.get("e").getAsInt() : 0;
					long age = v.has("age") ? v.get("age").getAsLong() : Long.MAX_VALUE;
					if (type <= 0 || age > PLAY_MS) {
						continue;
					}
					any = true;
					UUID id = UUID.fromString(en.getKey());
					long start = now - age;
					long[] old = REMOTE.get(id);
					// dieselbe Geste nicht neu starten (sie kommt bei jeder Abfrage wieder)
					if (old == null || old[0] != type || Math.abs(old[1] - start) > 1_500L) {
						REMOTE.put(id, new long[]{type, start});
						REMOTE_POS.remove(id); // Standort beim nächsten Tick neu merken
					}
				}
				// gerade wird gewunken → schnell bleiben, sonst langsam seltener fragen
				interval = any ? POLL_MS : Math.min(POLL_IDLE_MS, interval + 1_000L);
			} catch (RuntimeException ex) {
				interval = POLL_ERROR_MS;
				Loothub.LOGGER.debug("Gesten: Antwort nicht lesbar", ex);
			} finally {
				polling = false;
			}
		});
	}

	/** Eigene Geste melden (vom Emote-Rad). */
	public static void send(int type) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (!active() || sending || now - lastSend < SEND_GAP_MS || mc.player == null) {
			return;
		}
		lastSend = now;
		// wer selbst winkt, bekommt vermutlich gleich eine Antwort
		interval = POLL_MS;
		sending = true;
		UUID id = mc.getUser().getProfileId();
		Thread t = new Thread(() -> {
			try {
				var pair = mc.getProfileKeyPairManager().prepareKeyPair().get(20, java.util.concurrent.TimeUnit.SECONDS)
						.orElseThrow(() -> new IllegalStateException("kein Profil-Schlüssel"));
				var data = pair.publicKey().data();
				String uuid = id.toString().toLowerCase(Locale.ROOT);
				long ts = System.currentTimeMillis();
				java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
				sig.initSign(pair.privateKey());
				sig.update(("loothub-emote\n" + uuid + "\n" + ts + "\n" + type).getBytes(StandardCharsets.UTF_8));
				java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
				JsonObject body = new JsonObject();
				body.addProperty("uuid", uuid);
				body.addProperty("ts", ts);
				body.addProperty("emote", type);
				body.addProperty("publicKey", b64.encodeToString(data.key().getEncoded()));
				body.addProperty("expiresAt", data.expiresAt().toEpochMilli());
				body.addProperty("keySignature", b64.encodeToString(data.keySignature()));
				body.addProperty("signature", b64.encodeToString(sig.sign()));
				HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(CosmeticsServer.BASE + "/emote"))
						.timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
						.header("User-Agent", "Loothub-Mod")
						.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (r.statusCode() != 200) {
					Loothub.LOGGER.debug("Gesten-Server lehnt ab ({}): {}", r.statusCode(), r.body());
				}
			} catch (Exception e) {
				Loothub.LOGGER.debug("Geste konnte nicht geteilt werden", e);
			} finally {
				sending = false;
			}
		}, "Loothub-Emote");
		t.setDaemon(true);
		t.start();
	}

	/** Geste eines anderen Spielers: {Art, Fortschritt 0..1} oder null. */
	public static float[] remote(UUID id) {
		if (id == null) {
			return null;
		}
		long[] v = REMOTE.get(id);
		if (v == null) {
			return null;
		}
		long d = System.currentTimeMillis() - v[1];
		return d >= 0 ? Emotes.loop((int) v[0], d) : null; // Endlosschleife, bis er sich bewegt
	}
}
