package de.loothub.client.music;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Liest über den „API Server“ der YouTube-Music-Desktop-App (th-ch, Port 26538), welcher Titel läuft.
 * Nur lesend. Ist in der App eine Freigabe nötig, fragt die Mod einmal an; der Zugangsschlüssel liegt
 * danach in {@code config/loothub-ytmusic-token.txt}.
 */
public final class YtMusic {
	public enum Status { OFF, NOT_RUNNING, WAITING_FOR_APPROVAL, DENIED, OK }

	public record Song(String title, String artist, boolean paused, double elapsed, double duration, long at) {
		/** Laufende Position, zwischen den Abfragen weitergezählt. */
		public double position() {
			if (paused) {
				return elapsed;
			}
			return Math.min(duration, elapsed + (System.currentTimeMillis() - at) / 1000.0);
		}
	}

	private static final String CLIENT_ID = "loothub";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	private static volatile Status status = Status.OFF;
	private static volatile Song song;
	private static volatile Thread thread;

	private YtMusic() {
	}

	public static Status status() {
		return status;
	}

	/** Aktueller Titel oder null (nichts läuft / App nicht erreichbar). */
	public static Song song() {
		return status == Status.OK ? song : null;
	}

	private static Path tokenFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-ytmusic-token.txt");
	}

	/** Startet bzw. stoppt die Abfrage passend zur Einstellung. */
	public static synchronized void ensure(boolean enabled) {
		if (!enabled) {
			Thread t = thread;
			thread = null;
			if (t != null) {
				t.interrupt();
			}
			status = Status.OFF;
			song = null;
			return;
		}
		if (thread != null && thread.isAlive()) {
			return;
		}
		Thread t = new Thread(YtMusic::run, "Loothub-YTMusic");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	private static String base() {
		return "http://127.0.0.1:" + WidgetConfig.get().musicPort;
	}

	private static void run() {
		Thread me = Thread.currentThread();
		while (thread == me) {
			long wait = 2000;
			try {
				poll();
			} catch (IOException e) {
				status = Status.NOT_RUNNING;
				song = null;
				wait = 5000;
			} catch (InterruptedException e) {
				break;
			} catch (RuntimeException e) {
				Loothub.LOGGER.debug("YouTube Music: unerwartete Antwort", e);
				wait = 5000;
			}
			try {
				Thread.sleep(wait);
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	private static void poll() throws IOException, InterruptedException {
		String token = readToken();
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + "/api/v1/song"))
				.timeout(Duration.ofSeconds(3)).GET();
		if (!token.isEmpty()) {
			req.header("Authorization", "Bearer " + token);
		}
		HttpResponse<String> r = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (r.statusCode() == 401) {
			authorize();
			return;
		}
		if (r.statusCode() == 204 || r.body() == null || r.body().isBlank()) {
			status = Status.OK;
			song = null; // nichts geladen
			return;
		}
		if (r.statusCode() != 200) {
			status = Status.NOT_RUNNING;
			song = null;
			return;
		}
		JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
		song = new Song(str(o, "title"), str(o, "artist"), bool(o, "isPaused"), num(o, "elapsedSeconds"),
				num(o, "songDuration"), System.currentTimeMillis());
		status = Status.OK;
	}

	/** Einmalige Freigabe: die App zeigt dazu ein Fenster, das man bestätigen muss. */
	private static void authorize() throws IOException, InterruptedException {
		status = Status.WAITING_FOR_APPROVAL;
		song = null;
		HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/auth/" + CLIENT_ID))
				.timeout(Duration.ofSeconds(120)).POST(HttpRequest.BodyPublishers.noBody()).build();
		HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (r.statusCode() == 200) {
			String token = JsonParser.parseString(r.body()).getAsJsonObject().get("accessToken").getAsString();
			Files.writeString(tokenFile(), token, StandardCharsets.UTF_8);
			status = Status.OK;
		} else {
			status = Status.DENIED;
			Thread.sleep(30_000); // nicht sofort erneut nachfragen
		}
	}

	private static String readToken() {
		try {
			Path p = tokenFile();
			return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8).strip() : "";
		} catch (IOException e) {
			return "";
		}
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private static boolean bool(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && !e.isJsonNull() && e.getAsBoolean();
	}

	private static double num(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
	}
}
