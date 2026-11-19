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

	// ------------------------------------------------------------------ Box im Rahmen-Stil (für alle Einblendungen)

	/** Innenabstand und Größe des Symbol-Rahmens. */
	private static final int PAD = 7, FRAME = 20;
	/** Platz für die Verzierungen außen (oben/unten das Wappen, an den Seiten die Ecken). */
	private static final int OUT_X = 3, OUT_Y = 6;

	/** Verzierung oben in der Mitte; die letzte Zeile liegt auf der Rahmenlinie (unten gespiegelt). */
	private static final String[] CREST = {
			"..........#..........",
			".........#o#.........",
			"....###.#+++#.###....",
			"...#...##+o+##...#...",
			"..#.++...#+#...++.#..",
			".#+...++..#..++...+#.",
			"#####################"};
	/** Ecke oben links; '*' liegt genau auf der Rahmenecke (die anderen Ecken gespiegelt). */
	private static final String[] CORNER = {
			"##.....",
			"#o#....",
			".#+#...",
			"..#*###",
			"...#+..",
			"...#...",
			"...#..."};

	/** Größe einer Einblendung (Breite, Höhe) – auch für die Fang-Einblendung. */
	public static int[] boxSize(Font font, String title, String sub) {
		return boxSize(font, title, sub, false);
	}

	/** Größe mit Item-Symbol (z. B. der fehlende Block bei der Bau-Warnung). */
	public static int[] boxSize(Font font, String title, String sub, boolean item) {
		String[] t = splitIcon(title);
		String s = RedemptionHud.fit(font, sub == null ? "" : sub, 300);
		boolean frame = item || t[0] != null;
		int textW = Math.max(font.width(t[1]), font.width(s));
		int textH = s.isBlank() ? 9 : 9 + 6 + 9;
		int w = Math.max(70, PAD * 2 + (frame ? FRAME + 7 : 0) + textW);
		int h = PAD * 2 + Math.max(frame ? FRAME : 0, textH);
		return new int[]{w + OUT_X * 2, h + OUT_Y * 2};
	}

	public static void drawBox(GuiGraphics g, Font font, String title, String sub, int color, int x, int y, float a) {
		drawBox(g, font, title, sub, color, x, y, a, null);
	}

	/**
	 * Einblendung im Rahmen-Stil: dunkle Tafel mit doppelter Rahmenlinie, verzierten Ecken und Wappen oben und unten,
	 * links ein gerahmtes Symbol (Item oder das Zeichen vor dem Titel), daneben Titel, Trennlinie und Untertitel.
	 * Rahmen, Verzierung und Titel tragen die Farbe des Anlasses (Abo lila, Raid rot, Fang je Seltenheit …).
	 */
	public static void drawBox(GuiGraphics g, Font font, String title, String sub, int color, int x, int y, float a,
			net.minecraft.world.item.ItemStack item) {
		boolean hasItem = item != null && !item.isEmpty();
		int[] size = boxSize(font, title, sub, hasItem);
		int bx = x + OUT_X, by = y + OUT_Y, bw = size[0] - OUT_X * 2, bh = size[1] - OUT_Y * 2;
		String[] t = splitIcon(title);
		String s = RedemptionHud.fit(font, sub == null ? "" : sub, 300);
		int c = color & 0xFFFFFF;
		int light = mix(c, 0xFFFFFF, 0.35F), dark = mix(c, 0x000000, 0.55F);
		int alpha = Math.max(8, (int) (a * 255));

		// Tafel: dunkles Braun mit sanftem Verlauf, oben ein Hauch der Anlass-Farbe
		g.fillGradient(bx, by, bx + bw, by + bh, argb(a * 0.94F, 0x1C130D), argb(a * 0.94F, 0x0B0705));
		g.fillGradient(bx + 1, by + 1, bx + bw - 1, by + bh / 2, argb(a * 0.16F, c), argb(0F, c));
		// doppelte Rahmenlinie: außen in der Farbe, innen dunkler
		rect(g, bx, by, bw, bh, argb(a, c));
		rect(g, bx + 2, by + 2, bw - 4, bh - 4, argb(a * 0.75F, dark));
		// Verzierungen
		int cx = bx + bw / 2 - CREST[0].length() / 2;
		pattern(g, CREST, cx, by - (CREST.length - 1), false, false, a, light, c, 0xFFFFFF);
		pattern(g, CREST, cx, by + bh - 1, false, true, a, light, c, 0xFFFFFF);
		pattern(g, CORNER, bx - 3, by - 3, false, false, a, light, c, 0xFFFFFF);
		pattern(g, CORNER, bx + bw - 4, by - 3, true, false, a, light, c, 0xFFFFFF);
		pattern(g, CORNER, bx - 3, by + bh - 4, false, true, a, light, c, 0xFFFFFF);
		pattern(g, CORNER, bx + bw - 4, by + bh - 4, true, true, a, light, c, 0xFFFFFF);

		int tx = bx + PAD;
		if (hasItem || t[0] != null) {
			// Symbol-Rahmen
			int fx = bx + PAD, fy = by + (bh - FRAME) / 2;
			g.fill(fx, fy, fx + FRAME, fy + FRAME, argb(a * 0.6F, 0x000000));
			rect(g, fx, fy, FRAME, FRAME, argb(a, dark));
			g.fill(fx + 1, fy + 1, fx + FRAME - 1, fy + 2, argb(a * 0.5F, c));
			if (hasItem) {
				g.renderItem(item, fx + 2, fy + 2);
			} else {
				g.pose().pushMatrix();
				float gw = font.width(t[0]) * 1.5F;
				g.pose().translate(fx + (FRAME - gw) / 2F + 0.5F, fy + (FRAME - 12) / 2F + 0.5F);
				g.pose().scale(1.5F, 1.5F);
				g.drawString(font, t[0], 0, 0, argb(a, light), true);
				g.pose().popMatrix();
			}
			tx = fx + FRAME + 7;
		}
		int textH = s.isBlank() ? 8 : 8 + 6 + 9;
		int ty = by + (bh - textH) / 2;
		g.drawString(font, t[1], tx, ty, alpha << 24 | light, true);
		if (!s.isBlank()) {
			// Trennlinie, die nach rechts ausläuft
			int lx1 = bx + bw - PAD;
			int len = Math.max(1, lx1 - tx);
			for (int k = 0; k < 4; k++) {
				int sx = tx + len * k / 4, ex = tx + len * (k + 1) / 4;
				g.fill(sx, ty + 11, ex, ty + 12, argb(a * (0.8F - k * 0.18F), c));
			}
			g.drawString(font, s, tx, ty + 15, alpha << 24 | 0xE6DED6, true);
		}
	}

	/** „★ Name“ → {"★", "Name"}; ohne Zeichen vorn → {null, Titel}. */
	private static String[] splitIcon(String title) {
		if (title != null && title.length() > 2) {
			int cp = title.codePointAt(0);
			int n = Character.charCount(cp);
			if (!Character.isLetterOrDigit(cp) && title.length() > n + 1 && title.charAt(n) == ' ') {
				return new String[]{title.substring(0, n), title.substring(n + 1)};
			}
		}
		return new String[]{null, title == null ? "" : title};
	}

	/** Pixel-Muster zeichnen: '#' hell, '+' Farbe, 'o' Glanzpunkt, '*' hell (Ecke); gespiegelt nach Bedarf. */
	private static void pattern(GuiGraphics g, String[] rows, int x, int y, boolean flipX, boolean flipY, float a, int light,
			int mid, int shine) {
		int h = rows.length;
		for (int r = 0; r < h; r++) {
			String row = rows[r];
			int w = row.length();
			for (int k = 0; k < w; k++) {
				char ch = row.charAt(k);
				int col = switch (ch) {
					case '#', '*' -> light;
					case '+' -> mid;
					case 'o' -> shine;
					default -> -1;
				};
				if (col < 0) {
					continue;
				}
				int px = x + (flipX ? w - 1 - k : k);
				int py = y + (flipY ? h - 1 - r : r);
				g.fill(px, py, px + 1, py + 1, argb(a, col));
			}
		}
	}

	private static void rect(GuiGraphics g, int x, int y, int w, int h, int argb) {
		g.fill(x, y, x + w, y + 1, argb);
		g.fill(x, y + h - 1, x + w, y + h, argb);
		g.fill(x, y + 1, x + 1, y + h - 1, argb);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
	}

	private static int argb(float a, int rgb) {
		int al = Math.max(0, Math.min(255, (int) (a * 255)));
		return al << 24 | (rgb & 0xFFFFFF);
	}

	private static int mix(int c1, int c2, float k) {
		int r = (int) (((c1 >> 16) & 0xFF) * (1 - k) + ((c2 >> 16) & 0xFF) * k);
		int gg = (int) (((c1 >> 8) & 0xFF) * (1 - k) + ((c2 >> 8) & 0xFF) * k);
		int b = (int) ((c1 & 0xFF) * (1 - k) + (c2 & 0xFF) * k);
		return r << 16 | gg << 8 | b;
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
