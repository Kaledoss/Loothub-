package de.loothub.client.progress;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import de.loothub.Loothub;
import de.loothub.client.cosmetics.CosmeticsServer;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.stats.StatsStore;

/**
 * Loothub-Fortschritt: Erfolge, Quests (alle 4 Stunden, wöchentlich, Gemeinschaft), Serie, Punkte, Shop und Ranglisten.
 * Die Mod meldet nur ihre Zähler-Summen (unterschrieben wie die Cosmetics); Punkte und Fortschritt rechnet der
 * Loothub-Server. Neu verdiente Punkte erscheinen als Einblendung.
 */
public final class Progress {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final long SYNC_MS = 10 * 60_000L;
	private static final long FIRST_DELAY_MS = 20_000L;
	private static final long TAGS_MS = 5 * 60_000L;
	private static final long BOARD_MS = 5 * 60_000L;

	/** Letzter Stand vom Server (oder null). */
	private static volatile JsonObject state;
	private static volatile long stateAt;
	private static volatile boolean busy;
	private static volatile String status = "";
	private static long joinedAt;
	private static long lastSync;
	private static long lastTags;
	private static volatile Map<String, String[]> tags = Map.of();
	private static final Map<String, JsonObject> BOARDS = new ConcurrentHashMap<>();
	private static final Map<String, Long> BOARD_AT = new ConcurrentHashMap<>();

	private Progress() {
	}

	public static boolean enabled() {
		return WidgetConfig.get().progress && CosmeticsServer.enabled();
	}

	public static JsonObject state() {
		return state;
	}

	public static long stateAt() {
		return stateAt;
	}

	public static boolean busy() {
		return busy;
	}

	public static String status() {
		return status;
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			long now = System.currentTimeMillis();
			if (mc.player == null || mc.level == null) {
				joinedAt = 0;
				return;
			}
			if (!enabled()) {
				return;
			}
			if (joinedAt == 0) {
				joinedAt = now;
			}
			if (!busy && now - joinedAt > FIRST_DELAY_MS && now - lastSync > SYNC_MS) {
				sync();
			}
			if (WidgetConfig.get().progressTitles && now - lastTags > TAGS_MS) {
				lastTags = now;
				fetchTags();
			}
		});
	}

	/** Gleich abgleichen, wenn der letzte Abgleich länger als eine Minute her ist (z. B. beim Öffnen des Menüs). */
	public static void syncSoon() {
		if (enabled() && !busy && System.currentTimeMillis() - lastSync > 60_000L && Minecraft.getInstance().player != null) {
			sync();
		}
	}

	// ------------------------------------------------------------------ Zähler

	/** Summen der eigenen Statistik, wie der Server sie erwartet. */
	static JsonObject counters() {
		Map<String, Long> all = StatsStore.sum(StatsStore.Range.TOTAL);
		long blocks = 0, placed = 0, mobs = 0, eat = 0;
		for (Map.Entry<String, Long> e : all.entrySet()) {
			String k = e.getKey();
			long v = e.getValue();
			if (k.startsWith("mined.")) {
				blocks += v;
			} else if (k.startsWith("placed.")) {
				placed += v;
			} else if (k.startsWith("killed.")) {
				mobs += v;
			} else if (k.startsWith("eaten.")) {
				eat += v;
			}
		}
		long distCm = 0;
		for (String k : new String[]{"general.walk", "general.sprint", "general.crouch", "general.swim", "general.elytra",
				"general.fly", "general.ride"}) {
			distCm += all.getOrDefault(k, 0L);
		}
		double money = 0;
		for (var s : de.loothub.client.jobs.JobHistory.all()) {
			money += s.money();
		}
		JsonObject o = new JsonObject();
		o.addProperty("blocks", blocks);
		o.addProperty("placed", placed);
		o.addProperty("mobs", mobs);
		o.addProperty("fish", de.loothub.client.jobs.FishingLog.all().size());
		o.addProperty("play", all.getOrDefault("general.play_time", 0L) / 60_000L);
		o.addProperty("dist", distCm / 100L);
		o.addProperty("treasures", all.getOrDefault("general.treasures", 0L));
		o.addProperty("jobmoney", (long) Math.max(0, money));
		o.addProperty("itemdex", de.loothub.client.itemdex.Itemdex.entries().size());
		o.addProperty("eat", eat);
		o.addProperty("jumps", all.getOrDefault("general.jumps", 0L));
		return o;
	}

	// ------------------------------------------------------------------ Server

	private static void sync() {
		lastSync = System.currentTimeMillis();
		JsonObject p = new JsonObject();
		p.addProperty("op", "sync");
		p.add("stats", counters());
		send(p, null);
	}

	/** Shop-Artikel kaufen. */
	public static void buy(String item, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "buy");
		p.addProperty("item", item);
		send(p, done);
	}

	/** Titel bzw. Abzeichen anlegen ("" = ablegen, null = unverändert). */
	public static void equip(String title, String badge, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "equip");
		if (title != null) {
			p.addProperty("title", title);
		}
		if (badge != null) {
			p.addProperty("badge", badge);
		}
		send(p, done);
	}

	/** Unterschriebene Anfrage im Hintergrund; done bekommt null (ok) oder einen Fehlerschlüssel. */
	private static void send(JsonObject payload, Consumer<String> done) {
		busy = true;
		Minecraft mc = Minecraft.getInstance();
		Thread t = new Thread(() -> {
			String error = null;
			try {
				var pair = mc.getProfileKeyPairManager().prepareKeyPair().get(20, java.util.concurrent.TimeUnit.SECONDS)
						.orElseThrow(() -> new IllegalStateException("kein Profil-Schlüssel"));
				var data = pair.publicKey().data();
				UUID self = mc.getUser().getProfileId();
				String uuid = self.toString().toLowerCase(Locale.ROOT);
				long ts = System.currentTimeMillis();
				String text = payload.toString();
				java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
				sig.initSign(pair.privateKey());
				sig.update(("loothub-lh\n" + uuid + "\n" + ts + "\n" + text).getBytes(StandardCharsets.UTF_8));
				Base64.Encoder b64 = Base64.getEncoder();
				JsonObject body = new JsonObject();
				body.addProperty("uuid", uuid);
				body.addProperty("me", mc.getUser().getName());
				body.addProperty("ts", ts);
				body.addProperty("payload", text);
				body.addProperty("publicKey", b64.encodeToString(data.key().getEncoded()));
				body.addProperty("expiresAt", data.expiresAt().toEpochMilli());
				body.addProperty("keySignature", b64.encodeToString(data.keySignature()));
				body.addProperty("signature", b64.encodeToString(sig.sign()));
				HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(CosmeticsServer.BASE + "/lh"))
						.timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
						.header("User-Agent", "Loothub-Mod")
						.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (r.statusCode() == 200) {
					JsonObject s = JsonParser.parseString(r.body()).getAsJsonObject();
					if (s.has("error")) {
						error = s.get("error").getAsString();
					}
					state = s;
					stateAt = System.currentTimeMillis();
					status = "";
					mc.execute(() -> announce(s));
				} else if (r.statusCode() == 503) {
					error = "busy";
					status = "busy";
				} else {
					Loothub.LOGGER.debug("Loothub-Fortschritt: Server lehnt ab ({}): {}", r.statusCode(), r.body());
					error = "error";
					status = "error";
				}
			} catch (Exception e) {
				Loothub.LOGGER.debug("Loothub-Fortschritt: Server nicht erreichbar", e);
				error = "offline";
				status = "offline";
			} finally {
				busy = false;
			}
			if (done != null) {
				String err = error;
				mc.execute(() -> done.accept(err));
			}
		}, "Loothub-Fortschritt");
		t.setDaemon(true);
		t.start();
	}

	/** Rangliste (zwischengespeichert); lädt im Hintergrund nach, wenn sie älter als 5 Minuten ist. */
	public static JsonObject board(String b) {
		long now = System.currentTimeMillis();
		Long at = BOARD_AT.get(b);
		if (at == null || now - at > BOARD_MS) {
			BOARD_AT.put(b, now);
			get("/lh/board?b=" + b, e -> {
				if (e.isJsonObject() && e.getAsJsonObject().has("list")) {
					BOARDS.put(b, e.getAsJsonObject());
				}
			});
		}
		return BOARDS.get(b);
	}

	private static void fetchTags() {
		get("/lh/tags", e -> {
			if (!e.isJsonObject()) {
				return;
			}
			Map<String, String[]> m = new HashMap<>();
			JsonObject o = e.getAsJsonObject();
			for (String k : o.keySet()) {
				if (o.get(k).isJsonArray() && o.getAsJsonArray(k).size() >= 2) {
					m.put(k.toLowerCase(Locale.ROOT), new String[]{o.getAsJsonArray(k).get(0).getAsString(),
							o.getAsJsonArray(k).get(1).getAsString()});
				}
			}
			tags = m;
		});
	}

	private static void get(String path, Consumer<JsonElement> ok) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(CosmeticsServer.BASE + path)).timeout(Duration.ofSeconds(15))
				.header("User-Agent", "Loothub-Mod").GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((r, t) -> {
			try {
				if (t == null && r.statusCode() == 200) {
					ok.accept(JsonParser.parseString(r.body()));
				}
			} catch (RuntimeException e) {
				Loothub.LOGGER.debug("Loothub-Fortschritt: Antwort nicht lesbar", e);
			}
		});
	}

	// ------------------------------------------------------------------ Titel und Abzeichen anderer Spieler

	/** [Titel-ID, Abzeichen-ID] eines Spielers; eigener Stand kommt direkt vom letzten Abgleich. */
	public static String[] tagsOf(UUID id) {
		if (id == null || !enabled() || !WidgetConfig.get().progressTitles) {
			return null;
		}
		JsonObject s = state;
		Minecraft mc = Minecraft.getInstance();
		if (s != null && id.equals(mc.getUser().getProfileId())) {
			String t = s.has("title") ? s.get("title").getAsString() : "";
			String b = s.has("badge") ? s.get("badge").getAsString() : "";
			return t.isEmpty() && b.isEmpty() ? null : new String[]{t, b};
		}
		return tags.get(id.toString().toLowerCase(Locale.ROOT));
	}

	/** Titel über dem Namensschild: „★ Bergarbeiter“ – oder null. */
	public static Component titleLine(UUID id) {
		String[] t = tagsOf(id);
		if (t == null) {
			return null;
		}
		Component badge = Shop.badge(t[1]);
		Component title = t[0].isEmpty() ? null : Shop.titleText(t[0]);
		if (badge == null && title == null) {
			return null;
		}
		if (title == null) {
			return badge;
		}
		return badge == null ? title : Component.empty().append(badge).append(" ").append(title);
	}

	// ------------------------------------------------------------------ Einblendungen

	private record Toast(String title, String sub, int color) {
	}

	private static final Deque<Toast> TOASTS = new ArrayDeque<>();
	private static Toast current;
	private static long since;
	private static final long SHOW_MS = 4500, FADE_MS = 300;

	private static void announce(JsonObject s) {
		if (!s.has("news") || !s.get("news").isJsonArray()) {
			return;
		}
		var news = s.getAsJsonArray("news");
		if (news.size() > 4) {
			// viel auf einmal (z. B. beim ersten Abgleich mit alter Statistik): eine Sammel-Einblendung statt einer Flut
			int sum = 0;
			for (JsonElement e : news) {
				sum += e.getAsJsonObject().has("p") ? e.getAsJsonObject().get("p").getAsInt() : 0;
			}
			TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.many", news.size()).getString(),
					Component.translatable("loothub.lh.toast.points", sum).getString(), 0xF2C14E));
			return;
		}
		for (JsonElement e : news) {
			if (!e.isJsonObject() || TOASTS.size() > 20) {
				continue;
			}
			JsonObject n = e.getAsJsonObject();
			String k = n.get("k").getAsString();
			int p = n.has("p") ? n.get("p").getAsInt() : 0;
			String plus = Component.translatable("loothub.lh.toast.points", p).getString();
			String stat = n.has("stat") ? n.get("stat").getAsString() : "";
			switch (k) {
				case "quest", "week" -> TOASTS.add(new Toast(
						Component.translatable(k.equals("week") ? "loothub.lh.toast.week" : "loothub.lh.toast.quest").getString(),
						Texts.goal(stat, n.get("goal").getAsLong()).getString() + " · " + plus, 0x55E06A));
				case "streak" -> TOASTS.add(new Toast(
						Component.translatable("loothub.lh.toast.streak", n.get("n").getAsInt()).getString(), plus, 0xFFD166));
				case "comm" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.comm").getString(),
						Texts.stat(stat).getString() + " · " + plus, 0x6BD5FF));
				case "ach" -> TOASTS.add(new Toast(
						Component.translatable("loothub.lh.toast.ach", Texts.stat(stat), n.get("tier").getAsInt()).getString(),
						plus, 0xF2C14E));
				default -> {
				}
			}
		}
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (current != null && now - since > SHOW_MS) {
			current = null;
		}
		if (current == null && !TOASTS.isEmpty()) {
			current = TOASTS.poll();
			since = now;
			if (mc.player != null) {
				mc.player.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 0.35F, 1.4F);
			}
		}
		if (current == null || mc.options.hideGui) {
			return;
		}
		long age = now - since;
		float a = age < FADE_MS ? age / (float) FADE_MS : age > SHOW_MS - FADE_MS ? (SHOW_MS - age) / (float) FADE_MS : 1f;
		a = Mth.clamp(a, 0f, 1f);
		int[] size = de.loothub.client.streamer.Alerts.boxSize(mc.font, current.title(), current.sub());
		int x = (g.guiWidth() - size[0]) / 2;
		int y = 6 + (int) ((1 - a) * -12);
		de.loothub.client.streamer.Alerts.drawBox(g, mc.font, current.title(), current.sub(), current.color(), x, y, a);
	}
}
