package de.loothub.client.streamer;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Streamereignisse des eigenen Kanals: eingelöste Kanalbelohnungen (live über Twitch EventSub) und geteilte
 * Stream-Serien der Zuschauer (aus dem Twitch-Chat). Nur lesend: Die Liste
 * wird lokal geführt, „abhaken“ ändert nichts auf Twitch. Gespeichert in {@code config/loothub-redemptions.json}.
 */
public final class Redemptions {
	/** Eine Einlösung. */
	public static final class Entry {
		public String id = "";
		public String user = "";
		public String reward = "";
		public String input = "";
		public int cost;
		public long time;
		public boolean done;
		/** "redeem" = Kanalbelohnung, "streak" = Stream-Serie, "sub" = Abo oder Geschenk-Abo. */
		public String kind = "redeem";
	}

	public enum Status { OFF, CONNECTING, CONNECTED, NOT_ALLOWED, ERROR }

	private static final String WS_URL = "wss://eventsub.wss.twitch.tv/ws";
	private static final int MAX = 300;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Nur auf dem Spiel-Thread ändern. */
	private static List<Entry> entries;
	private static volatile Status status = Status.OFF;
	private static volatile String error = "";
	private static volatile Thread thread;

	private Redemptions() {
	}

	public static Status status() {
		return status;
	}

	public static String error() {
		return error;
	}

	// ------------------------------------------------------------------ Liste

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-redemptions.json");
	}

	public static List<Entry> list() {
		if (entries == null) {
			entries = new ArrayList<>();
			Path p = file();
			if (Files.exists(p)) {
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					List<Entry> l = GSON.fromJson(r, new TypeToken<List<Entry>>() { }.getType());
					if (l != null) {
						l.removeIf(e -> e == null);
						entries.addAll(l);
					}
				} catch (IOException | RuntimeException e) {
					Loothub.LOGGER.warn("Kanalbelohnungen konnten nicht gelesen werden", e);
				}
			}
		}
		return entries;
	}

	public static List<Entry> open() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : list()) {
			if (!e.done) {
				out.add(e);
			}
		}
		return out;
	}

	public static void save() {
		de.loothub.client.AsyncSave.write(file(), GSON.toJson(list()));
	}

	public static void toggle(Entry e) {
		e.done = !e.done;
		save();
	}

	public static void clearDone() {
		list().removeIf(e -> e.done);
		save();
	}

	/** Zuschauer teilt seine Stream-Serie (Watch Streak) im Chat – Spiel-Thread. */
	static void addStreak(String id, String user, String streams, String message) {
		if (addEvent("streak", id, user, "Stream-Serie: " + streams + " Streams in Folge", message)) {
			Alerts.post(Alerts.Type.STREAK, user, streams + " Streams in Folge"
					+ (message == null || message.isBlank() ? "" : " – „" + message + "“"));
		}
	}

	/** Streamereignis aus dem Twitch-Chat (Abo, Geschenk-Abo, Stream-Serie) in die Liste – Spiel-Thread. */
	static boolean addEvent(String kind, String id, String user, String title, String message) {
		Entry e = new Entry();
		e.id = id == null || id.isBlank() ? kind + "-" + user + "-" + title + "-" + System.currentTimeMillis() / 60_000L : kind + "-" + id;
		e.kind = kind;
		e.user = user;
		e.reward = title;
		e.input = message == null ? "" : message;
		e.time = System.currentTimeMillis();
		return add(e);
	}

	private static boolean add(Entry e) {
		List<Entry> l = list();
		for (Entry x : l) {
			if (x.id.equals(e.id)) {
				return false; // doppelt (Twitch kann nach einem Neuverbinden wiederholen)
			}
		}
		l.add(0, e);
		while (l.size() > MAX) {
			l.remove(l.size() - 1);
		}
		save();
		Minecraft mc = Minecraft.getInstance();
		Alerts.Type t = switch (e.kind == null ? "" : e.kind) {
			case "streak" -> Alerts.Type.STREAK;
			case "sub" -> Alerts.Type.SUB;
			default -> Alerts.Type.REDEEM;
		};
		if (WidgetConfig.get().streamerSound && mc.player != null && !Alerts.enabled(t)) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5F, 0.8F));
		}
		return true;
	}

	// ------------------------------------------------------------------ Verbindung

	public static synchronized void ensure(boolean on) {
		if (!on) {
			Thread t = thread;
			thread = null;
			if (t != null) {
				t.interrupt();
			}
			status = Status.OFF;
			return;
		}
		if (thread != null && thread.isAlive()) {
			return;
		}
		Thread t = new Thread(Redemptions::run, "Loothub-Twitch-Belohnungen");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	/** Zustand einer WebSocket-Sitzung. */
	private static final class Session implements WebSocket.Listener {
		final StringBuilder buf = new StringBuilder();
		volatile String id;
		volatile String reconnectUrl;
		volatile long lastMessage = System.currentTimeMillis();
		volatile int keepalive = 10;
		volatile boolean closed;

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buf.append(data);
			if (last) {
				String msg = buf.toString();
				buf.setLength(0);
				lastMessage = System.currentTimeMillis();
				handle(this, msg);
			}
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			closed = true;
			if (code == 4003 || code == 4004) {
				Loothub.LOGGER.debug("EventSub geschlossen: {} {}", code, reason);
			}
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			closed = true;
		}
	}

	private static void run() {
		Thread me = Thread.currentThread();
		long backoff = 2000;
		String url = WS_URL;
		boolean subscribe = true;
		WebSocket previous = null;
		while (thread == me) {
			status = Status.CONNECTING;
			Session s = new Session();
			WebSocket ws = null;
			try {
				ws = TwitchAuth.HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
						.buildAsync(URI.create(url), s).get(15, TimeUnit.SECONDS);
				// Auf die Begrüßung mit der Sitzungs-ID warten
				long until = System.currentTimeMillis() + 10_000;
				while (s.id == null && !s.closed && System.currentTimeMillis() < until && thread == me) {
					Thread.sleep(100);
				}
				if (s.id == null) {
					throw new IOException("keine Begrüßung von EventSub");
				}
				if (previous != null) {
					previous.abort(); // Umzug fertig: alte Verbindung erst jetzt schließen
					previous = null;
				}
				if (subscribe && !subscribe(s.id)) {
					ws.abort();
					if (status == Status.NOT_ALLOWED) {
						Thread.sleep(10 * 60_000L); // z. B. kein Affiliate – nicht dauernd neu versuchen
					}
					continue;
				}
				status = Status.CONNECTED;
				backoff = 2000;
				url = WS_URL;
				subscribe = true;
				// Laufen lassen, bis Twitch umziehen will, die Verbindung abbricht oder Lebenszeichen ausbleiben
				while (thread == me && !s.closed && s.reconnectUrl == null
						&& System.currentTimeMillis() - s.lastMessage < (s.keepalive + 10) * 1000L) {
					Thread.sleep(500);
				}
				if (s.reconnectUrl != null) {
					url = s.reconnectUrl; // Abos ziehen mit um
					subscribe = false;
					previous = ws; // erst schließen, wenn die neue Verbindung steht
					continue;
				}
				ws.abort();
			} catch (InterruptedException e) {
				break;
			} catch (Exception e) {
				Loothub.LOGGER.debug("EventSub-Verbindung getrennt", e);
				if (ws != null) {
					ws.abort();
				}
				if (previous != null) {
					previous.abort();
					previous = null;
				}
				url = WS_URL;
				subscribe = true;
			}
			if (thread != me) {
				break;
			}
			try {
				Thread.sleep(backoff);
			} catch (InterruptedException e) {
				break;
			}
			backoff = Math.min(backoff * 2, 60_000);
		}
	}

	/** Ein EventSub-Abo: Typ, Version und ob als Bedingung auch die Moderator-ID nötig ist. */
	private record Sub(String type, String version, boolean moderator) {
	}

	private static final Sub REDEEM = new Sub("channel.channel_points_custom_reward_redemption.add", "1", false);
	private static final List<Sub> SUBS = List.of(REDEEM,
			new Sub("channel.follow", "2", true),
			new Sub("channel.hype_train.begin", "2", false),
			new Sub("channel.hype_train.end", "2", false),
			new Sub("channel.ad_break.begin", "1", false),
			new Sub("channel.poll.begin", "1", false),
			new Sub("channel.poll.end", "1", false),
			new Sub("channel.prediction.begin", "1", false),
			new Sub("channel.prediction.end", "1", false));

	/**
	 * Alle Abos für die Sitzung anlegen. Einzelne Ablehnungen (z. B. ohne Affiliate keine Kanalpunkte und Werbung)
	 * sind kein Fehler – Hauptsache, irgendetwas kommt an.
	 */
	private static boolean subscribe(String sessionId) throws InterruptedException {
		int ok = 0;
		error = "";
		for (Sub sub : SUBS) {
			int code = subscribeOne(sessionId, sub);
			if (code < 0) {
				return false; // nicht angemeldet / nicht erreichbar
			}
			if (code == 202 || code == 409) {
				ok++;
			} else if (sub == REDEEM) {
				error = code == 403 ? "Kanalpunkte nicht verfügbar (nur Affiliate/Partner)" : "Kanalbelohnungen abgelehnt (" + code + ")";
			}
		}
		if (ok == 0) {
			status = Status.NOT_ALLOWED;
			if (error.isEmpty()) {
				error = "Twitch lehnt alle Ereignisse ab – bitte neu verbinden";
			}
			return false;
		}
		return true;
	}

	/** HTTP-Code der Anmeldung, -1 bei fehlender Anmeldung oder Netzfehler. */
	private static int subscribeOne(String sessionId, Sub sub) throws InterruptedException {
		for (int attempt = 0; attempt < 2; attempt++) {
			String token = TwitchAuth.token();
			String uid = TwitchAuth.userId();
			if (token.isEmpty() || uid.isEmpty()) {
				status = Status.ERROR;
				error = "Nicht mit Twitch verbunden";
				return -1;
			}
			JsonObject body = new JsonObject();
			body.addProperty("type", sub.type());
			body.addProperty("version", sub.version());
			JsonObject cond = new JsonObject();
			if (sub.type().equals("channel.raid")) {
				cond.addProperty("to_broadcaster_user_id", uid);
			} else {
				cond.addProperty("broadcaster_user_id", uid);
			}
			if (sub.moderator()) {
				cond.addProperty("moderator_user_id", uid);
			}
			body.add("condition", cond);
			JsonObject tr = new JsonObject();
			tr.addProperty("method", "websocket");
			tr.addProperty("session_id", sessionId);
			body.add("transport", tr);
			try {
				HttpResponse<String> r = TwitchAuth.HTTP.send(HttpRequest.newBuilder(
								URI.create("https://api.twitch.tv/helix/eventsub/subscriptions"))
						.timeout(Duration.ofSeconds(10))
						.header("Authorization", "Bearer " + token)
						.header("Client-Id", TwitchAuth.clientId())
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				int code = r.statusCode();
				if (code == 401 && attempt == 0) {
					TwitchAuth.refresh();
					continue;
				}
				if (code != 202 && code != 409) {
					JsonObject o = TwitchAuth.parse(r.body());
					Loothub.LOGGER.info("Twitch: Abo {} abgelehnt ({}): {}", sub.type(), code,
							o != null && o.has("message") ? o.get("message").getAsString() : "");
				}
				return code;
			} catch (IOException e) {
				status = Status.ERROR;
				error = "Twitch nicht erreichbar";
				return -1;
			}
		}
		return 401;
	}

	private static void handle(Session s, String msg) {
		JsonObject o = TwitchAuth.parse(msg);
		if (o == null || !o.has("metadata")) {
			return;
		}
		String type = str(o.getAsJsonObject("metadata"), "message_type");
		JsonObject payload = o.has("payload") ? o.getAsJsonObject("payload") : new JsonObject();
		switch (type) {
			case "session_welcome" -> {
				JsonObject sess = payload.getAsJsonObject("session");
				if (sess.has("keepalive_timeout_seconds") && !sess.get("keepalive_timeout_seconds").isJsonNull()) {
					s.keepalive = sess.get("keepalive_timeout_seconds").getAsInt();
				}
				s.id = str(sess, "id");
			}
			case "session_reconnect" -> s.reconnectUrl = str(payload.getAsJsonObject("session"), "reconnect_url");
			case "notification" -> {
				if (!payload.has("event")) {
					return;
				}
				JsonObject ev = payload.getAsJsonObject("event");
				String sub = str(o.getAsJsonObject("metadata"), "subscription_type");
				if (sub.equals(REDEEM.type())) {
					redemption(ev);
				} else {
					Alerts.fromEventSub(sub, ev);
				}
			}
			case "revocation" -> {
				status = Status.ERROR;
				error = "Twitch hat das Abo beendet – bitte neu verbinden";
				s.closed = true;
			}
			default -> {
				// session_keepalive: nur Lebenszeichen
			}
		}
	}

	private static void redemption(JsonObject ev) {
		Entry e = new Entry();
		e.id = str(ev, "id");
		e.user = str(ev, "user_name");
		if (e.user.isBlank()) {
			e.user = str(ev, "user_login");
		}
		e.input = str(ev, "user_input");
		if (ev.has("reward") && ev.get("reward").isJsonObject()) {
			JsonObject rw = ev.getAsJsonObject("reward");
			e.reward = str(rw, "title");
			e.cost = rw.has("cost") ? rw.get("cost").getAsInt() : 0;
		}
		try {
			e.time = Instant.parse(str(ev, "redeemed_at")).toEpochMilli();
		} catch (RuntimeException ex) {
			e.time = System.currentTimeMillis();
		}
		Minecraft.getInstance().execute(() -> {
			if (add(e)) {
				Alerts.post(Alerts.Type.REDEEM, e.user, e.reward + (e.input.isBlank() ? "" : " – „" + e.input + "“"));
			}
		});
	}

	static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}
}
