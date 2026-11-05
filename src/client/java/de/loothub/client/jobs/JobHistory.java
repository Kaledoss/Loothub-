package de.loothub.client.jobs;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Gespeicherte Job-Sitzungen in {@code config/loothub-jobs.json}. Neueste Sitzung steht am Ende der Liste.
 * Es werden höchstens {@link #MAX_SESSIONS} Sitzungen behalten.
 */
public final class JobHistory {
	private static final int MAX_SESSIONS = 1000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Ein Job innerhalb einer Sitzung. Level/Fortschritt sind -1, wenn unbekannt (kein NaN – JSON kennt das nicht). */
	public static final class JobEntry {
		public String job;
		public double xp;
		public double money;
		public long activeMs;
		public int levelStart = -1;
		public int levelEnd = -1;
		public double progressStart = -1;
		public double progressEnd = -1;
	}

	/** Eine Sitzung: von der ersten bis zur letzten Job-Aktion. Zeiten in Millisekunden (Epoch). */
	public static final class Session {
		public long start;
		public long end;
		public long activeMs;
		public List<JobEntry> jobs = new ArrayList<>();
		/** Nur für die laufende Sitzung gesetzt, wird nicht gespeichert. */
		public transient boolean live;

		public double xp() {
			return jobs.stream().mapToDouble(j -> j.xp).sum();
		}

		public double money() {
			return jobs.stream().mapToDouble(j -> j.money).sum();
		}

		/** Der Job mit dem meisten Geld (bei Gleichstand: meiste XP). */
		public JobEntry mainJob() {
			JobEntry best = null;
			for (JobEntry j : jobs) {
				if (best == null || j.money > best.money || (j.money == best.money && j.xp > best.xp)) {
					best = j;
				}
			}
			return best;
		}

		public JobEntry job(String name) {
			for (JobEntry j : jobs) {
				if (j.job.equals(name)) {
					return j;
				}
			}
			return null;
		}
	}

	private static List<Session> sessions;

	private JobHistory() {
	}

	/** Kopie aller gespeicherten Sitzungen, älteste zuerst. */
	public static synchronized List<Session> all() {
		ensureLoaded();
		return new ArrayList<>(sessions);
	}

	public static synchronized void add(Session session) {
		ensureLoaded();
		sessions.add(session);
		while (sessions.size() > MAX_SESSIONS) {
			sessions.remove(0);
		}
		save();
	}

	public static synchronized void clear() {
		ensureLoaded();
		sessions.clear();
		save();
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(Loothub.MOD_ID + "-jobs.json");
	}

	private static void ensureLoaded() {
		if (sessions != null) {
			return;
		}
		sessions = new ArrayList<>();
		Path p = path();
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			List<Session> loaded = GSON.fromJson(r, new TypeToken<List<Session>>() {
			}.getType());
			if (loaded != null) {
				for (Session s : loaded) {
					if (s != null && s.jobs != null) {
						sessions.add(s);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			Loothub.LOGGER.warn("Job-Verlauf konnte nicht gelesen werden", e);
		}
	}

	/** Erst in eine Temp-Datei schreiben, dann ersetzen – so geht bei einem Absturz nichts kaputt. */
	private static void save() {
		de.loothub.client.AsyncSave.write(path(), GSON.toJson(sessions));
	}
}
