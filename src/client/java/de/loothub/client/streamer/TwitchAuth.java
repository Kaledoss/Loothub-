package de.loothub.client.streamer;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Anmeldung mit dem eigenen Twitch-Konto über den „Device Code Flow“: Die Mod zeigt einen Code, man bestätigt ihn
 * auf twitch.tv/activate – Passwort und Zugangsschlüssel gibt man nirgends ein. Die Schlüssel landen nur lokal in
 * {@code config/loothub-twitch-account.json} (getrennt von loothub.json, damit man die Einstellungen teilen kann).
 * Es wird nur das Recht „Kanalbelohnungen lesen“ angefragt – die Mod kann nichts im Kanal ändern oder schreiben.
 */
public final class TwitchAuth {
	/**
	 * Client-ID der Loothub-Anwendung im Twitch-Entwicklerportal (Typ „Öffentlich“). Die ID ist kein Geheimnis –
	 * sie steht bei jeder Twitch-App offen im Programm.
	 */
	public static final String CLIENT_ID = "";
	static final String SCOPES = "channel:read:redemptions";

	public enum Status { NOT_CONNECTED, NO_CLIENT_ID, STARTING, WAITING, CONNECTED, ERROR }

	/** Gespeichertes Konto. */
	static final class Account {
		String access = "";
		String refresh = "";
		String login = "";
		String userId = "";
		String displayName = "";
		long expiresAt;
	}

	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final Gson GSON = new Gson();

	private static volatile Account account;
	private static volatile Status status = Status.NOT_CONNECTED;
	private static volatile String userCode = "";
	private static volatile String verifyUri = "";
	private static volatile String error = "";
	private static volatile Thread loginThread;
	private static volatile boolean loaded;
	private static final Object LOAD = new Object();

	private TwitchAuth() {
	}

	public static String clientId() {
		return CLIENT_ID;
	}

	public static Status status() {
		ensureLoaded();
		if (clientId().isBlank() && account == null) {
			return Status.NO_CLIENT_ID;
		}
		return status;
	}

	public static boolean connected() {
		return status() == Status.CONNECTED;
	}

	/** Twitch-Name (Kleinbuchstaben) des verbundenen Kontos, sonst "". */
	public static String login() {
		Account a = account;
		return a == null ? "" : a.login;
	}

	public static String displayName() {
		Account a = account;
		return a == null ? "" : a.displayName.isBlank() ? a.login : a.displayName;
	}

	static String userId() {
		Account a = account;
		return a == null ? "" : a.userId;
	}

	/** Code, den man auf twitch.tv/activate eingibt (während der Anmeldung). */
	public static String userCode() {
		return userCode;
	}

	public static String verifyUri() {
		return verifyUri.isBlank() ? "https://www.twitch.tv/activate" : verifyUri;
	}

	public static String error() {
		return error;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-twitch-account.json");
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		synchronized (LOAD) {
			if (loaded) {
				return;
			}
			loaded = true;
			load();
		}
	}

	private static void load() {
		Path p = file();
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			Account a = GSON.fromJson(r, Account.class);
			if (a != null && a.access != null && !a.access.isBlank()) {
				account = a;
				status = Status.CONNECTED;
			}
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.warn("Twitch-Konto konnte nicht gelesen werden", e);
		}
	}

	private static void save(Account a) {
		de.loothub.client.AsyncSave.write(file(), GSON.toJson(a));
	}

	// ------------------------------------------------------------------ Anmelden

	/** Anmeldung starten: Code holen, Browser öffnen, im Hintergrund auf die Bestätigung warten. */
	public static synchronized void startLogin() {
		ensureLoaded();
		if (clientId().isBlank()) {
			status = Status.NO_CLIENT_ID;
			return;
		}
		cancelLogin();
		status = Status.STARTING;
		error = "";
		userCode = "";
		Thread t = new Thread(TwitchAuth::loginFlow, "Loothub-Twitch-Login");
		t.setDaemon(true);
		loginThread = t;
		t.start();
	}

	public static synchronized void cancelLogin() {
		Thread t = loginThread;
		loginThread = null;
		if (t != null) {
			t.interrupt();
		}
		if (status == Status.STARTING || status == Status.WAITING) {
			status = account != null ? Status.CONNECTED : Status.NOT_CONNECTED;
		}
		userCode = "";
	}

	private static void loginFlow() {
		Thread me = Thread.currentThread();
		try {
			JsonObject dev = post("https://id.twitch.tv/oauth2/device", form("client_id", clientId(), "scopes", SCOPES));
			if (dev == null || !dev.has("device_code")) {
				fail("Twitch hat keinen Code geschickt");
				return;
			}
			String deviceCode = dev.get("device_code").getAsString();
			userCode = dev.get("user_code").getAsString();
			verifyUri = dev.has("verification_uri") ? dev.get("verification_uri").getAsString() : "";
			long interval = dev.has("interval") ? Math.max(1, dev.get("interval").getAsLong()) : 5;
			long until = System.currentTimeMillis() + (dev.has("expires_in") ? dev.get("expires_in").getAsLong() : 1800) * 1000L;
			status = Status.WAITING;
			Browser.open(verifyUri());

			while (loginThread == me) {
				Thread.sleep(interval * 1000L);
				if (System.currentTimeMillis() > until) {
					fail("Code abgelaufen – bitte neu verbinden");
					return;
				}
				HttpResponse<String> r = postRaw("https://id.twitch.tv/oauth2/token", form("client_id", clientId(),
						"scopes", SCOPES, "device_code", deviceCode, "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
				JsonObject o = parse(r.body());
				if (r.statusCode() == 200 && o != null && o.has("access_token")) {
					Account a = new Account();
					a.access = o.get("access_token").getAsString();
					a.refresh = o.has("refresh_token") ? o.get("refresh_token").getAsString() : "";
					a.expiresAt = System.currentTimeMillis() + (o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600) * 1000L;
					if (!fillUser(a)) {
						fail("Konto konnte nicht gelesen werden");
						return;
					}
					synchronized (TwitchAuth.class) {
						if (loginThread != me) {
							return;
						}
						account = a;
						save(a);
						userCode = "";
						status = Status.CONNECTED;
						loginThread = null;
					}
					Loothub.LOGGER.info("Twitch verbunden als {}", a.login);
					Streamer.applyLater();
					return;
				}
				String msg = o != null && o.has("message") ? o.get("message").getAsString() : "";
				if (msg.contains("authorization_pending")) {
					continue;
				}
				if (msg.contains("slow_down")) {
					interval += 5;
					continue;
				}
				fail(msg.contains("denied") ? "Anmeldung auf Twitch abgelehnt" : "Code abgelaufen – bitte neu verbinden");
				return;
			}
		} catch (InterruptedException e) {
			// abgebrochen
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.warn("Twitch-Anmeldung fehlgeschlagen", e);
			if (loginThread == me) {
				fail("Twitch nicht erreichbar");
			}
		}
	}

	private static void fail(String why) {
		synchronized (TwitchAuth.class) {
			error = why;
			userCode = "";
			status = account != null ? Status.CONNECTED : Status.ERROR;
			loginThread = null;
		}
	}

	/** Name und Nutzer-ID zum Schlüssel abfragen (/oauth2/validate). */
	private static boolean fillUser(Account a) throws IOException, InterruptedException {
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/validate"))
				.timeout(Duration.ofSeconds(10)).header("Authorization", "OAuth " + a.access).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		JsonObject o = parse(r.body());
		if (r.statusCode() != 200 || o == null || !o.has("user_id")) {
			return false;
		}
		a.login = o.get("login").getAsString();
		a.userId = o.get("user_id").getAsString();
		if (a.displayName.isBlank()) {
			a.displayName = a.login;
		}
		return true;
	}

	/** Abmelden: Schlüssel bei Twitch zurückziehen und die Datei löschen. */
	public static void logout() {
		Account a;
		synchronized (TwitchAuth.class) {
			cancelLogin();
			a = account;
			account = null;
			status = Status.NOT_CONNECTED;
			error = "";
		}
		Streamer.applyLater();
		Thread t = new Thread(() -> {
			if (a != null && !a.access.isBlank() && !clientId().isBlank()) {
				try {
					postRaw("https://id.twitch.tv/oauth2/revoke", form("client_id", clientId(), "token", a.access));
				} catch (IOException | InterruptedException | RuntimeException ignored) {
					// Datei wird trotzdem gelöscht
				}
			}
			try {
				Files.deleteIfExists(file());
			} catch (IOException e) {
				Loothub.LOGGER.warn("Twitch-Konto-Datei konnte nicht gelöscht werden", e);
			}
		}, "Loothub-Twitch-Logout");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ Schlüssel

	/** Gültiger Zugangsschlüssel (frischt ihn bei Bedarf auf) oder "" – nur aus Hintergrund-Threads aufrufen. */
	static String token() throws InterruptedException {
		ensureLoaded();
		Account a = account;
		if (a == null) {
			return "";
		}
		if (System.currentTimeMillis() > a.expiresAt - 120_000L) {
			refresh();
			a = account;
		}
		return a == null ? "" : a.access;
	}

	/** Schlüssel erneuern (z. B. nach „401 Unauthorized“). */
	static synchronized void refresh() throws InterruptedException {
		Account a = account;
		if (a == null) {
			return;
		}
		if (a.refresh.isBlank()) {
			expired();
			return;
		}
		try {
			HttpResponse<String> r = postRaw("https://id.twitch.tv/oauth2/token",
					form("grant_type", "refresh_token", "refresh_token", a.refresh, "client_id", clientId()));
			JsonObject o = parse(r.body());
			if (r.statusCode() == 200 && o != null && o.has("access_token")) {
				Account n = new Account();
				n.access = o.get("access_token").getAsString();
				n.refresh = o.has("refresh_token") ? o.get("refresh_token").getAsString() : a.refresh;
				n.expiresAt = System.currentTimeMillis() + (o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600) * 1000L;
				n.login = a.login;
				n.userId = a.userId;
				n.displayName = a.displayName;
				account = n;
				save(n);
				return;
			}
			if (r.statusCode() == 400 || r.statusCode() == 401) {
				expired();
			}
		} catch (IOException e) {
			// Netz kurz weg – beim nächsten Versuch nochmal
		}
	}

	private static void expired() {
		Loothub.LOGGER.info("Twitch-Anmeldung abgelaufen");
		account = null;
		status = Status.ERROR;
		error = "Anmeldung abgelaufen – bitte neu verbinden";
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// egal
		}
		Streamer.applyLater();
	}

	// ------------------------------------------------------------------ HTTP

	private static String form(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : m.entrySet()) {
			if (!sb.isEmpty()) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
					.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	private static HttpResponse<String> postRaw(String url, String body) throws IOException, InterruptedException {
		return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static JsonObject post(String url, String body) throws IOException, InterruptedException {
		HttpResponse<String> r = postRaw(url, body);
		if (r.statusCode() / 100 != 2) {
			Loothub.LOGGER.warn("Twitch: {} antwortet {}", url, r.statusCode());
			return null;
		}
		return parse(r.body());
	}

	static JsonObject parse(String body) {
		try {
			return body == null || body.isBlank() ? null : JsonParser.parseString(body).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
