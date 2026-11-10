package de.loothub.client.streamer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Ereignis-Einblendungen oben in der Bildschirmmitte: Follow, Abo, Geschenk-Abo, Raid, Bits, Kanalbelohnung,
 * Hype Train, Werbung, Umfrage, Vorhersage. Nacheinander, je ein paar Sekunden, mit Ton. Nur Anzeige.
 */
public final class Alerts {
	public enum Type {
		FOLLOW("follow", 0x55E06A, "♥"),
		SUB("sub", 0xB18CFF, "★"),
		GIFT("gift", 0xFF8AD8, "✚"),
		RAID("raid", 0xFF6B6B, "⚔"),
		CHEER("cheer", 0x6BD5FF, "◆"),
		REDEEM("redeem", 0xF2C14E, "✦"),
		STREAK("streak", 0xFFD166, "⚡"),
		HYPE("hype", 0xFF9B3B, "▲"),
		AD("ad", 0xB9A9AB, "⏸"),
		POLL("poll", 0x7FB3FF, "?"),
		PREDICTION("prediction", 0x4FD1C5, "⚑");

		public final String id;
		final int color;
		final String icon;

		Type(String id, int color, String icon) {
			this.id = id;
			this.color = color;
			this.icon = icon;
		}

		public int color() {
			return color;
		}

		public String icon() {
			return icon;
		}

		public String key() {
			return "loothub.streamer.alert." + id;
		}
	}

	private record Alert(Type type, String title, String sub) {
	}

	private static final long SHOW_MS = 5500;
	private static final long FADE_MS = 350;
	private static final Deque<Alert> QUEUE = new ArrayDeque<>();
	private static Alert current;
	private static long since;

	/** Laufende Sammel-Geschenke: Schenker → noch zu verschluckende Einzel-Meldungen. */
	private static final Map<String, Integer> MASS_GIFTS = new HashMap<>();

	private Alerts() {
	}

	public static boolean enabled(Type t) {
		WidgetConfig c = WidgetConfig.get();
		return c.streamerMode && c.streamerAlerts && (c.streamerAlertsOff == null || !c.streamerAlertsOff.contains(t.id));
	}

	public static void setEnabled(Type t, boolean on) {
		WidgetConfig c = WidgetConfig.get();
		if (c.streamerAlertsOff == null) {
			c.streamerAlertsOff = new java.util.ArrayList<>();
		}
		c.streamerAlertsOff.remove(t.id);
		if (!on) {
			c.streamerAlertsOff.add(t.id);
		}
		c.save();
	}

	/** Neue Einblendung (Spiel-Thread). */
	public static void post(Type t, String title, String sub) {
		if (!enabled(t)) {
			return;
		}
		if (QUEUE.size() < 30) {
			QUEUE.add(new Alert(t, title, sub == null ? "" : sub));
		}
	}

	/** Beispiel zum Ausprobieren (Knopf „Test“), auch wenn die Art ausgeschaltet ist. */
	public static void test(Type t) {
		String name = TwitchAuth.displayName().isBlank() ? "Zuschauer" : "Zuschauer123";
		String sub = switch (t) {
			case FOLLOW -> "folgt jetzt";
			case SUB -> "abonniert seit 6 Monaten (Stufe 1)";
			case GIFT -> "verschenkt 5 Abos";
			case RAID -> "raidet mit 42 Zuschauern";
			case CHEER -> "100 Bits – Gute Arbeit!";
			case REDEEM -> "Hydrieren!";
			case STREAK -> "12 Streams in Folge";
			case HYPE -> "Level 2";
			case AD -> "Werbung läuft 90 Sekunden";
			case POLL -> "Welche Farm als Nächstes?";
			case PREDICTION -> "Schafft er den Boss?";
		};
		QUEUE.add(new Alert(t, t == Type.AD || t == Type.POLL || t == Type.PREDICTION || t == Type.HYPE
				? Component.translatable(t.key()).getString() : name, sub));
	}

	// ------------------------------------------------------------------ Quellen

	/** Aus Twitch EventSub (Hintergrund-Thread). */
	static void fromEventSub(String type, JsonObject ev) {
		String title;
		String sub;
		Type t;
		switch (type) {
			case "channel.follow" -> {
				t = Type.FOLLOW;
				title = name(ev, "user");
				sub = "folgt jetzt";
			}
			case "channel.hype_train.begin" -> {
				t = Type.HYPE;
				title = "Hype Train";
				sub = "ist gestartet!";
			}
			case "channel.hype_train.end" -> {
				t = Type.HYPE;
				title = "Hype Train";
				sub = "beendet – Level " + Redemptions.str(ev, "level");
			}
			case "channel.ad_break.begin" -> {
				t = Type.AD;
				title = "Werbung";
				sub = "läuft " + Redemptions.str(ev, "duration_seconds") + " Sekunden";
			}
			case "channel.poll.begin" -> {
				t = Type.POLL;
				title = "Umfrage gestartet";
				sub = Redemptions.str(ev, "title");
			}
			case "channel.poll.end" -> {
				if (!Redemptions.str(ev, "status").equalsIgnoreCase("completed")) {
					return;
				}
				t = Type.POLL;
				title = "Umfrage beendet: " + Redemptions.str(ev, "title");
				sub = "Gewonnen: " + topChoice(ev);
			}
			case "channel.prediction.begin" -> {
				t = Type.PREDICTION;
				title = "Vorhersage gestartet";
				sub = Redemptions.str(ev, "title");
			}
			case "channel.prediction.end" -> {
				if (!Redemptions.str(ev, "status").equalsIgnoreCase("resolved")) {
					return;
				}
				t = Type.PREDICTION;
				title = "Vorhersage: " + Redemptions.str(ev, "title");
				sub = "Ergebnis: " + winner(ev);
			}
			default -> {
				return;
			}
		}
		Minecraft.getInstance().execute(() -> post(t, title, sub));
	}

	private static String name(JsonObject ev, String prefix) {
		String n = Redemptions.str(ev, prefix + "_name");
		return n.isBlank() ? Redemptions.str(ev, prefix + "_login") : n;
	}

	private static String topChoice(JsonObject ev) {
		String best = "?";
		int votes = -1;
		if (ev.has("choices") && ev.get("choices").isJsonArray()) {
			for (JsonElement el : ev.getAsJsonArray("choices")) {
				JsonObject c = el.getAsJsonObject();
				int v = c.has("votes") && !c.get("votes").isJsonNull() ? c.get("votes").getAsInt() : 0;
				if (v > votes) {
					votes = v;
					best = Redemptions.str(c, "title") + " (" + v + ")";
				}
			}
		}
		return best;
	}

	private static String winner(JsonObject ev) {
		String id = Redemptions.str(ev, "winning_outcome_id");
		if (ev.has("outcomes") && ev.get("outcomes").isJsonArray()) {
			JsonArray arr = ev.getAsJsonArray("outcomes");
			for (JsonElement el : arr) {
				JsonObject c = el.getAsJsonObject();
				if (Redemptions.str(c, "id").equals(id)) {
					return Redemptions.str(c, "title");
				}
			}
		}
		return "?";
	}

	/** Aus dem Twitch-Chat: Abos, Geschenke, Raids (USERNOTICE) und Bits (Hintergrund-Thread). */
	static void fromIrc(String msgId, String name, String login, Map<String, String> tags, String text) {
		Minecraft mc = Minecraft.getInstance();
		switch (msgId) {
			case "sub", "resub" -> {
				String plan = plan(tags.getOrDefault("msg-param-sub-plan", ""));
				String months = tags.getOrDefault("msg-param-cumulative-months", "");
				boolean resub = msgId.equals("resub") && !months.isBlank() && !months.equals("1");
				String what = resub ? "Abo: " + months + " Monate (" + plan + ")" : "Neues Abo (" + plan + ")";
				String sub = (resub ? "abonniert seit " + months + " Monaten (" + plan + ")" : "hat abonniert (" + plan + ")")
						+ (text.isBlank() ? "" : " – " + text);
				String id = tags.getOrDefault("id", "");
				mc.execute(() -> {
					post(Type.SUB, name, sub);
					Redemptions.addEvent("sub", id, name, what, text);
				});
			}
			case "submysterygift" -> {
				String count = tags.getOrDefault("msg-param-mass-gift-count", "1");
				try {
					int n = Integer.parseInt(count);
					mc.execute(() -> MASS_GIFTS.merge(login, n, Integer::sum));
				} catch (NumberFormatException ignored) {
					// keine Zahl – Einzel-Meldungen kommen dann einzeln
				}
				String what = "Verschenkt " + count + (count.equals("1") ? " Abo" : " Abos");
				String id = tags.getOrDefault("id", "");
				mc.execute(() -> {
					post(Type.GIFT, name, "verschenkt " + count + (count.equals("1") ? " Abo" : " Abos"));
					Redemptions.addEvent("sub", id, name, what, "");
				});
			}
			case "subgift" -> {
				String to = tags.getOrDefault("msg-param-recipient-display-name", "?");
				String id = tags.getOrDefault("id", "");
				mc.execute(() -> {
					Integer left = MASS_GIFTS.get(login);
					if (left != null && left > 0) {
						// Teil eines Sammel-Geschenks – schon als Ganzes gezeigt
						if (left == 1) {
							MASS_GIFTS.remove(login);
						} else {
							MASS_GIFTS.put(login, left - 1);
						}
						return;
					}
					post(Type.GIFT, name, "schenkt " + to + " ein Abo");
					Redemptions.addEvent("sub", id, name, "Schenkt " + to + " ein Abo", "");
				});
			}
			case "viewermilestone" -> {
				if (!"watch-streak".equals(tags.getOrDefault("msg-param-category", ""))) {
					return;
				}
				String streams = tags.getOrDefault("msg-param-value", "?");
				String id = tags.getOrDefault("id", "");
				mc.execute(() -> Redemptions.addStreak(id, name, streams, text));
			}
			case "raid" -> {
				String who = tags.getOrDefault("msg-param-displayName", name);
				String viewers = tags.getOrDefault("msg-param-viewerCount", "?");
				mc.execute(() -> post(Type.RAID, who, "raidet mit " + viewers + " Zuschauern"));
			}
			case "cheer" -> {
				String bits = tags.getOrDefault("bits", "?");
				String sub = bits + " Bits" + (text.isBlank() ? "" : " – " + text);
				mc.execute(() -> post(Type.CHEER, name, sub));
			}
			default -> {
				// Ankündigungen u. Ä. stehen nur im Chat
			}
		}
	}

	private static String plan(String p) {
		return switch (p) {
			case "Prime" -> "Prime";
			case "2000" -> "Stufe 2";
			case "3000" -> "Stufe 3";
			default -> "Stufe 1";
		};
	}

	// ------------------------------------------------------------------ Anzeige

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (current != null && now - since > SHOW_MS) {
			current = null;
		}
		if (current == null && !QUEUE.isEmpty()) {
			current = QUEUE.poll();
			since = now;
			sound(mc, current.type());
		}
		if (current == null || mc.options.hideGui) {
			return;
		}
		long age = now - since;
		float a = age < FADE_MS ? age / (float) FADE_MS : age > SHOW_MS - FADE_MS ? (SHOW_MS - age) / (float) FADE_MS : 1f;
		a = Mth.clamp(a, 0f, 1f);
		draw(g, mc.font, current, g.guiWidth(), a);
	}

	private static void draw(GuiGraphics g, Font font, Alert al, int sw, float a) {
		String title = al.type().icon + " " + al.title();
		int[] size = boxSize(font, title, al.sub());
		int[] p = pos(sw, Minecraft.getInstance().getWindow().getGuiScaledHeight(), size[0], size[1]);
		int x = p[0];
		int y = p[1] + (int) ((1 - a) * -12); // von oben hereinrutschen
		drawBox(g, font, title, al.sub(), al.type().color, x, y, a);
	}

	/** Lage aus der Einstellung (Standard: oben in der Mitte). */
	public static int[] pos(int sw, int sh, int w, int h) {
		WidgetConfig c = WidgetConfig.get();
		return new int[]{(int) Math.round(c.streamAlertX * Math.max(0, sw - w)), (int) Math.round(c.streamAlertY * Math.max(0, sh - h))};
	}

	/** Größe der Beispiel-Einblendung (für „HUD-Positionen“). */
	public static int[] sampleSize(Font font) {
		return boxSize(font, "♥ Zuschauer123", "folgt jetzt");
	}

	/** Größe einer Einblendung (Breite, Höhe) – auch für die Fang-Einblendung. */
	public static int[] boxSize(Font font, String title, String sub) {
		String s = RedemptionHud.fit(font, sub, 300);
		int tw = (int) (font.width(title) * 1.5f);
		return new int[]{Math.max(tw, font.width(s)) + 28, sub == null || sub.isBlank() ? 24 : 36};
	}

	/** Einblendung im Twitch-Stil zeichnen: dunkle Box, farbige Kante, großer Titel, Untertitel. */
	public static void drawBox(GuiGraphics g, Font font, String title, String sub, int color, int x, int y, float a) {
		int alpha = Math.max(8, (int) (a * 255)) << 24;
		int[] size = boxSize(font, title, sub);
		int w = size[0], h = size[1];
		String s = RedemptionHud.fit(font, sub == null ? "" : sub, 300);
		int tw = (int) (font.width(title) * 1.5f);
		int bg = (int) (a * 0xD0) << 24 | 0x120A0C;
		g.fill(x, y, x + w, y + h, bg);
		g.fill(x, y + h - 2, x + w, y + h, alpha | (color & 0xFFFFFF));
		g.fill(x, y, x + 2, y + h - 2, alpha | (color & 0xFFFFFF));
		g.pose().pushMatrix();
		g.pose().translate(x + (w - tw) / 2f, y + 5);
		g.pose().scale(1.5f, 1.5f);
		g.drawString(font, title, 0, 0, alpha | (color & 0xFFFFFF), true);
		g.pose().popMatrix();
		if (!s.isBlank()) {
			g.drawString(font, s, x + (w - font.width(s)) / 2, y + 22, alpha | 0xFFFFFF, true);
		}
	}


	private static void sound(Minecraft mc, Type t) {
		if (!WidgetConfig.get().streamerSound || mc.player == null) {
			return;
		}
		de.loothub.client.Sounds.play(de.loothub.client.Sounds.Group.STREAM, sounds(t));
	}

	/** Eigene Klangfolge je Ereignis. */
	static java.util.List<de.loothub.client.Sounds.Note> sounds(Type t) {
		java.util.List<de.loothub.client.Sounds.Note> l = new java.util.ArrayList<>();
		switch (t) {
			case FOLLOW -> {
				l.add(de.loothub.client.Sounds.n(0, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 1.0F));
				l.addAll(de.loothub.client.Sounds.arpeggio(SoundEvents.NOTE_BLOCK_CHIME, 1.0F, 0.7F, 3));
			}
			case SUB -> {
				l.add(de.loothub.client.Sounds.n(0, SoundEvents.PLAYER_LEVELUP, 1.0F, 0.6F));
				l.addAll(de.loothub.client.Sounds.arpeggio(SoundEvents.NOTE_BLOCK_BELL, 0.84F, 0.8F, 3));
			}
			case GIFT -> {
				l.add(de.loothub.client.Sounds.n(0, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.5F));
				l.add(de.loothub.client.Sounds.n(6, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5F, 1.0F));
			}
			case RAID -> {
				l.add(de.loothub.client.Sounds.n(0, SoundEvents.RAID_HORN, 1.0F, 0.6F));
				for (int i = 0; i < 4; i++) {
					l.add(de.loothub.client.Sounds.n(10 + i * 4, SoundEvents.NOTE_BLOCK_BASEDRUM, 0.8F, 0.9F));
				}
			}
			case CHEER -> {
				for (int i = 0; i < 4; i++) {
					l.add(de.loothub.client.Sounds.n(i * 2, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9F + i * 0.25F, 0.6F));
				}
				l.add(de.loothub.client.Sounds.n(8, SoundEvents.AMETHYST_BLOCK_CHIME, 1.8F, 1.0F));
			}
			case REDEEM -> l.addAll(de.loothub.client.Sounds.arpeggio(SoundEvents.NOTE_BLOCK_CHIME, 0.9F, 0.8F, 2));
			case STREAK -> {
				l.addAll(de.loothub.client.Sounds.arpeggio(SoundEvents.NOTE_BLOCK_FLUTE, 0.8F, 0.8F, 3));
				l.add(de.loothub.client.Sounds.n(12, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F, 1.0F));
			}
			case HYPE -> {
				l.add(de.loothub.client.Sounds.n(0, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.0F, 0.8F));
				l.add(de.loothub.client.Sounds.n(14, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0F, 0.8F));
				l.add(de.loothub.client.Sounds.n(20, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0F, 0.8F));
			}
			case AD -> {
				l.add(de.loothub.client.Sounds.n(0, SoundEvents.NOTE_BLOCK_BASS, 1.0F, 0.8F));
				l.add(de.loothub.client.Sounds.n(5, SoundEvents.NOTE_BLOCK_BASS, 0.75F, 0.8F));
			}
			case POLL, PREDICTION -> {
				l.add(de.loothub.client.Sounds.n(0, SoundEvents.NOTE_BLOCK_COW_BELL, 1.0F, 0.8F));
				l.add(de.loothub.client.Sounds.n(4, SoundEvents.NOTE_BLOCK_COW_BELL, 1.33F, 0.8F));
			}
		}
		return l;
	}

}
