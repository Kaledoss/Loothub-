package de.loothub.client.bau;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Gemeinsam bauen: eine platzierte Schematic (Datei, Ort, Drehung, Spiegelung) über den Loothub-Server an einen
 * Freund mit Loothub+ schicken. Er nimmt sie an und sieht sie am selben Ort; verschiebt oder dreht man sie, zieht seine
 * Kopie nach. Wer etwas schickt, prüft der Server über das von Mojang beglaubigte Konto (wie bei den Cosmetics).
 */
public final class Share {
	/** Zum Senden vorbereitet: Einstellungen der Platzierung (ohne Dateipfad) und die Schematic-Datei. */
	record Outgoing(String id, String name, JsonObject settings, byte[] file, String fileName) {
	}

	/** Eine Schematic, die jemand mit dir teilt. */
	public record Incoming(String id, String from, String name, long rev, boolean accepted) {
	}

	private static final String BASE = de.loothub.client.cosmetics.CosmeticsServer.BASE;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	/** Höchstgröße einer Schematic-Datei (größere schickt der Server nicht weiter). */
	public static final int MAX_FILE = 2_000_000;

	private static volatile List<Incoming> inbox = List.of();
	private static volatile String status = "";
	private static long nextPoll, nextUpdateCheck;
	private static volatile boolean busy;

	/** Geteilt an wen (Platzierungs-ID → Namen) und mit welchen Einstellungen zuletzt. */
	private static Map<String, List<String>> sent;
	private static final Map<String, String> SENT_SETTINGS = new HashMap<>();
	/** Angenommene Schematics: ID → Stand (rev). */
	private static Map<String, Long> accepted;

	private Share() {
	}

	public static List<Incoming> inbox() {
		return inbox;
	}

	/** Letzte Meldung (Erfolg/Fehler) zum Anzeigen. */
	public static String status() {
		return status;
	}

	/** An wen diese Schematic geteilt ist. */
	public static List<String> sharedWith(Plan plan) {
		load();
		return sent.getOrDefault(plan.id, List.of());
	}

	static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (busy || mc.player == null) {
			return;
		}
		if (now >= nextPoll) {
			nextPoll = now + 30_000;
			async(Share::poll);
			return;
		}
		// eigene geteilte Schematic verschoben/gedreht? Dann die neuen Einstellungen nachschicken (ohne Datei)
		if (now >= nextUpdateCheck) {
			nextUpdateCheck = now + 10_000;
			load();
			for (var e : sent.entrySet()) {
				Plan p = Build.plan(e.getKey());
				if (p == null || e.getValue().isEmpty()) {
					continue;
				}
				Outgoing out = LitematicaBridge.export(p);
				if (out == null) {
					continue;
				}
				String settings = out.settings().toString();
				String before = SENT_SETTINGS.get(p.id);
				if (before == null) {
					SENT_SETTINGS.put(p.id, settings); // erster Stand nach dem Start
				} else if (!before.equals(settings)) {
					SENT_SETTINGS.put(p.id, settings);
					List<String> to = List.copyOf(e.getValue());
					async(() -> {
						for (String name : to) {
							send(out, name, false);
						}
					});
					return;
				}
			}
		}
	}

	// ------------------------------------------------------------------ Senden

	/** Schematic an einen Spieler schicken. */
	public static void share(Plan plan, String target) {
		String to = target.strip();
		if (to.isEmpty()) {
			return;
		}
		Outgoing out = LitematicaBridge.export(plan);
		if (out == null) {
			status = tr("loothub.build.share.no_file");
			return;
		}
		if (out.file().length > MAX_FILE) {
			status = tr("loothub.build.share.too_big");
			return;
		}
		async(() -> {
			if (send(out, to, true)) {
				load();
				List<String> list = new ArrayList<>(sent.getOrDefault(plan.id, List.of()));
				if (list.stream().noneMatch(n -> n.equalsIgnoreCase(to))) {
					list.add(to);
				}
				sent.put(plan.id, list);
				SENT_SETTINGS.put(plan.id, out.settings().toString());
				saveState();
				status = tr("loothub.build.share.sent", to);
			}
		});
	}

	/** Teilen mit einem Spieler beenden (seine Kopie verschwindet beim nächsten Abgleich). */
	public static void unshare(Plan plan, String target) {
		async(() -> {
			JsonObject body = new JsonObject();
			body.addProperty("id", plan.id);
			body.addProperty("to", target.toLowerCase(Locale.ROOT));
			if (post("/unshare", "loothub-unshare\n%s\n%d\n" + plan.id + "\n" + target.toLowerCase(Locale.ROOT), body) != null) {
				load();
				List<String> list = new ArrayList<>(sent.getOrDefault(plan.id, List.of()));
				list.removeIf(n -> n.equalsIgnoreCase(target));
				sent.put(plan.id, list);
				saveState();
			}
		});
	}

	private static boolean send(Outgoing out, String to, boolean withFile) {
		String settings = out.settings().toString();
		String file = withFile ? Base64.getEncoder().encodeToString(out.file()) : "";
		JsonObject body = new JsonObject();
		body.addProperty("id", out.id());
		body.addProperty("to", to.toLowerCase(Locale.ROOT));
		body.addProperty("name", out.name());
		body.addProperty("settings", settings);
		body.addProperty("file", file);
		body.addProperty("fileName", out.fileName());
		String digest = sha256(settings + "\n" + file);
		JsonObject r = post("/share", "loothub-share\n%s\n%d\n" + to.toLowerCase(Locale.ROOT) + "\n" + out.id() + "\n" + digest,
				body);
		return r != null;
	}

	// ------------------------------------------------------------------ Empfangen

	private static void poll() {
		JsonObject r = post("/inbox", "loothub-inbox\n%s\n%d", new JsonObject());
		if (r == null || !r.has("shares")) {
			return;
		}
		load();
		List<Incoming> list = new ArrayList<>();
		java.util.Set<String> still = new java.util.HashSet<>();
		for (JsonElement el : r.getAsJsonArray("shares")) {
			JsonObject o = el.getAsJsonObject();
			String id = o.get("id").getAsString();
			if (!LitematicaBridge.validId(id)) {
				continue;
			}
			long rev = o.get("rev").getAsLong();
			still.add(id);
			Long have = accepted.get(id);
			list.add(new Incoming(id, o.get("fromName").getAsString(), o.get("name").getAsString(), rev, have != null));
			if (have != null && rev > have) {
				fetch(id, rev); // angenommene Schematic wurde verschoben/geändert: nachziehen
			}
		}
		// vom Absender zurückgezogen: eigene Kopie entfernen
		for (String id : new ArrayList<>(accepted.keySet())) {
			if (!still.contains(id)) {
				accepted.remove(id);
				Minecraft.getInstance().execute(() -> {
					LitematicaBridge.removeShared(id);
					Build.refreshSoon();
				});
				saveState();
			}
		}
		inbox = list;
	}

	/** Geteilte Schematic annehmen: Datei holen und bei Litematica platzieren. */
	public static void accept(Incoming in) {
		async(() -> fetch(in.id(), in.rev()));
	}

	/** Ablehnen bzw. eine angenommene Schematic wieder entfernen. */
	public static void decline(Incoming in) {
		async(() -> {
			JsonObject body = new JsonObject();
			body.addProperty("id", in.id());
			post("/share-drop", "loothub-share-drop\n%s\n%d\n" + in.id(), body);
			load();
			accepted.remove(in.id());
			saveState();
			Minecraft.getInstance().execute(() -> {
				LitematicaBridge.removeShared(in.id());
				Build.refreshSoon();
			});
			poll();
		});
	}

	private static void fetch(String id, long rev) {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		JsonObject r = post("/share-get", "loothub-share-get\n%s\n%d\n" + id, body);
		if (r == null || !r.has("settings") || !r.has("file")) {
			return;
		}
		try {
			byte[] data = Base64.getDecoder().decode(r.get("file").getAsString());
			String fileName = r.has("fileName") ? r.get("fileName").getAsString() : "geteilt.litematic";
			fileName = fileName.replaceAll("[^A-Za-z0-9._ -]", "_");
			// Endung behalten: Litematica liest die Datei je nach Endung als .litematic, .schem (Sponge), .schematic
			// oder .nbt (Strukturblock) – eine umbenannte .schem wäre „ohne Versionsangabe“ und ließe sich nicht laden
			String lower = fileName.toLowerCase(Locale.ROOT);
			if (!(lower.endsWith(".litematic") || lower.endsWith(".schem") || lower.endsWith(".schematic")
					|| lower.endsWith(".nbt"))) {
				fileName = fileName + ".litematic";
			}
			Path dir = LitematicaBridge.sharedDir();
			Files.createDirectories(dir);
			Path file = dir.resolve(id.substring(0, 8) + "-" + fileName);
			Files.write(file, data);
			JsonObject settings = JsonParser.parseString(r.get("settings").getAsString()).getAsJsonObject();
			Minecraft.getInstance().execute(() -> {
				if (LitematicaBridge.importShared(id, settings, file)) {
					load();
					accepted.put(id, rev);
					saveState();
					status = tr("loothub.build.share.accepted");
					Build.refreshSoon();
				} else {
					status = tr("loothub.build.share.failed");
				}
			});
		} catch (Exception e) {
			Loothub.LOGGER.warn("Geteilte Schematic konnte nicht übernommen werden", e);
			status = tr("loothub.build.share.failed");
		}
	}

	// ------------------------------------------------------------------ Server

	/**
	 * Unterschriebene Anfrage an den Loothub-Server. {@code message} enthält „%s“ (UUID) und „%d“ (Zeit) für die
	 * Unterschrift. Antwort als JSON oder null (Fehler; Grund steht dann in {@link #status()}).
	 */
	private static JsonObject post(String path, String message, JsonObject body) {
		Minecraft mc = Minecraft.getInstance();
		try {
			var pair = mc.getProfileKeyPairManager().prepareKeyPair().get(20, java.util.concurrent.TimeUnit.SECONDS)
					.orElseThrow(() -> new IllegalStateException("kein Profil-Schlüssel"));
			var data = pair.publicKey().data();
			UUID self = mc.getUser().getProfileId();
			String uuid = self.toString().toLowerCase(Locale.ROOT);
			long ts = System.currentTimeMillis();
			java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
			sig.initSign(pair.privateKey());
			sig.update(String.format(Locale.ROOT, message, uuid, ts).getBytes(StandardCharsets.UTF_8));
			Base64.Encoder b64 = Base64.getEncoder();
			body.addProperty("uuid", uuid);
			body.addProperty("me", mc.getUser().getName());
			body.addProperty("ts", ts);
			body.addProperty("publicKey", b64.encodeToString(data.key().getEncoded()));
			body.addProperty("expiresAt", data.expiresAt().toEpochMilli());
			body.addProperty("keySignature", b64.encodeToString(data.keySignature()));
			body.addProperty("signature", b64.encodeToString(sig.sign()));
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path))
					.timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
					.header("User-Agent", "Loothub-Mod")
					.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (r.statusCode() == 404 && path.equals("/share")) {
				status = tr("loothub.build.share.unknown");
				return null;
			}
			if (r.statusCode() != 200) {
				Loothub.LOGGER.debug("Bau-Server lehnt ab ({}): {}", r.statusCode(), r.body());
				status = tr("loothub.build.share.error", r.statusCode());
				return null;
			}
			return JsonParser.parseString(r.body()).getAsJsonObject();
		} catch (Exception e) {
			Loothub.LOGGER.debug("Bau-Server nicht erreichbar", e);
			status = tr("loothub.build.share.offline");
			return null;
		}
	}

	private static final java.util.concurrent.ExecutorService EXEC = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Loothub-Bau");
		t.setDaemon(true);
		return t;
	});
	private static final java.util.concurrent.atomic.AtomicInteger QUEUED = new java.util.concurrent.atomic.AtomicInteger();

	/** Der Reihe nach im Hintergrund ausführen (Klicks gehen nicht verloren, auch wenn gerade abgefragt wird). */
	private static void async(Runnable r) {
		QUEUED.incrementAndGet();
		busy = true;
		EXEC.execute(() -> {
			try {
				r.run();
			} catch (RuntimeException e) {
				Loothub.LOGGER.debug("Gemeinsam bauen: Fehler", e);
			} finally {
				busy = QUEUED.decrementAndGet() > 0;
			}
		});
	}

	private static String sha256(String s) {
		try {
			byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder b = new StringBuilder();
			for (byte x : d) {
				b.append(String.format(Locale.ROOT, "%02x", x));
			}
			return b.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	// ------------------------------------------------------------------ gemerkter Stand (config/loothub-bau-geteilt.json)

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-bau-geteilt.json");
	}

	private static synchronized void load() {
		if (sent != null) {
			return;
		}
		sent = new HashMap<>();
		accepted = new HashMap<>();
		try {
			Path p = file();
			if (!Files.exists(p)) {
				return;
			}
			JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("sent")) {
				for (var e : root.getAsJsonObject("sent").entrySet()) {
					List<String> names = new ArrayList<>();
					e.getValue().getAsJsonArray().forEach(n -> names.add(n.getAsString()));
					sent.put(e.getKey(), names);
				}
			}
			if (root.has("accepted")) {
				for (var e : root.getAsJsonObject("accepted").entrySet()) {
					accepted.put(e.getKey(), e.getValue().getAsLong());
				}
			}
		} catch (Exception e) {
			Loothub.LOGGER.debug("Geteilte Schematics nicht lesbar", e);
		}
	}

	private static synchronized void saveState() {
		JsonObject root = new JsonObject();
		JsonObject s = new JsonObject();
		sent.forEach((id, names) -> {
			JsonArray a = new JsonArray();
			names.forEach(a::add);
			s.add(id, a);
		});
		root.add("sent", s);
		JsonObject a = new JsonObject();
		accepted.forEach(a::addProperty);
		root.add("accepted", a);
		try {
			Files.writeString(file(), root.toString(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			Loothub.LOGGER.debug("Geteilte Schematics nicht gespeichert", e);
		}
	}
}
