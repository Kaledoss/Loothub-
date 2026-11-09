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
		int alpha = Math.max(8, (int) (a * 255)) << 24;
		String title = al.type().icon + " " + al.title();
		String sub = RedemptionHud.fit(font, al.sub(), 300);
		int tw = (int) (font.width(title) * 1.5f);
		int w = Math.max(tw, font.width(sub)) + 28;
		int h = al.sub().isBlank() ? 24 : 36;
		int x = (sw - w) / 2;
		int y = 14 + (int) ((1 - a) * -12); // von oben hereinrutschen
		int bg = (int) (a * 0xD0) << 24 | 0x120A0C;
		g.fill(x, y, x + w, y + h, bg);
		g.fill(x, y + h - 2, x + w, y + h, alpha | al.type().color);
		g.fill(x, y, x + 2, y + h - 2, alpha | al.type().color);
		g.pose().pushMatrix();
		g.pose().translate(x + (w - tw) / 2f, y + 5);
		g.pose().scale(1.5f, 1.5f);
		g.drawString(font, title, 0, 0, alpha | al.type().color, true);
		g.pose().popMatrix();
		if (!al.sub().isBlank()) {
			g.drawString(font, sub, x + (w - font.width(sub)) / 2, y + 22, alpha | 0xFFFFFF, true);
		}
	}

	private static void sound(Minecraft mc, Type t) {
		if (!WidgetConfig.get().streamerSound || mc.player == null) {
			return;
		}
		float pitch = switch (t) {
			case FOLLOW -> 1.6F;
			case SUB, GIFT -> 1.2F;
			case RAID, HYPE -> 0.8F;
			case CHEER -> 1.8F;
			case REDEEM -> 1.5F;
			default -> 1.0F;
		};
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), pitch, 0.8F));
		if (t == Type.SUB || t == Type.GIFT || t == Type.RAID || t == Type.HYPE) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), pitch * 1.26F, 0.8F));
		}
	}
}
