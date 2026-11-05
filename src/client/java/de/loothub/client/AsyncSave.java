package de.loothub.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.loothub.Loothub;

/**
 * Schreibt Dateien im Hintergrund, damit das Spiel beim Speichern nicht kurz hängt. Der Inhalt wird vorher auf dem
 * Spiel-Thread fertig erzeugt; mehrere Speicherungen derselben Datei kurz hintereinander werden zusammengefasst
 * (nur die neueste wird geschrieben). Erst Temp-Datei, dann ersetzen – so geht bei einem Absturz nichts kaputt.
 */
public final class AsyncSave {
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Loothub-Speichern");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static final Map<Path, String> PENDING = new LinkedHashMap<>();

	private AsyncSave() {
	}

	public static void write(Path path, String content) {
		boolean schedule;
		synchronized (PENDING) {
			schedule = !PENDING.containsKey(path);
			PENDING.put(path, content);
		}
		if (schedule) {
			IO.execute(() -> flush(path));
		}
	}

	private static void flush(Path path) {
		String content;
		synchronized (PENDING) {
			content = PENDING.remove(path);
		}
		if (content == null) {
			return;
		}
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(tmp, content, StandardCharsets.UTF_8);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Loothub.LOGGER.warn("Konnte {} nicht speichern", path.getFileName(), e);
		}
	}

	/** Beim Beenden: ausstehende Speicherungen abwarten. */
	public static void shutdown() {
		IO.shutdown();
		try {
			IO.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}
}
