package de.loothub.client.cosmetics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Geteilte Cosmetics über den Loothub-Cosmetics-Server (Cloudflare Worker): Die eigene Auswahl wird hochgeladen,
 * sobald sie sich ändert; die Auswahl aller anderen Loothub-Spieler wird alle paar Minuten geladen.
 * <p>
 * Wer man ist, prüft der Server über den Profil-Schlüssel des Kontos, den Mojang beglaubigt (derselbe, mit dem
 * Chat-Nachrichten signiert werden): Die Mod unterschreibt ihre Auswahl damit, der Server prüft Unterschrift und
 * Beglaubigung. Der Zugangsschlüssel des Minecraft-Kontos verlässt dabei nie das Spiel.
 */
public final class CosmeticsServer {
	/** Adresse des Workers, z. B. https://loothub-cosmetics.NAME.workers.dev – leer = aus (nur cosmetics.json). */
	static final String BASE = "https://loothub.kaledoss564.workers.dev";

	private static final long REFRESH_MS = 5 * 60_000L;
	/** Nach der letzten Änderung so lange warten, bevor hochgeladen wird (z. B. beim Durchklicken von Farben). */
	private static final long SETTLE_MS = 20_000L;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	/** Alle Profile vom Server: UUID → {name, set, values, skins}. */
	private static volatile JsonObject all = new JsonObject();
	private static volatile boolean fetched;
	private static volatile boolean busy;
	private static volatile long lastFetch;
	private static String pending;
	private static long pendingSince;
	private static long lastCheck;
	private static volatile long uploadBlockedUntil;
	/** Zuletzt erfolgreich hochgeladen – nicht dasselbe nochmal schicken (falls der Server etwas gekürzt hat). */
	private static volatile String lastUploaded;
	private static volatile long lastUploadedAt;

	private CosmeticsServer() {
	}

	/** Nutzt dieser Spieler Loothub (steht er auf dem Cosmetics-Server)? */
	public static boolean isUser(java.util.UUID id) {
		return id != null && enabled() && all.has(id.toString().toLowerCase(Locale.ROOT));
	}

	public static boolean enabled() {
		return !BASE.isBlank();
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!enabled() || !WidgetConfig.get().cosmetics) {
				return;
			}
			long now = System.currentTimeMillis();
			if (!busy && now - lastFetch > REFRESH_MS) {
				fetch();
			}
			if (now - lastCheck > 2000 && mc.player != null) {
				lastCheck = now;
				checkUpload(mc, now);
			}
		});
	}

	/** Gleich neu laden (z. B. nach dem eigenen Hochladen). */
	private static void fetch() {
		busy = true;
		lastFetch = System.currentTimeMillis();
		HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/all")).timeout(Duration.ofSeconds(15))
				.header("User-Agent", "Loothub-Mod").GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((r, t) -> {
			try {
				if (t == null && r.statusCode() == 200) {
					JsonElement e = JsonParser.parseString(r.body());
					if (e.isJsonObject()) {
						all = e.getAsJsonObject();
						fetched = true;
						Cosmetics.rebuild();
					}
				}
			} catch (RuntimeException e) {
				Loothub.LOGGER.debug("Cosmetics-Server: Antwort nicht lesbar", e);
			} finally {
				busy = false;
			}
		});
	}

	private static String ownUuid(Minecraft mc) {
		return mc.getUser().getProfileId().toString().toLowerCase(Locale.ROOT);
	}

	/** Eigene Auswahl hochladen, wenn sie sich vom Stand auf dem Server unterscheidet und eine Weile gleich blieb. */
	private static void checkUpload(Minecraft mc, long now) {
		if (!fetched || busy || now < uploadBlockedUntil) {
			return;
		}
		JsonObject mine = Cosmetics.ownProfile();
		String want = mine == null ? "" : mine.toString();
		String have = "";
		long seen = 0;
		if (all.has(ownUuid(mc)) && all.get(ownUuid(mc)).isJsonObject()) {
			JsonObject s = all.getAsJsonObject(ownUuid(mc)).deepCopy();
			if (s.has("t") && s.get("t").isJsonPrimitive()) {
				seen = s.get("t").getAsLong();
			}
			s.remove("name");
			s.remove("t");
			have = s.toString();
		}
		// Alle 3 Tage neu melden, damit man als Loothub-Nutzer sichtbar bleibt (Krönchen in der Tab-Liste)
		boolean stale = now - seen > 3 * 24 * 3600_000L;
		boolean recentlySent = want.equals(lastUploaded) && now - lastUploadedAt < 30 * 60_000L;
		if ((want.equals(have) && !stale) || recentlySent) {
			pending = null;
			return;
		}
		if (!want.equals(pending)) {
			pending = want;
			pendingSince = now;
			return;
		}
		if (now - pendingSince < SETTLE_MS) {
			return;
		}
		pending = null;
		upload(mc, mine);
	}

	private static void upload(Minecraft mc, JsonObject profile) {
		busy = true;
		String sent = profile == null ? "" : profile.toString();
		String name = mc.getUser().getName();
		java.util.UUID id = mc.getUser().getProfileId();
		Thread t = new Thread(() -> {
			try {
				// Mit dem von Mojang beglaubigten Schlüssel des Kontos unterschreiben (derselbe wie für Chat-Signaturen).
				// Der Zugangsschlüssel des Kontos verlässt dabei nie das Spiel.
				var pair = mc.getProfileKeyPairManager().prepareKeyPair().get(20, java.util.concurrent.TimeUnit.SECONDS)
						.orElseThrow(() -> new IllegalStateException("kein Profil-Schlüssel (Offline-Konto?)"));
				var data = pair.publicKey().data();
				String uuid = id.toString().toLowerCase(Locale.ROOT);
				long ts = System.currentTimeMillis();
				String profileJson = profile == null ? "null" : profile.toString();
				java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
				sig.initSign(pair.privateKey());
				sig.update(("loothub-cosmetics\n" + uuid + "\n" + ts + "\n" + profileJson).getBytes(StandardCharsets.UTF_8));
				java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
				JsonObject body = new JsonObject();
				body.addProperty("uuid", uuid);
				body.addProperty("name", name);
				body.addProperty("ts", ts);
				body.addProperty("profile", profileJson);
				body.addProperty("publicKey", b64.encodeToString(data.key().getEncoded()));
				body.addProperty("expiresAt", data.expiresAt().toEpochMilli());
				body.addProperty("keySignature", b64.encodeToString(data.keySignature()));
				body.addProperty("signature", b64.encodeToString(sig.sign()));
				HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/profile"))
						.timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
						.header("User-Agent", "Loothub-Mod")
						.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (r.statusCode() == 200) {
					Loothub.LOGGER.info("Cosmetics-Server: eigene Auswahl hochgeladen");
					lastUploaded = sent;
					lastUploadedAt = System.currentTimeMillis();
					lastFetch = 0; // gleich neu laden
				} else {
					Loothub.LOGGER.warn("Cosmetics-Server lehnt ab ({}): {}", r.statusCode(), r.body());
					uploadBlockedUntil = System.currentTimeMillis() + 10 * 60_000L;
				}
			} catch (Exception e) {
				Loothub.LOGGER.warn("Cosmetics-Server: Hochladen fehlgeschlagen", e);
				uploadBlockedUntil = System.currentTimeMillis() + 5 * 60_000L;
			} finally {
				busy = false;
			}
		}, "Loothub-Cosmetics-Upload");
		t.setDaemon(true);
		t.start();
	}

	/** Server-Profile in die Cosmetics-Tabellen übernehmen (je Spieler ersetzt der Server die cosmetics.json). */
	static void mergeInto(Map<String, Set<String>> owners, Map<String, List<String>> skins, Map<String, String> auras,
			Map<String, Map<String, String>> singles) {
		JsonObject data = all;
		String self = ownUuid(Minecraft.getInstance());
		for (String uuid : data.keySet()) {
			if (!data.get(uuid).isJsonObject() || uuid.equalsIgnoreCase(self)) {
				continue; // eigene Auswahl kommt direkt aus den Einstellungen
			}
			JsonObject p = data.getAsJsonObject(uuid);
			String key = uuid.toLowerCase(Locale.ROOT);
			Set<String> set = new HashSet<>();
			if (p.has("set") && p.get("set").isJsonArray()) {
				for (JsonElement e : p.getAsJsonArray("set")) {
					set.add(e.getAsString().toLowerCase(Locale.ROOT));
				}
			}
			if (set.isEmpty()) {
				continue;
			}
			Map<String, String> values = new HashMap<>();
			if (p.has("values") && p.get("values").isJsonObject()) {
				JsonObject v = p.getAsJsonObject("values");
				for (String k : v.keySet()) {
					if (v.get(k).isJsonPrimitive()) {
						values.put(k.toLowerCase(Locale.ROOT), v.get(k).getAsString());
					}
				}
			}
			// Name als zweiter Schlüssel entfernen, damit alte cosmetics.json-Einträge nicht dazwischenfunken
			if (p.has("name") && p.get("name").isJsonPrimitive()) {
				String n = p.get("name").getAsString().toLowerCase(Locale.ROOT);
				owners.remove(n);
				singles.remove(n);
				skins.remove(n);
				auras.remove(n);
			}
			owners.put(key, set);
			singles.put(key, values);
			if (values.containsKey(Cosmetics.AURA)) {
				auras.put(key, values.get(Cosmetics.AURA));
			}
			if (p.has("skins") && p.get("skins").isJsonArray()) {
				List<String> list = new ArrayList<>();
				for (JsonElement e : p.getAsJsonArray("skins")) {
					if (list.size() < Cosmetics.MAX_MINI_MES) {
						list.add(e.isJsonNull() ? null : e.getAsString());
					}
				}
				if (!list.isEmpty()) {
					skins.put(key, list);
				}
			}
		}
	}
}
