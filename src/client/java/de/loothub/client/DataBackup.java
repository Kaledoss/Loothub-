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

	/** Ganz am Anfang aufrufen (vor dem ersten Lesen der Einstellungen). */
	public static void restoreIfEmpty() {
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
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
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

	/** Für die Einstellungen: Ordner der Sicherungen. */
	public static Path folder() {
		return backupRoot();
	}
}
