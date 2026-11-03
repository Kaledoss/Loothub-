package de.loothub.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import de.loothub.Loothub;

/**
 * Update-Funktion: prüft das GitHub-Release „latest“ von Kaledoss/Loothub- auf eine neuere Version
 * (das Repository muss dafür öffentlich sein),
 * lädt sie auf Wunsch in den Mods-Ordner und entfernt die alte Datei nach dem Beenden des Spiels.
 * Die neue Version ist nach einem Neustart aktiv.
 */
public final class Updater {
	private static final String RELEASE_API = "https://api.github.com/repos/Kaledoss/Loothub-/releases/tags/latest";
	private static final Pattern ASSET = Pattern.compile("^" + Loothub.MOD_ID + "-(\\d+(?:\\.\\d+)*)\\.jar$");

	public enum State {
		IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLED, ERROR
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private static volatile State state = State.IDLE;
	private static volatile String latestVersion;
	/** Änderungen der neuesten Version (aus der Release-Beschreibung). */
	private static volatile java.util.List<String> changes = java.util.List.of();

	public static java.util.List<String> changes() {
		return changes;
	}
	private static volatile String downloadUrl;
	private static volatile String errorMessage;

	private Updater() {
	}

	public static State state() {
		return state;
	}

	public static String latestVersion() {
		return latestVersion;
	}

	public static String errorMessage() {
		return errorMessage;
	}

	/** Verständlicher Grund für die Anzeige: not_found, rate_limited, no_file, offline oder HTTP-Code. */
	public static net.minecraft.network.chat.Component errorReason() {
		return reasonText(errorMessage == null ? "offline" : errorMessage);
	}

	/** Fehlercode (not_found, rate_limited, no_file, offline, HTTP …) als lesbarer Text. */
	public static net.minecraft.network.chat.Component reasonText(String e) {
		return switch (e) {
			case "not_found", "rate_limited", "no_file", "offline" ->
					net.minecraft.network.chat.Component.translatable("loothub.update.reason." + e);
			default -> net.minecraft.network.chat.Component.literal(e);
		};
	}

	public static String currentVersion() {
		return FabricLoader.getInstance().getModContainer(Loothub.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("0");
	}

	/** Beim Spielstart: prüft nur, wenn „Automatisch prüfen“ eingeschaltet ist. */
	public static void checkOnStartup() {
		cleanupOldVersions();
		if (de.loothub.client.jobs.WidgetConfig.get().updateCheck) {
			checkAsync();
		}
	}

	/** „Jetzt prüfen“ in den Einstellungen – unabhängig von der Einstellung. */
	public static void checkAgain() {
		if (state == State.CHECKING || state == State.DOWNLOADING || state == State.INSTALLED) {
			return;
		}
		state = State.IDLE;
		latestVersion = null;
		downloadUrl = null;
		errorMessage = null;
		checkAsync();
	}

	/** Prüft im Hintergrund auf eine neue Version. */
	public static void checkAsync() {
		if (state != State.IDLE) {
			return;
		}
		state = State.CHECKING;

		HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASE_API))
				.timeout(Duration.ofSeconds(15))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "Loothub-Mod/" + currentVersion())
				.GET()
				.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.whenComplete((response, error) -> {
					try {
						if (error != null) {
							throw error;
						}
						if (response.statusCode() == 404) {
							throw new IllegalStateException("not_found");
						}
						if (response.statusCode() == 403 || response.statusCode() == 429) {
							throw new IllegalStateException("rate_limited");
						}
						if (response.statusCode() != 200) {
							throw new IllegalStateException("HTTP " + response.statusCode());
						}
						JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
						// Änderungsliste aus der Release-Beschreibung („- …“-Zeilen)
						java.util.List<String> notes = new java.util.ArrayList<>();
						if (release.has("body") && !release.get("body").isJsonNull()) {
							for (String line : release.get("body").getAsString().split("\\r?\\n")) {
								if (line.startsWith("- ")) {
									notes.add(line.substring(2).strip());
								}
							}
						}
						changes = java.util.List.copyOf(notes);
						for (JsonElement el : release.getAsJsonArray("assets")) {
							JsonObject asset = el.getAsJsonObject();
							Matcher m = ASSET.matcher(asset.get("name").getAsString());
							if (m.matches()) {
								latestVersion = m.group(1);
								downloadUrl = asset.get("browser_download_url").getAsString();
								break;
							}
						}
						if (latestVersion == null) {
							throw new IllegalStateException("no_file");
						}
						boolean newer = compare(latestVersion, currentVersion()) > 0;
						state = newer ? State.AVAILABLE : State.UP_TO_DATE;
						Loothub.LOGGER.info("Update-Prüfung: installiert {}, verfügbar {}", currentVersion(), latestVersion);
					} catch (Throwable t) {
						errorMessage = t instanceof IllegalStateException ? t.getMessage() : "offline";
						state = State.ERROR;
						Loothub.LOGGER.warn("Update-Prüfung fehlgeschlagen: {}", t.toString());
					}
				});
	}

	/** Lädt die neue Version herunter. Aktiv nach dem nächsten Neustart. */
	public static void installAsync() {
		if (state != State.AVAILABLE || downloadUrl == null) {
			return;
		}
		Path ownJar = ownJar();
		if (ownJar == null) {
			errorMessage = "Nur in der installierten Mod möglich (nicht in der Entwicklungsumgebung)";
			state = State.ERROR;
			return;
		}
		state = State.DOWNLOADING;
		Path modsDir = ownJar.getParent();
		Path target = modsDir.resolve(Loothub.MOD_ID + "-" + latestVersion + ".jar");
		Path temp = modsDir.resolve(Loothub.MOD_ID + "-" + latestVersion + ".jar.download");

		HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
				.timeout(Duration.ofSeconds(60))
				.header("User-Agent", "Loothub-Mod/" + currentVersion())
				.GET()
				.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
				.whenComplete((response, error) -> {
					try {
						if (error != null) {
							throw error;
						}
						if (response.statusCode() != 200) {
							throw new IllegalStateException("HTTP " + response.statusCode());
						}
						try (InputStream in = response.body()) {
							Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
						}
						if (Files.size(temp) < 1024) {
							throw new IOException("Download unvollständig");
						}
						Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
						if (!target.equals(ownJar)) {
							scheduleDelete(ownJar);
						}
						state = State.INSTALLED;
						Loothub.LOGGER.info("Update {} gespeichert als {}", latestVersion, target);
					} catch (Throwable t) {
						try {
							Files.deleteIfExists(temp);
						} catch (IOException ignored) {
						}
						errorMessage = t.getMessage();
						state = State.ERROR;
						Loothub.LOGGER.warn("Update fehlgeschlagen: {}", t.toString());
					}
				});
	}

	/** Pfad der laufenden Loothub-Datei, oder null (z. B. in der Entwicklungsumgebung). */
	private static Path ownJar() {
		try {
			ModContainer container = FabricLoader.getInstance().getModContainer(Loothub.MOD_ID).orElse(null);
			if (container == null || container.getOrigin().getKind() != ModOrigin.Kind.PATH) {
				return null;
			}
			List<Path> paths = container.getOrigin().getPaths();
			for (Path p : paths) {
				if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
					return p;
				}
			}
		} catch (RuntimeException e) {
			Loothub.LOGGER.debug("Eigene Mod-Datei nicht ermittelbar", e);
		}
		return null;
	}

	/**
	 * Unter Windows ist die laufende Datei gesperrt. Deshalb wird nach dem Beenden des Spiels ein kleiner
	 * Befehl gestartet, der kurz wartet und die alte Datei dann löscht. Auf anderen Systemen geht es direkt.
	 */
	private static void scheduleDelete(Path oldJar) {
		try {
			Files.delete(oldJar);
			return;
		} catch (IOException ignored) {
			// gesperrt – nach dem Beenden löschen
		}
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		String path = oldJar.toAbsolutePath().toString();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				if (windows) {
					new ProcessBuilder("cmd", "/c", "ping 127.0.0.1 -n 6 > nul & del /f /q \"" + path + "\"").start();
				} else {
					new ProcessBuilder("sh", "-c", "sleep 3; rm -f '" + path.replace("'", "'\\''") + "'").start();
				}
			} catch (IOException e) {
				// Beim nächsten Start räumt cleanupOldVersions() auf
			}
		}, "Loothub-Update-Aufräumen"));
	}

	/** Entfernt beim Start übrig gebliebene ältere Loothub-Dateien neben der laufenden Version. */
	private static void cleanupOldVersions() {
		Path ownJar = ownJar();
		if (ownJar == null) {
			return;
		}
		String current = currentVersion();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(ownJar.getParent(), Loothub.MOD_ID + "-*.jar*")) {
			for (Path p : files) {
				if (p.equals(ownJar)) {
					continue;
				}
				String name = p.getFileName().toString();
				if (name.endsWith(".download")) {
					Files.deleteIfExists(p);
					continue;
				}
				Matcher m = ASSET.matcher(name);
				if (m.matches() && compare(m.group(1), current) < 0) {
					Files.deleteIfExists(p);
					Loothub.LOGGER.info("Alte Version entfernt: {}", name);
				}
			}
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.debug("Aufräumen alter Versionen fehlgeschlagen", e);
		}
	}

	/** Vergleicht Versionen wie 1.0.12 und 1.0.9 Zahl für Zahl. */
	static int compare(String a, String b) {
		String[] pa = a.split("[.+-]");
		String[] pb = b.split("[.+-]");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			int x = i < pa.length ? parse(pa[i]) : 0;
			int y = i < pb.length ? parse(pb[i]) : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	private static int parse(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
