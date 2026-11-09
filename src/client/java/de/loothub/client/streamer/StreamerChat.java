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

	private StreamerChat() {
	}

	public static Status status() {
		return status;
	}

	public static synchronized void ensure(boolean on, String login) {
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

	private static String tag(String tags, String key) {
		for (String part : tags.split(";")) {
			int eq = part.indexOf('=');
			if (eq > 0 && part.substring(0, eq).equals(key)) {
				return part.substring(eq + 1);
			}
		}
		return "";
	}

	private static void handle(String line) {
		String tags = "";
		if (line.startsWith("@")) {
			int sp = line.indexOf(' ');
			if (sp < 0) {
				return;
			}
			tags = line.substring(1, sp);
			line = line.substring(sp + 1);
		}
		int pm = line.indexOf(" PRIVMSG #");
		int un = line.indexOf(" USERNOTICE #");
		int at = pm > 0 ? pm : un;
		if (at <= 0 || !line.startsWith(":")) {
			return;
		}
		int bang = line.indexOf('!');
		String name = tag(tags, "display-name").replace("\\s", " ");
		if (name.isBlank()) {
			name = bang > 1 ? line.substring(1, bang) : "?";
		}
		int textStart = line.indexOf(" :", at);
		String text = textStart > 0 ? line.substring(textStart + 2) : "";
		boolean action = false;
		if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
			text = text.substring(8, text.length() - 1); // /me
			action = true;
		}
		int color = 0xE0E0E0;
		String col = tag(tags, "color");
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
		if (un > 0) {
			// Abo, Raid, Geschenk … – Twitch liefert den fertigen Satz
			String sys = clean(tag(tags, "system-msg").replace("\\s", " "));
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
			msg = prefix().append(Component.literal(clean(name)).withStyle(st -> st.withColor(nameColor)))
					.append(Component.literal(me ? " " : ": ").withStyle(st -> st.withColor(0xAAAAAA)))
					.append(Component.literal(clean(text)).withStyle(st -> st.withColor(me ? nameColor : 0xFFFFFF)));
		}
		MutableComponent shown = msg;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null && thread != null) {
				mc.player.displayClientMessage(shown, false);
			}
		});
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
