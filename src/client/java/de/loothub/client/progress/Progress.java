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

import de.loothub.client.Fmt;
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
	private static final long FIRST_DELAY_MS = 5_000L;
	private static final long TAGS_MS = 5 * 60_000L;
	private static final long BOARD_MS = 5 * 60_000L;

	/** Zähler zum letzten Abgleich – für den Live-Fortschritt angepinnter Quests. */
	private static volatile JsonObject atSync;
	private static JsonObject liveCounters;
	private static long liveAt;

	/** Fortschritt einer Quest jetzt: Stand vom Server plus was seit dem Abgleich dazukam. */
	public static long liveHave(String stat, long serverHave) {
		int colon = stat.indexOf(':');
		if (colon > 0) {
			return liveHaveSpecial(stat.substring(0, colon), stat.substring(colon + 1), serverHave);
		}
		JsonObject base = atSync;
		if (base == null || !base.has(stat)) {
			return serverHave;
		}
		long now = System.currentTimeMillis();
		if (liveCounters == null || now - liveAt > 1000) {
			liveCounters = counters();
			liveAt = now;
		}
		long cur = liveCounters.has(stat) ? liveCounters.get(stat).getAsLong() : 0;
		return serverHave + Math.max(0, cur - base.get(stat).getAsLong());
	}

	/** Zähler je Block/Mob/Item zum letzten Abgleich (b, m, p, i) – für besondere Quests wie „p:stone_bricks“. */
	private static volatile JsonObject atSyncCol;
	private static JsonObject liveCol;
	private static long liveColAt;

	private static long liveHaveSpecial(String kind, String id, long serverHave) {
		JsonObject base = atSyncCol;
		if (base == null) {
			return serverHave;
		}
		long now = System.currentTimeMillis();
		if (liveCol == null || now - liveColAt > 1000) {
			liveCol = Collection.counters(StatsStore.sum(StatsStore.Range.TOTAL));
			liveColAt = now;
		}
		long was = num(base, kind, id), cur = num(liveCol, kind, id); // nicht gemeldet = 0
		return serverHave + Math.max(0, cur - was);
	}

	private static long num(JsonObject o, String kind, String id) {
		if (o == null || !o.has(kind) || !o.get(kind).isJsonObject()) {
			return 0;
		}
		JsonObject k = o.getAsJsonObject(kind);
		return k.has(id) ? k.get(id).getAsLong() : 0;
	}

	// ------------------------------------------------------------------ angepinnte Quests (höchstens 3)

	public static final int MAX_PINS = 3;

	/** Schlüssel einer Quest: „s|blocks|300“ (4 Stunden) bzw. „w|…“ (Woche). */
	public static String pinKey(String kind, JsonObject q) {
		return kind + "|" + q.get("stat").getAsString() + "|" + q.get("goal").getAsLong();
	}

	private static java.util.List<String> pins() {
		WidgetConfig c = WidgetConfig.get();
		if (c.pinnedQuests == null) {
			c.pinnedQuests = new java.util.ArrayList<>();
		}
		return c.pinnedQuests;
	}

	public static boolean pinned(String key) {
		return pins().contains(key);
	}

	/** An-/abpinnen; false, wenn schon zwei angepinnt sind. */
	public static boolean togglePin(String key) {
		var list = pins();
		if (list.remove(key)) {
			WidgetConfig.get().save();
			return true;
		}
		if (list.size() >= MAX_PINS) {
			return false;
		}
		list.add(key);
		WidgetConfig.get().save();
		return true;
	}

	/** Angepinnte Quests, die es gerade gibt: [Art, Quest]. Veraltete werden entfernt. */
	public static java.util.List<Object[]> pinnedQuests() {
		java.util.List<Object[]> out = new java.util.ArrayList<>();
		JsonObject s = state;
		if (s == null) {
			return out;
		}
		var list = pins();
		java.util.Set<String> present = new java.util.HashSet<>();
		for (String kind : new String[]{"s", "w"}) {
			JsonObject part = s.getAsJsonObject(kind.equals("s") ? "slot" : "week");
			if (part == null) {
				continue;
			}
			if (part.has("ends") && part.get("ends").getAsLong() < System.currentTimeMillis()) {
				continue; // Abschnitt vorbei (z. B. gespeicherter Stand vom letzten Spielen) – neue Quests kommen mit dem Abgleich
			}
			for (JsonElement e : part.getAsJsonArray("list")) {
				String key = pinKey(kind, e.getAsJsonObject());
				if (e.getAsJsonObject().get("done").getAsBoolean() || ALERTED.contains(key)) {
					continue; // geschafft: angepinnte Quest verschwindet
				}
				present.add(key);
				if (list.contains(key)) {
					out.add(new Object[]{kind, e.getAsJsonObject()});
				}
			}
		}
		// Questkette der Woche („k|kette“, bleibt über alle Schritte angepinnt) und Gemeinschaftsquest („c|stat|ziel“)
		if (s.has("chain") && s.get("chain").isJsonObject()) {
			JsonObject ch = s.getAsJsonObject("chain");
			int step = ch.get("step").getAsInt();
			var steps = ch.getAsJsonArray("steps");
			String key = "k|" + ch.get("id").getAsString();
			if (step < steps.size()) {
				present.add(key);
				if (list.contains(key)) {
					var cur = steps.get(step).getAsJsonArray();
					JsonObject q = new JsonObject();
					q.addProperty("stat", cur.get(0).getAsString());
					q.addProperty("goal", cur.get(1).getAsLong());
					q.addProperty("have", ch.get("have").getAsLong());
					q.addProperty("done", false);
					out.add(new Object[]{"k", q});
				}
			}
		}
		if (s.has("comm") && s.get("comm").isJsonObject()) {
			JsonObject c = s.getAsJsonObject("comm");
			String key = "c|" + c.get("stat").getAsString() + "|" + c.get("goal").getAsLong();
			if (c.get("sum").getAsLong() < c.get("goal").getAsLong()) {
				present.add(key);
				if (list.contains(key)) {
					JsonObject q = new JsonObject();
					q.addProperty("stat", c.get("stat").getAsString());
					q.addProperty("goal", c.get("goal").getAsLong());
					q.addProperty("have", c.get("sum").getAsLong());
					q.addProperty("done", false);
					out.add(new Object[]{"c", q});
				}
			}
		} else {
			// ohne Angaben zur Gemeinschaftsquest (z. B. direkt nach einem Kauf) die Anpinnung behalten
			list.stream().filter(k -> k.startsWith("c|")).forEach(present::add);
		}
		if (list.removeIf(k -> !present.contains(k))) {
			WidgetConfig.get().save(); // Abschnitt bzw. Woche vorbei
		}
		return out;
	}

	/** Anzeige der angepinnten Quests im Spiel (verschiebbar über „HUD verschieben“). */
	public static void renderPins(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!enabled() || mc.options.hideGui || mc.player == null || mc.screen instanceof de.loothub.client.HudLayoutScreen) {
			return;
		}
		java.util.List<Object[]> pins = pinnedQuests();
		if (pins.isEmpty()) {
			return;
		}
		drawPins(g, mc.font, pins, -1, -1);
	}

	public static final int PIN_W = 160;

	public static int pinHeight(int n) {
		return 4 + n * 24;
	}

	/** Zeichnet die Liste; x/y = -1 nimmt die gespeicherte Lage. */
	public static void drawPins(GuiGraphics g, net.minecraft.client.gui.Font font, java.util.List<Object[]> pins, int x, int y) {
		WidgetConfig c = WidgetConfig.get();
		int h = pinHeight(pins.size());
		if (x < 0) {
			x = (int) Math.round(c.questHudX * (g.guiWidth() - PIN_W));
			y = (int) Math.round(c.questHudY * (g.guiHeight() - h));
		}
		g.fill(x, y, x + PIN_W, y + h, 0x90000000);
		g.fill(x, y, x + 2, y + h, 0xFFF2C14E);
		int ry = y + 3;
		for (Object[] p : pins) {
			JsonObject q = (JsonObject) p[1];
			String stat = q.get("stat").getAsString();
			long goal = q.get("goal").getAsLong();
			boolean done = q.get("done").getAsBoolean();
			long have = done ? goal : Math.min(goal, liveHave(stat, q.get("have").getAsLong()));
			String prefix = switch ((String) p[0]) {
				case "w" -> "W · ";
				case "k" -> "✦ ";
				case "c" -> "♦ ";
				default -> "";
			};
			String label = prefix + Texts.goal(stat, goal).getString();
			if (font.width(label) > PIN_W - 12) {
				label = font.plainSubstrByWidth(label, PIN_W - 16) + "…";
			}
			g.drawString(font, label, x + 6, ry + 1, done ? 0xFF55E06A : 0xFFFFFFFF, true);
			int bw = PIN_W - 12;
			double f = goal == 0 ? 1 : have / (double) goal;
			g.fill(x + 6, ry + 12, x + 6 + bw, ry + 15, 0xFF2A1B1E);
			g.fill(x + 6, ry + 12, x + 6 + (int) Math.round(bw * Math.min(1, f)), ry + 15, done ? 0xFF55E06A : 0xFFE23B3B);
			String prog = done ? "✔" : Texts.value(stat, have) + " / " + Texts.value(stat, goal);
			g.drawString(font, prog, x + PIN_W - 6 - font.width(prog), ry + 16, 0xFFB9A9AB, false);
			ry += 24;
		}
	}

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

	/** Ist man laut Server Admin (Taler ändern, Ranglisten zurücksetzen)? */
	/** Worker-Stand, den diese Mod-Version braucht (muss zu WORKER_VERSION in cosmetics-worker.js passen). */
	public static final int WORKER_NEEDED = 18;

	/** Admin: Läuft auf dem Server ein älterer Worker, als diese Mod braucht? (unbekannt = nein) */
	public static boolean workerOutdated() {
		JsonObject s = state;
		if (s == null || !isAdmin()) {
			return false;
		}
		int wv = s.has("wv") ? s.get("wv").getAsInt() : 0;
		return wv < WORKER_NEEDED;
	}

	/** Admin: ungelesene Feedback-Meldungen (für das „+1“ am Knopf). */
	public static int feedbackUnread() {
		JsonObject s = state;
		return s != null && s.has("fbUnread") ? s.get("fbUnread").getAsInt() : 0;
	}

	public static void setFeedbackUnread(int n) {
		JsonObject s = state;
		if (s != null) {
			s.addProperty("fbUnread", Math.max(0, n));
		}
	}

	public static boolean isAdmin() {
		JsonObject s = state;
		return s != null && s.has("admin") && s.get("admin").getAsBoolean();
	}

	public static boolean busy() {
		return busy;
	}

	public static String status() {
		return status;
	}

	// ------------------------------------------------------------------ Zwischenspeicher (Quests sofort nach dem Einloggen)

	private static java.nio.file.Path cacheFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("loothub-quests-cache.json");
	}

	private static String ownId() {
		var u = Minecraft.getInstance().getUser().getProfileId();
		return u == null ? "" : u.toString();
	}

	private static void saveCache(JsonObject s) {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("id", ownId());
			o.add("state", s);
			if (atSync != null) {
				o.add("atSync", atSync);
			}
			if (atSyncCol != null) {
				o.add("atSyncCol", atSyncCol);
			}
			de.loothub.client.AsyncSave.write(cacheFile(), o.toString());
		} catch (RuntimeException e) {
			Loothub.LOGGER.debug("Quest-Zwischenspeicher nicht geschrieben", e);
		}
	}

	/** Kontowechsel (z. B. LabyMod-Kontowechsel ohne Neustart): Stand des alten Kontos verwerfen. */
	public static void resetForAccount() {
		state = null;
		atSync = null;
		atSyncCol = null;
		joinedAt = 0;
		lastSync = 0;
	}

	private static void loadCache(Minecraft mc) {
		if (state != null) {
			return;
		}
		try {
			java.nio.file.Path f = cacheFile();
			if (!java.nio.file.Files.exists(f)) {
				return;
			}
			JsonObject o = JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
			if (!ownId().equals(o.has("id") ? o.get("id").getAsString() : "")) {
				return; // anderes Konto
			}
			atSync = o.has("atSync") ? o.getAsJsonObject("atSync") : null;
			atSyncCol = o.has("atSyncCol") ? o.getAsJsonObject("atSyncCol") : null;
			state = o.getAsJsonObject("state");
		} catch (Exception e) {
			Loothub.LOGGER.debug("Quest-Zwischenspeicher nicht lesbar", e);
		}
	}

	public static void register() {
		de.loothub.client.util.Guard.tick("Progress", Secrets::tick);
		net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.CHAT.register(Secrets::chat);
		de.loothub.client.util.Guard.tick("Progress#2", mc -> {
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
				loadCache(mc); // angepinnte Quests sofort zeigen, nicht erst nach dem ersten Abgleich
			}
			// Neuer 4-Stunden-Abschnitt bzw. neue Woche: gleich abgleichen, damit die neuen Quests sofort da sind
			JsonObject st = state;
			if (st != null && !busy && now - lastSync > 15_000L) {
				for (String part : new String[]{"slot", "week"}) {
					if (st.has(part) && st.get(part).isJsonObject() && st.getAsJsonObject(part).has("ends")) {
						long ends = st.getAsJsonObject(part).get("ends").getAsLong();
						if (now > ends + 2_000L && lastSync < ends + 2_000L) {
							sync();
							break;
						}
					}
				}
			}
			if (!busy && now - joinedAt > FIRST_DELAY_MS && now - lastSync > SYNC_MS) {
				sync();
			}
			if (now - lastQuestCheck > 1000) {
				lastQuestCheck = now;
				checkQuestsDone();
			}
			if (creditSoon && !busy && now - lastSync > 15_000L) {
				creditSoon = false;
				sync(); // geschaffte Quest gleich gutschreiben lassen
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

	// ------------------------------------------------------------------ Quest geschafft (sofort, ohne auf den Abgleich zu warten)

	private static long lastQuestCheck;
	private static boolean creditSoon;
	/** Schon gemeldete Quests („s|blocks|300“), je Abschnitt bzw. Woche. */
	private static final java.util.Set<String> ALERTED = new java.util.HashSet<>();
	private static long alertedSlot, alertedWeek;

	private static void checkQuestsDone() {
		JsonObject s = state;
		if (s == null || !s.has("slot") || !s.has("week")) {
			return;
		}
		long slotEnds = s.getAsJsonObject("slot").get("ends").getAsLong();
		long weekEnds = s.getAsJsonObject("week").get("ends").getAsLong();
		if (slotEnds != alertedSlot) {
			alertedSlot = slotEnds;
			ALERTED.removeIf(k -> k.startsWith("s|"));
		}
		if (weekEnds != alertedWeek) {
			alertedWeek = weekEnds;
			ALERTED.removeIf(k -> k.startsWith("w|"));
		}
		for (String kind : new String[]{"s", "w"}) {
			if ((kind.equals("s") ? slotEnds : weekEnds) < System.currentTimeMillis()) {
				continue; // Abschnitt vorbei: nichts mehr als geschafft melden
			}
			for (JsonElement e : s.getAsJsonObject(kind.equals("s") ? "slot" : "week").getAsJsonArray("list")) {
				JsonObject q = e.getAsJsonObject();
				String key = pinKey(kind, q);
				if (q.get("done").getAsBoolean()) {
					continue; // schon vom Server bestätigt – dessen Einblendung kommt mit dem Abgleich
				}
				String stat = q.get("stat").getAsString();
				long goal = q.get("goal").getAsLong();
				if (liveHave(stat, q.get("have").getAsLong()) >= goal && ALERTED.add(key)) {
					TOASTS.add(new Toast(Component.translatable(kind.equals("w") ? "loothub.lh.toast.week" : "loothub.lh.toast.quest").getString(),
							Texts.goal(stat, goal).getString() + " · "
									+ Component.translatable("loothub.lh.toast.points", q.get("points").getAsInt()).getString(), 0x55E06A));
					creditSoon = true;
					if (pins().remove(key)) {
						WidgetConfig.get().save(); // geschafft: nicht mehr angepinnt
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ Zähler

	/** Summen der eigenen Statistik, wie der Server sie erwartet. */
	static JsonObject counters() {
		Map<String, Long> all = StatsStore.sum(StatsStore.Range.TOTAL);
		long blocks = 0, placed = 0, mobs = 0, eat = 0, picked = 0, dropped = 0;
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
			} else if (k.startsWith("picked.")) {
				picked += v;
			} else if (k.startsWith("dropped.")) {
				dropped += v;
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
		o.addProperty("play", all.getOrDefault("general.active_time", 0L) / 60_000L); // ohne AFK-Zeit
		o.addProperty("dist", distCm / 100L);
		o.addProperty("treasures", all.getOrDefault("general.treasures", 0L));
		o.addProperty("jobmoney", (long) Math.max(0, money));
		o.addProperty("itemdex", de.loothub.client.itemdex.Itemdex.entries().size());
		o.addProperty("eat", eat);
		o.addProperty("jumps", all.getOrDefault("general.jumps", 0L));
		o.addProperty("picked", picked);
		o.addProperty("dropped", dropped);
		o.addProperty("nether", all.getOrDefault("general.nether_time", 0L) / 60_000L);
		o.addProperty("deep", all.getOrDefault("general.deep_time", 0L) / 60_000L);
		return o;
	}

	// ------------------------------------------------------------------ Server

	private static void sync() {
		lastSync = System.currentTimeMillis();
		JsonObject p = new JsonObject();
		p.addProperty("op", "sync");
		p.addProperty("mv", 2); // Feature-Stand der Mod: kann besondere/Erkundungs-Quests anzeigen
		p.add("stats", counters());
		p.add("col", Collection.counters(StatsStore.sum(StatsStore.Range.TOTAL)));
		p.add("fk", de.loothub.client.jobs.FishingLog.knownKinds());
		// für die Admin-Übersicht: Mod-Version und welche Loothub-Funktionen eingeschaltet sind (nur Namen der Schalter)
		p.addProperty("ver", de.loothub.client.Updater.currentVersion());
		com.google.gson.JsonArray feat = new com.google.gson.JsonArray();
		de.loothub.client.jobs.WidgetConfig cfg = de.loothub.client.jobs.WidgetConfig.get();
		for (java.lang.reflect.Field f : de.loothub.client.jobs.WidgetConfig.class.getFields()) {
			try {
				if (f.getType() == boolean.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getBoolean(cfg)) {
					feat.add(f.getName());
				}
			} catch (IllegalAccessException ignored) {
				// nicht lesbar: weglassen
			}
		}
		p.add("feat", feat);
		send(p, null);
	}

	/** Shop-Artikel kaufen. */
	public static void buy(String item, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "buy");
		p.addProperty("item", item);
		send(p, done);
	}

	/** Überraschungsgeschenk des Haustiers abholen (der Server erlaubt eins am Tag). */
	public static void petGift() {
		if (!enabled()) {
			return;
		}
		JsonObject p = new JsonObject();
		p.addProperty("op", "petgift");
		send(p, null);
	}

	/** Geheimnis gefunden melden. */
	public static void secret(String id) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "secret");
		p.addProperty("id", id);
		send(p, err -> {
		});
	}

	/** Antwort im täglichen Quiz. */
	public static void quiz(int answer, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "quiz");
		p.addProperty("a", answer);
		send(p, done);
	}

	/** Punktestand eines Minispiels für die Rangliste melden. */
	public static void mgSubmit(String game, int score, long ms, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "mg");
		p.addProperty("game", game);
		p.addProperty("score", score);
		p.addProperty("ms", ms);
		send(p, done);
	}

	/** Minispiel-Rangliste laden (week 0 = diese Woche, 1 = letzte; Serien ohne Woche). done bekommt die Antwort oder null. */
	public static void mgBoard(String game, int week, Consumer<JsonObject> done) {
		String me = Minecraft.getInstance().getUser().getProfileId().toString().toLowerCase(Locale.ROOT);
		get("/lh/mg?g=" + game + "&w=" + (week == 2 ? "a" : String.valueOf(week)) + "&u=" + me, e -> {
			JsonObject o = e.isJsonObject() && e.getAsJsonObject().has("list") ? e.getAsJsonObject() : null;
			Minecraft.getInstance().execute(() -> done.accept(o));
		});
	}

	/** Duo-Partner eintragen ("" = austragen). */
	public static void duo(String target, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "duo");
		p.addProperty("target", target);
		send(p, done);
	}

	/** Tipp beim Block-Raten. */
	public static void guess(String choice, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "guess");
		p.addProperty("c", choice);
		send(p, done);
	}

	/** Artikel an einen anderen Spieler verschenken (der Schenkende zahlt). */
	public static void gift(String item, String target, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "gift");
		p.addProperty("item", item);
		p.addProperty("target", target);
		send(p, done);
	}

	/** Titelfarbe anlegen ("" = Gold). */
	public static void equipColor(String color, Consumer<String> done) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "equip");
		p.addProperty("tcolor", color);
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
		send(payload, done, null);
	}

	/** Beliebige unterschriebene Anfrage, deren Antwort (oder null) direkt zurückkommt – z. B. für Freunde. */
	public static void request(JsonObject payload, Consumer<JsonObject> result) {
		send(payload, null, result);
	}

	/** Admin: Taler eines Spielers ändern bzw. eine Rangliste zurücksetzen; result bekommt die Antwort (oder null). */
	public static void admin(JsonObject payload, Consumer<JsonObject> result) {
		send(payload, null, result);
	}

	private static void send(JsonObject payload, Consumer<String> done, Consumer<JsonObject> adminResult) {
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
				if (adminResult != null) {
					JsonObject s = null;
					try {
						s = JsonParser.parseString(r.body()).getAsJsonObject();
					} catch (RuntimeException ignored) {
						// keine lesbare Antwort
					}
					JsonObject res = s;
					mc.execute(() -> adminResult.accept(res));
				} else if (r.statusCode() == 200) {
					JsonObject s = JsonParser.parseString(r.body()).getAsJsonObject();
					if (s.has("error")) {
						error = s.get("error").getAsString();
					}
					state = s;
					stateAt = System.currentTimeMillis();
					if ("sync".equals(payload.get("op").getAsString())) {
						atSync = payload.getAsJsonObject("stats"); // Stand der Zähler zu diesem Abgleich
						atSyncCol = payload.has("col") ? payload.getAsJsonObject("col") : null;
						saveCache(s);
					}
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
				if (adminResult != null) {
					mc.execute(() -> adminResult.accept(null));
				}
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

	/** Rangliste für einen Zeitraum (d/w/m/a, zwischengespeichert); lädt im Hintergrund nach, wenn sie älter als 5 Minuten ist. */
	public static JsonObject board(String b, String period) {
		String key = b + "/" + period;
		long now = System.currentTimeMillis();
		Long at = BOARD_AT.get(key);
		if (at == null || now - at > BOARD_MS) {
			BOARD_AT.put(key, now);
			String me = Minecraft.getInstance().getUser().getProfileId().toString().toLowerCase(Locale.ROOT);
			get("/lh/board?b=" + b + "&p=" + period + "&u=" + me, e -> {
				if (e.isJsonObject() && e.getAsJsonObject().has("list")) {
					BOARDS.put(key, e.getAsJsonObject());
				}
			});
		}
		return BOARDS.get(key);
	}

	/** Spielerkarte (öffentliche Werte) laden; done bekommt die Antwort oder null. */
	public static void card(String uuid, Consumer<JsonObject> done) {
		get("/lh/card?u=" + uuid.toLowerCase(Locale.ROOT), e -> {
			JsonObject o = e.isJsonObject() && !e.getAsJsonObject().has("error") ? e.getAsJsonObject() : null;
			Minecraft.getInstance().execute(() -> done.accept(o));
		});
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
					var arr = o.getAsJsonArray(k);
					m.put(k.toLowerCase(Locale.ROOT), new String[]{arr.get(0).getAsString(), arr.get(1).getAsString(),
							arr.size() > 2 ? arr.get(2).getAsString() : ""});
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
			String c = s.has("tcolor") ? s.get("tcolor").getAsString() : "";
			return t.isEmpty() && b.isEmpty() ? null : new String[]{t, b, c};
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
		Component title = t[0].isEmpty() ? null : Shop.titleText(t[0], t.length > 2 ? t[2] : "");
		if (badge == null && title == null) {
			return null;
		}
		if (title == null) {
			return badge;
		}
		return badge == null ? title : Component.empty().append(badge).append(" ").append(title);
	}

	// ------------------------------------------------------------------ Einblendungen

	private record Toast(String title, String sub, int color, boolean epic) {
		Toast(String title, String sub, int color) {
			this(title, sub, color, false);
		}
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
				case "quest", "week" -> {
					if (ALERTED.contains((k.equals("week") ? "w|" : "s|") + stat + "|" + n.get("goal").getAsLong())) {
						break; // schon beim Schaffen eingeblendet
					}
					TOASTS.add(new Toast(
						Component.translatable(k.equals("week") ? "loothub.lh.toast.week" : "loothub.lh.toast.quest").getString(),
						Texts.goal(stat, n.get("goal").getAsLong()).getString() + " · " + plus, 0x55E06A));
				}
				case "duo" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.duo").getString(),
						Texts.stat(stat).getString() + " · " + plus, 0x6BD5FF));
				case "mg" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.mg", n.get("r").getAsInt(),
						Component.translatable("loothub.lh.mg." + n.get("g").getAsString())).getString(), plus, 0xF2C14E));
				case "login" -> TOASTS.add(new Toast(
						Component.translatable("loothub.lh.toast.login", n.get("n").getAsInt()).getString(), plus, 0xFFB833));
				case "petgift" -> TOASTS.add(new Toast(Component.translatable("loothub.petgift.toast").getString(), plus, 0xFF9AD2));
				case "cal" -> {
					TOASTS.add(new Toast(Component.translatable("loothub.cal.toast", n.get("n").getAsInt()).getString(), plus, 0xF2C14E));
					de.loothub.client.cosmetics.PetLevels.bonus(n.get("n").getAsInt() * 5); // Haustiere bekommen Erfahrung
				}
				case "freeze" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.freeze").getString(),
						Component.translatable("loothub.lh.toast.freeze.sub").getString(), 0x7FB3FF));
				case "freeze_used" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.freeze_used").getString(),
						Component.translatable("loothub.lh.toast.freeze_used.sub", n.get("n").getAsInt()).getString(), 0x7FB3FF));
				case "comm" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.comm").getString(),
						Texts.stat(stat).getString() + " · " + plus, 0x6BD5FF));
				case "ach" -> {
					// Heldentat: epischer Name, erreichter Rang und Fanfare
					int tier = n.get("tier").getAsInt();
					int max = 30;
					try {
						max = s.getAsJsonObject("cat").getAsJsonObject("ach").getAsJsonArray(stat).size();
					} catch (RuntimeException ignored) {
					}
					de.loothub.client.cosmetics.Emotes.cheer(); // Mini-Mes und Haustiere freuen sich mit
					de.loothub.client.friends.Friends.post("ach", Texts.achName(stat).getString(), Texts.rank(tier, max).getString(), tier);
					TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.ach", Texts.achName(stat)).getString(),
							Component.translatable("loothub.lh.toast.ach.sub", Texts.rank(tier, max), plus).getString(),
							Texts.rankColor(tier, max) & 0xFFFFFF, true));
				}
				case "col" -> {
					String c = n.get("c").getAsString();
					int tier = n.get("tier").getAsInt();
					TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.col." + c,
							Collection.name(c, n.get("id").getAsString())).getString(),
							Component.translatable("loothub.lh.toast.ach.sub", Texts.rank(tier, 15), plus).getString(),
							Texts.rankColor(tier, 15) & 0xFFFFFF, tier >= 15));
				}
				case "col_many" -> TOASTS.add(new Toast(
						Component.translatable("loothub.lh.toast.col_many", n.get("n").getAsInt()).getString(), plus, 0xF2C14E));
				case "chain" -> {
					String cid = n.get("id").getAsString();
					int step = n.get("step").getAsInt(), of = n.get("of").getAsInt();
					TOASTS.add(new Toast(Component.translatable(step >= of ? "loothub.lh.toast.chain.done" : "loothub.lh.toast.chain",
							Component.translatable("loothub.lh.chain." + cid), step, of).getString(), plus, 0xB07CFF, step >= of));
				}
				case "secret" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.secret").getString(),
						Component.translatable("loothub.lh.secret." + n.get("id").getAsString()).getString() + "  " + plus, 0xB07CFF, true));
				case "quiz" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.quiz").getString(), plus, 0x55E06A));
				case "guess" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.guess").getString(), plus, 0x55E06A));
				case "gift" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.gift", n.get("from").getAsString()).getString(),
						Shop.name(n.get("id").getAsString()).getString(), 0xFF7FB3, true));
				case "gift_sent" -> TOASTS.add(new Toast(Component.translatable("loothub.lh.toast.gift_sent", n.get("to").getAsString()).getString(),
						Shop.name(n.get("id").getAsString()).getString(), 0xFF7FB3));
				case "unlock" -> {
					// Ruhmestitel oder Sammel-Abzeichen freigeschaltet
					String id = n.get("id").getAsString();
					TOASTS.add(new Toast(Component.translatable(id.startsWith("a_") ? "loothub.lh.toast.unlock.title"
							: "loothub.lh.toast.unlock.badge").getString(), Shop.name(id).getString(), 0xFF8C30, true));
				}
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
			if (mc.player != null && current.epic()) {
				mc.player.playSound(net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.45F, 1.0F); // Fanfare
			} else if (mc.player != null) {
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
