package de.loothub.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import de.loothub.Loothub;

/**
 * Sicherung der Loothub-Daten (Stats, Verläufe, Itemdex, Einstellungen …) einmal am Tag in einen Ordner außerhalb des
 * Spielordners: {@code <Benutzer>/.loothub/backups/<Datum>/}, die letzten 7 Tage bleiben. Liegt der Ordner außerhalb,
 * übersteht die Sicherung auch einen Wechsel des Launchers oder Spielordners.
 * <p>
 * Findet Loothub beim Start im aktuellen Spielordner gar keine Daten, obwohl es eine Sicherung gibt (z. B. weil der
 * Launcher plötzlich einen anderen Ordner nutzt), wird die neueste Sicherung automatisch eingespielt – bevor
 * irgendetwas gelesen wird – und ein Hinweis gezeigt. Zugangsdaten (Twitch, Musik) werden nie gesichert.
 */
public final class DataBackup {
	private static final int KEEP_DAYS = 7;
	private static final long CHECK_MS = 6L * 60 * 60 * 1000;
	private static String restoredFrom;
	private static long lastCheck;

	private DataBackup() {
	}

	private static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	private static Path backupRoot() {
		return Path.of(System.getProperty("user.home"), ".loothub", "backups");
	}

	/** Gehört die Datei zu den zu sichernden Loothub-Daten? */
	private static boolean included(Path p) {
		String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		return n.startsWith(Loothub.MOD_ID) && Files.isRegularFile(p) && !n.contains("token") && !n.contains("account")
				&& !n.endsWith(".tmp") && !n.endsWith(".csv");
	}

	private static List<Path> dataFiles(Path dir) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
			for (Path p : s) {
				if (included(p)) {
					out.add(p);
				}
			}
		} catch (IOException e) {
			Loothub.LOGGER.debug("Loothub-Dateien nicht lesbar", e);
		}
		return out;
	}

	private static List<Path> backups() {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(backupRoot())) {
			return out;
		}
		try (DirectoryStream<Path> s = Files.newDirectoryStream(backupRoot())) {
			for (Path p : s) {
				if (Files.isDirectory(p) && p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}")) {
					out.add(p);
				}
			}
		} catch (IOException e) {
			Loothub.LOGGER.debug("Sicherungen nicht lesbar", e);
		}
		out.sort(Comparator.comparing(p -> p.getFileName().toString()));
		return out;
	}

	// ------------------------------------------------------------------ Export / Import (Umzug auf anderen PC)

	public static Path exportDir() {
		return Path.of(System.getProperty("user.home"), ".loothub", "export");
	}

	private static Path pendingFile() {
		return Path.of(System.getProperty("user.home"), ".loothub", "einspielen.txt");
	}

	/** Eine Sicherungsquelle für die Auswahl: tägliche Sicherung (Ordner) oder exportierte Zip-Datei. */
	public record Source(Path path, boolean zip, String label, long modified) {
	}

	/** Alle Daten als eine Zip-Datei in den Export-Ordner packen (ohne Zugangsdaten). Gibt die Datei zurück. */
	public static Path exportZip() throws IOException {
		de.loothub.client.AsyncSave.flushAll();
		List<Path> files = dataFiles(configDir());
		if (files.isEmpty()) {
			throw new IOException("keine Daten");
		}
		Files.createDirectories(exportDir());
		String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
		Path zip = exportDir().resolve("loothub-daten-" + stamp + ".zip");
		Path tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
			for (Path f : files) {
				out.putNextEntry(new java.util.zip.ZipEntry(f.getFileName().toString()));
				Files.copy(f, out);
				out.closeEntry();
			}
		}
		Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
		return zip;
	}

	/** Exportierte Zips (neueste zuerst), dann die täglichen Sicherungen (neueste zuerst). */
	public static List<Source> sources() {
		List<Source> out = new ArrayList<>();
		if (Files.isDirectory(exportDir())) {
			try (DirectoryStream<Path> s = Files.newDirectoryStream(exportDir(), "*.zip")) {
				for (Path p : s) {
					out.add(new Source(p, true, p.getFileName().toString(), Files.getLastModifiedTime(p).toMillis()));
				}
			} catch (IOException e) {
				Loothub.LOGGER.debug("Export-Ordner nicht lesbar", e);
			}
		}
		out.sort(Comparator.comparingLong(Source::modified).reversed());
		List<Path> daily = backups();
		for (int i = daily.size() - 1; i >= 0; i--) {
			Path d = daily.get(i);
			long m;
			try {
				m = Files.getLastModifiedTime(d).toMillis();
			} catch (IOException e) {
				m = 0;
			}
			out.add(new Source(d, false, d.getFileName().toString(), m));
		}
		return out;
	}

	/** Merkt die Quelle vor; eingespielt wird beim nächsten Spielstart, bevor irgendetwas gelesen wird. */
	public static void scheduleRestore(Path source) throws IOException {
		Files.createDirectories(pendingFile().getParent());
		Files.writeString(pendingFile(), source.toAbsolutePath().toString(), StandardCharsets.UTF_8);
	}

	public static boolean restorePending() {
		return Files.exists(pendingFile());
	}

	public static void cancelRestore() {
		try {
			Files.deleteIfExists(pendingFile());
		} catch (IOException ignored) {
			// egal
		}
	}

	/** Vorgemerkte Sicherung einspielen: aktueller Stand wird vorher nach backups/vor-import gesichert. */
	private static boolean restorePendingNow() {
		Path pf = pendingFile();
		if (!Files.exists(pf)) {
			return false;
		}
		try {
			Path src = Path.of(Files.readString(pf, StandardCharsets.UTF_8).trim());
			Files.deleteIfExists(pf); // nur einmal versuchen – nie in einer Schleife hängen bleiben
			if (!Files.exists(src)) {
				Loothub.LOGGER.warn("Loothub: vorgemerkte Sicherung {} gibt es nicht mehr", src);
				return false;
			}
			// Sicherheitskopie des aktuellen Stands
			Path safety = backupRoot().resolve("vor-import");
			if (Files.isDirectory(safety)) {
				try (DirectoryStream<Path> s = Files.newDirectoryStream(safety)) {
					for (Path p : s) {
						Files.deleteIfExists(p);
					}
				}
			}
			Files.createDirectories(safety);
			List<Path> current = dataFiles(configDir());
			for (Path f : current) {
				Files.copy(f, safety.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
			int n = 0;
			if (Files.isDirectory(src)) {
				List<Path> files = dataFiles(src);
				if (files.isEmpty()) {
					return false;
				}
				for (Path f : current) {
					if (!otherAccount(f)) {
						Files.deleteIfExists(f); // Dateien anderer Minecraft-Konten bleiben, wie sie sind
					}
				}
				for (Path f : files) {
					Files.copy(f, configDir().resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
					n++;
				}
			} else {
				// Zip: nur flache Loothub-Dateinamen annehmen (keine Pfade, keine Zugangsdaten)
				List<String> names = new ArrayList<>();
				try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(src))) {
					for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
						names.add(e.getName());
					}
				}
				if (names.stream().noneMatch(DataBackup::safeName)) {
					Loothub.LOGGER.warn("Loothub: {} enthält keine Loothub-Daten", src);
					return false;
				}
				for (Path f : current) {
					if (!otherAccount(f)) {
						Files.deleteIfExists(f); // Dateien anderer Minecraft-Konten bleiben, wie sie sind
					}
				}
				try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(src))) {
					for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
						if (!e.isDirectory() && safeName(e.getName())) {
							Files.copy(in, configDir().resolve(e.getName()), StandardCopyOption.REPLACE_EXISTING);
							n++;
						}
					}
				}
			}
			// Sicherung aus der Zeit vor den Konto-Dateien: deren gemeinsame Statistik gehört dem Konto, das sie einspielt
			for (String name : new String[]{"loothub-stats.json", "loothub-jobs.json", "loothub-fishing.json", "loothub-backpack.json"}) {
				Path legacy = configDir().resolve(name);
				if (Files.exists(legacy)) {
					try {
						int dot = name.lastIndexOf('.');
						Path mine = configDir().resolve(name.substring(0, dot) + "." + Accounts.id() + name.substring(dot));
						Files.move(legacy, mine, StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException | RuntimeException e) {
						Loothub.LOGGER.warn("Loothub: {} aus der Sicherung nicht dem Konto zugeordnet", name, e);
					}
				}
			}
			restoredFrom = src.getFileName().toString();
			Loothub.LOGGER.info("Loothub: Sicherung {} eingespielt ({} Dateien, vorheriger Stand in {})", restoredFrom, n, safety);
			return true;
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.warn("Loothub: vorgemerkte Sicherung konnte nicht eingespielt werden", e);
			return false;
		}
	}

	/** Kontobezogene Datei eines anderen Kontos (loothub-stats.&lt;uuid&gt;.json)? */
	private static boolean otherAccount(Path f) {
		String n = f.getFileName().toString();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.([0-9a-f]{32})\\.json$").matcher(n);
		try {
			return m.find() && !m.group(1).equals(Accounts.id());
		} catch (RuntimeException e) {
			return true; // im Zweifel nichts löschen
		}
	}

	private static boolean safeName(String n) {
		String l = n.toLowerCase(java.util.Locale.ROOT);
		return n.matches("[A-Za-z0-9._-]+") && l.startsWith(Loothub.MOD_ID) && !l.contains("token") && !l.contains("account")
				&& !l.endsWith(".tmp") && !l.endsWith(".csv");
	}

	/** Ganz am Anfang aufrufen (vor dem ersten Lesen der Einstellungen). */
	public static void restoreIfEmpty() {
		if (restorePendingNow()) {
			return;
		}
		try {
			if (!dataFiles(configDir()).isEmpty()) {
				return; // Daten da – nichts zu tun
			}
			List<Path> all = backups();
			if (all.isEmpty()) {
				return; // wirklich neu
			}
			Path newest = all.get(all.size() - 1);
			List<Path> files = dataFiles(newest);
			if (files.isEmpty()) {
				return;
			}
			Files.createDirectories(configDir());
			for (Path f : files) {
				Files.copy(f, configDir().resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
			restoredFrom = newest.getFileName().toString();
			Loothub.LOGGER.info("Loothub: keine Daten im Spielordner – Sicherung vom {} eingespielt ({} Dateien)", restoredFrom,
					files.size());
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.warn("Loothub: Sicherung konnte nicht eingespielt werden", e);
		}
	}

	public static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			backupNow();
			if (restoredFrom != null) {
				client.getToastManager().addToast(new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
						Component.translatable("loothub.backup.restored.title"),
						Component.translatable("loothub.backup.restored", restoredFrom)));
			}
		});
		// lange Sitzungen: alle paar Stunden prüfen, ob heute schon gesichert wurde
		de.loothub.client.util.Guard.tick("DataBackup", client -> {
			long now = System.currentTimeMillis();
			if (now - lastCheck > CHECK_MS) {
				backupNow();
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> backupNow(true));
	}

	private static void backupNow() {
		backupNow(false);
	}

	/** Einmal am Tag (bzw. beim Beenden: den heutigen Stand aktualisieren). Im Hintergrund, damit nichts ruckelt. */
	private static synchronized void backupNow(boolean refreshToday) {
		lastCheck = System.currentTimeMillis();
		Path today = backupRoot().resolve(LocalDate.now().toString());
		if (Files.isDirectory(today) && !refreshToday) {
			return;
		}
		List<Path> files = dataFiles(configDir());
		if (files.isEmpty()) {
			return;
		}
		Runnable job = () -> {
			try {
				Files.createDirectories(today);
				for (Path f : files) {
					Files.copy(f, today.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
				Files.writeString(today.resolve("_quelle.txt"), configDir().toAbsolutePath().toString(), StandardCharsets.UTF_8);
				List<Path> all = backups();
				for (int i = 0; i < all.size() - KEEP_DAYS; i++) {
					deleteDir(all.get(i));
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.warn("Loothub: Sicherung fehlgeschlagen", e);
			}
		};
		if (refreshToday) {
			job.run(); // beim Beenden direkt (sonst wird der Hintergrund-Thread abgebrochen)
		} else {
			Thread t = new Thread(job, "Loothub-Sicherung");
			t.setDaemon(true);
			t.start();
		}
	}

	private static void deleteDir(Path dir) throws IOException {
		// nur alte Sicherungsordner in .loothub/backups – nichts anderes
		if (!dir.getParent().equals(backupRoot())) {
			return;
		}
		try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
			for (Path p : s) {
				Files.deleteIfExists(p);
			}
		}
		Files.deleteIfExists(dir);
	}

}
