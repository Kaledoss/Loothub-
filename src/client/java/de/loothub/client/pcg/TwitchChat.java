package de.loothub.client.pcg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import javax.net.ssl.SSLSocketFactory;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Verbindung zum Twitch-Chat (IRC über TLS). Ohne Zugangsschlüssel nur lesend (anonym),
 * mit Schlüssel aus {@code config/loothub-twitch-token.txt} kann die Mod auf Klick auch schreiben.
 * Geschrieben wird ausschließlich, wenn der Spieler einen Knopf drückt – nie automatisch.
 */
public final class TwitchChat {
	public enum Status { OFF, CONNECTING, READ_ONLY, CONNECTED, ERROR, NO_CHANNEL }

	private static final String HOST = "irc.chat.twitch.tv";
	private static final int PORT = 6697;

	private static volatile Status status = Status.OFF;
	private static volatile String channel = "";
	private static volatile String user = "";
	private static volatile BufferedWriter out;
	private static volatile Socket socket;
	private static volatile Thread thread;
	private static volatile boolean canWrite;
	private static volatile long lastSend;

	private TwitchChat() {
	}

	/** Seit wann die Verbindung steht, ohne dass Twitch das Betreten des Kanals bestätigt hat (0 = nicht offen). */
	private static volatile long waitingForJoinSince;

	public static Status status() {
		long since = waitingForJoinSince;
		if (status == Status.CONNECTING && since > 0 && System.currentTimeMillis() - since > 15_000) {
			return Status.NO_CHANNEL; // Kanal gibt es nicht (Tippfehler, Anzeigename statt Kanalname …)
		}
		return status;
	}

	/** Kanal, mit dem gerade verbunden wird bzw. ist. */
	public static String channel() {
		return channel;
	}

	/** Kanalnamen aus allem machen, was man eintippen könnte: Link, @Name, #Name, Großbuchstaben, Leerzeichen. */
	public static String normalizeChannel(String input) {
		if (input == null) {
			return "";
		}
		String c = input.strip().toLowerCase(Locale.ROOT);
		int slash = c.indexOf("twitch.tv/");
		if (slash >= 0) {
			c = c.substring(slash + "twitch.tv/".length());
		}
		int end = c.indexOf('/');
		if (end >= 0) {
			c = c.substring(0, end);
		}
		int q = c.indexOf('?');
		if (q >= 0) {
			c = c.substring(0, q);
		}
		return c.replaceAll("[^a-z0-9_]", "");
	}

	public static boolean canWrite() {
		return canWrite && status == Status.CONNECTED;
	}

	public static Path tokenFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-twitch-token.txt");
	}

	/** Startet bzw. wechselt die Verbindung, wenn sich Kanal oder Nutzer geändert haben. */
	public static synchronized void ensure(boolean enabled, String wantChannel, String wantUser) {
		String ch = normalizeChannel(wantChannel);
		String us = wantUser == null ? "" : wantUser.strip().toLowerCase(Locale.ROOT);
		if (!enabled || ch.isEmpty()) {
			stop();
			return;
		}
		if (thread != null && thread.isAlive() && ch.equals(channel) && us.equals(user)) {
			return;
		}
		stop();
		channel = ch;
		user = us;
		Thread t = new Thread(TwitchChat::run, "Loothub-Twitch");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	/** Verbindung neu aufbauen, z. B. nachdem die Token-Datei geändert wurde. */
	public static synchronized void reconnect() {
		String ch = channel;
		String us = user;
		stop();
		ensure(true, ch, us);
	}

	public static synchronized void stop() {
		Thread t = thread;
		thread = null;
		closeSocket();
		if (t != null) {
			t.interrupt();
		}
		status = Status.OFF;
		canWrite = false;
		waitingForJoinSince = 0;
	}

	/** Grund, warum die letzte Nachricht nicht gesendet wurde (für die Anzeige). */
	private static volatile String lastError = "";
	/** Zeitpunkt der letzten gesendeten Nachricht – Bot-Antworten danach gelten als Antwort darauf. */
	private static volatile long lastSentAt;

	public static String lastError() {
		return lastError;
	}

	public static long lastSentAt() {
		return lastSentAt;
	}

	/** Schickt eine Chat-Nachricht in den Kanal. Nur auf Knopfdruck aufrufen. */
	public static boolean send(String message) {
		BufferedWriter w = out;
		if (status == Status.ERROR) {
			lastError = "Token ungültig – neuen Token eintragen";
			return false;
		}
		if (status == Status.READ_ONLY) {
			lastError = "Nur lesen – Twitch-Name oder Token fehlt";
			return false;
		}
		if (!canWrite() || w == null) {
			lastError = status == Status.CONNECTING ? "Noch nicht mit Twitch verbunden" : "Twitch-Verbindung ist aus";
			return false;
		}
		long now = System.currentTimeMillis();
		if (now - lastSend < 1500) {
			lastError = "Zu schnell – kurz warten";
			return false;
		}
		lastSend = now;
		try {
			synchronized (TwitchChat.class) {
				w.write("PRIVMSG #" + channel + " :" + message + "\r\n");
				w.flush();
			}
			lastSentAt = now;
			lastError = "";
			return true;
		} catch (IOException e) {
			Loothub.LOGGER.warn("Twitch-Nachricht konnte nicht gesendet werden", e);
			lastError = "Verbindung unterbrochen";
			return false;
		}
	}

	private static String readToken() {
		try {
			Path p = tokenFile();
			if (!Files.exists(p)) {
				Files.writeString(p, "", StandardCharsets.UTF_8);
				return "";
			}
			String t = Files.readString(p, StandardCharsets.UTF_8).strip();
			if (t.isEmpty()) {
				return "";
			}
			return t.startsWith("oauth:") ? t : "oauth:" + t;
		} catch (IOException e) {
			return "";
		}
	}

	private static void run() {
		Thread me = Thread.currentThread();
		long backoff = 2000;
		while (thread == me) {
			status = Status.CONNECTING;
			String token = readToken();
			boolean login = !token.isEmpty() && !user.isEmpty();
			try (Socket s = SSLSocketFactory.getDefault().createSocket(HOST, PORT)) {
				socket = s;
				s.setSoTimeout(6 * 60_000);
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
				BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
				if (login) {
					w.write("PASS " + token + "\r\n");
					w.write("NICK " + user + "\r\n");
				} else {
					w.write("NICK justinfan" + ThreadLocalRandom.current().nextInt(10000, 99999) + "\r\n");
				}
				w.write("JOIN #" + channel + "\r\n");
				w.flush();
				out = w;
				waitingForJoinSince = System.currentTimeMillis();
				String line;
				while (thread == me && (line = in.readLine()) != null) {
					if (line.startsWith("PING")) {
						synchronized (TwitchChat.class) {
							w.write("PONG" + line.substring(4) + "\r\n");
							w.flush();
						}
						continue;
					}
					if (line.contains(" 001 ")) {
						backoff = 2000;
					}
					if (line.contains(" JOIN #" + channel) || line.contains(" ROOMSTATE #" + channel)) {
						waitingForJoinSince = 0;
						canWrite = login;
						status = login ? Status.CONNECTED : Status.READ_ONLY;
					}
					if (line.contains("Login authentication failed") || line.contains("Improperly formatted auth")) {
						Loothub.LOGGER.warn("Twitch-Anmeldung fehlgeschlagen – Token prüfen");
						status = Status.ERROR;
						canWrite = false;
						break;
					}
					int pm = line.indexOf(" PRIVMSG #");
					if (pm > 0 && line.startsWith(":")) {
						String sender = line.substring(1, line.indexOf('!') > 0 ? line.indexOf('!') : 1);
						int textStart = line.indexOf(" :", pm);
						if (textStart > 0) {
							PcgState.onChat(sender, line.substring(textStart + 2));
						}
					}
				}
			} catch (IOException e) {
				if (thread == me) {
					Loothub.LOGGER.debug("Twitch-Verbindung getrennt", e);
				}
			} finally {
				waitingForJoinSince = 0;
				out = null;
				canWrite = false;
				socket = null;
			}
			if (thread != me) {
				break;
			}
			if (status != Status.ERROR) {
				status = Status.CONNECTING;
			}
			try {
				Thread.sleep(status == Status.ERROR ? 60_000 : backoff);
			} catch (InterruptedException e) {
				break;
			}
			backoff = Math.min(backoff * 2, 60_000);
		}
	}

	private static void closeSocket() {
		Socket s = socket;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// egal
			}
		}
	}
}
