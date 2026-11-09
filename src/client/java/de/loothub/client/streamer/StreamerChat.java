package de.loothub.client.streamer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

import javax.net.ssl.SSLSocketFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import de.loothub.Loothub;

/**
 * Liest den Twitch-Chat des eigenen Kanals (anonym, nur lesend) und zeigt jede Nachricht im Minecraft-Chat als
 * „Twitch » Name: Text“. Im eigenen Loothub-Chat lässt sich „TWITCH“ dadurch wie jede andere Quelle einem Tab zuordnen.
 * Es wird nichts gesendet – weder in den Twitch-Chat noch an den Minecraft-Server.
 */
public final class StreamerChat {
	public static final int PURPLE = 0x9146FF;
	private static final String HOST = "irc.chat.twitch.tv";
	private static final int PORT = 6697;

	public enum Status { OFF, CONNECTING, CONNECTED }

	private static volatile Status status = Status.OFF;
	private static volatile String channel = "";
	private static volatile Thread thread;
	private static volatile Socket socket;
	/** Nachrichten im MC-Chat zeigen (sonst nur für Einblendungen mitlesen). */
	private static volatile boolean showInChat;

	private StreamerChat() {
	}

	public static Status status() {
		return status;
	}

	public static synchronized void ensure(boolean on, boolean inChat, String login) {
		showInChat = inChat;
		String ch = login == null ? "" : login.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "");
		if (!on || ch.isEmpty()) {
			stop();
			return;
		}
		if (thread != null && thread.isAlive() && ch.equals(channel)) {
			return;
		}
		stop();
		channel = ch;
		Thread t = new Thread(StreamerChat::run, "Loothub-Twitch-Chat");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	public static synchronized void stop() {
		Thread t = thread;
		thread = null;
		Socket s = socket;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
		}
		if (t != null) {
			t.interrupt();
		}
		status = Status.OFF;
	}

	private static void run() {
		Thread me = Thread.currentThread();
		long backoff = 2000;
		while (thread == me) {
			status = Status.CONNECTING;
			try (Socket s = SSLSocketFactory.getDefault().createSocket(HOST, PORT)) {
				socket = s;
				s.setSoTimeout(6 * 60_000);
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
				BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
				w.write("NICK justinfan" + ThreadLocalRandom.current().nextInt(10000, 99999) + "\r\n");
				w.write("CAP REQ :twitch.tv/commands twitch.tv/tags\r\n");
				w.write("JOIN #" + channel + "\r\n");
				w.flush();
				String line;
				while (thread == me && (line = in.readLine()) != null) {
					if (line.startsWith("PING")) {
						w.write("PONG" + line.substring(4) + "\r\n");
						w.flush();
						continue;
					}
					if (line.contains(" JOIN #" + channel) || line.contains(" ROOMSTATE #" + channel)) {
						status = Status.CONNECTED;
						backoff = 2000;
					}
					handle(line);
				}
			} catch (IOException e) {
				if (thread == me) {
					Loothub.LOGGER.debug("Twitch-Chat getrennt", e);
				}
			} finally {
				socket = null;
			}
			if (thread != me) {
				break;
			}
			status = Status.CONNECTING;
			try {
				Thread.sleep(backoff);
			} catch (InterruptedException e) {
				break;
			}
			backoff = Math.min(backoff * 2, 60_000);
		}
	}

	/** IRC-Tags („a=b;c=d“) als Map, Twitch-Maskierungen aufgelöst. */
	private static java.util.Map<String, String> tags(String raw) {
		java.util.Map<String, String> m = new java.util.HashMap<>();
		for (String part : raw.split(";")) {
			int eq = part.indexOf('=');
			if (eq > 0) {
				m.put(part.substring(0, eq), part.substring(eq + 1).replace("\\s", " ").replace("\\:", ";").replace("\\\\", "\\"));
			}
		}
		return m;
	}

	private static void handle(String line) {
		String rawTags = "";
		if (line.startsWith("@")) {
			int sp = line.indexOf(' ');
			if (sp < 0) {
				return;
			}
			rawTags = line.substring(1, sp);
			line = line.substring(sp + 1);
		}
		int pm = line.indexOf(" PRIVMSG #");
		int un = line.indexOf(" USERNOTICE #");
		int at = pm > 0 ? pm : un;
		if (at <= 0 || !line.startsWith(":")) {
			return;
		}
		java.util.Map<String, String> tags = tags(rawTags);
		int bang = line.indexOf('!');
		String login = tags.getOrDefault("login", bang > 1 ? line.substring(1, bang) : "");
		String name = tags.getOrDefault("display-name", "");
		if (name.isBlank()) {
			name = login.isBlank() ? "?" : login;
		}
		int textStart = line.indexOf(" :", at);
		String text = textStart > 0 ? line.substring(textStart + 2) : "";
		boolean action = false;
		if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
			text = text.substring(8, text.length() - 1); // /me
			action = true;
		}

		// Einblendungen: Abos, Geschenke, Raids, Bits
		if (un > 0) {
			Alerts.fromIrc(tags.getOrDefault("msg-id", ""), clean(name), login, tags, clean(text));
		} else if (!tags.getOrDefault("bits", "").isBlank()) {
			Alerts.fromIrc("cheer", clean(name), login, tags, clean(stripCheers(text)));
		}
		if (!showInChat) {
			return;
		}

		int color = 0xE0E0E0;
		String col = tags.getOrDefault("color", "");
		if (col.startsWith("#") && col.length() == 7) {
			try {
				color = brighten(Integer.parseInt(col.substring(1), 16));
			} catch (NumberFormatException ignored) {
				// Standardfarbe
			}
		}
		final int nameColor = color;
		final boolean me = action;
		MutableComponent msg;
		boolean mention = false;
		if (un > 0) {
			// Abo, Raid, Geschenk … – Twitch liefert den fertigen Satz
			String sys = clean(tags.getOrDefault("system-msg", ""));
			if (sys.isBlank()) {
				return;
			}
			msg = prefix().append(Component.literal(sys).withStyle(st -> st.withColor(0xF2C14E)));
			if (!text.isBlank()) {
				msg.append(Component.literal(" – " + clean(text)).withStyle(st -> st.withColor(0xFFFFFF)));
			}
		} else {
			if (text.isBlank()) {
				return;
			}
			String own = channel;
			String lower = text.toLowerCase(java.util.Locale.ROOT);
			mention = !own.isEmpty() && !login.equals(own) && (lower.contains("@" + own) || lower.matches(".*\\b" + java.util.regex.Pattern.quote(own) + "\\b.*"));
			msg = prefix();
			badges(msg, tags);
			final int textColor = mention ? 0xFFD66B : me ? nameColor : 0xFFFFFF;
			msg.append(Component.literal(clean(name)).withStyle(st -> st.withColor(nameColor)))
					.append(Component.literal(me ? " " : ": ").withStyle(st -> st.withColor(0xAAAAAA)))
					.append(Component.literal(clean(text)).withStyle(st -> st.withColor(textColor).withBold(false)));
		}
		MutableComponent shown = msg;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null && thread != null) {
				mc.player.displayClientMessage(shown, false);
			}
		});
	}

	/** Abzeichen vor dem Namen: Streamer, Moderator, VIP, Abonnent, erste Nachricht. */
	private static void badges(MutableComponent msg, java.util.Map<String, String> tags) {
		String b = "," + tags.getOrDefault("badges", "") + ",";
		if (b.contains(",broadcaster/")) {
			badge(msg, "LIVE", 0xFF4B4B);
		}
		if (b.contains(",moderator/")) {
			badge(msg, "MOD", 0x34C759);
		}
		if (b.contains(",vip/")) {
			badge(msg, "VIP", 0xFF6AD5);
		}
		if (b.contains(",subscriber/") || b.contains(",founder/")) {
			badge(msg, "SUB", 0xB18CFF);
		}
		if ("1".equals(tags.get("first-msg"))) {
			badge(msg, "NEU", 0xF2C14E);
		}
	}

	private static void badge(MutableComponent msg, String text, int color) {
		msg.append(Component.literal("[" + text + "] ").withStyle(st -> st.withColor(color).withBold(true)));
	}

	/** „Cheer100 Text“ → „Text“. */
	private static String stripCheers(String text) {
		return text.replaceAll("(?i)\\b[a-z]+\\d+\\b", "").replaceAll("\\s+", " ").strip();
	}

	private static MutableComponent prefix() {
		return Component.literal("Twitch").withStyle(st -> st.withColor(PURPLE)).append(Component.literal(" » ").withStyle(st -> st.withColor(0x6E6E6E)));
	}

	/** Minecraft-Formatcodes (§) aus Twitch-Text entfernen. */
	private static String clean(String s) {
		return s.replace('§', '&');
	}

	/** Sehr dunkle Twitch-Namensfarben auf dunklem Chat lesbar machen. */
	private static int brighten(int rgb) {
		int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		if (max >= 120) {
			return rgb;
		}
		float f = 120f / Math.max(1, max);
		return Math.min(255, (int) (r * f + 40)) << 16 | Math.min(255, (int) (g * f + 40)) << 8 | Math.min(255, (int) (b * f + 40));
	}
}
