package de.loothub.client.jobs;

import java.util.HashMap;
import java.util.Map;

import de.loothub.Loothub;

/**
 * Sammelt die Job-Fortschritte einer Spielsitzung und berechnet XP/h, Geld/h und die Zeit bis zum Level-Up.
 *
 * <p>Nur aktive Zeit zählt: Liegen zwischen zwei Job-Aktionen mehr als {@link #PAUSE_AFTER_MS}, gilt die Lücke als
 * Pause und verfälscht den Stundenschnitt nicht. Nach {@link #SESSION_TIMEOUT_MS} ohne Aktion oder beim Verlassen
 * des Servers beginnt eine neue Sitzung.
 */
public final class JobTracker {
	/** Längere Lücken zwischen zwei Job-Aktionen gelten als Pause (60 s – manche Jobs zahlen nur alle 20–40 s). */
	public static final long PAUSE_AFTER_MS = 60_000;
	public static final long SESSION_TIMEOUT_MS = 10 * 60_000;
	/** Unter dieser aktiven Zeit sind Stundenwerte zu ungenau und werden nicht angezeigt. */
	private static final long MIN_ACTIVE_FOR_RATES_MS = 20_000;

	private static final class JobStats {
		double xp;
		double money;
		long activeMs;
		int level = -1;
		double progress = Double.NaN;
		int firstLevel = -1;
		double firstProgress = Double.NaN;
		boolean seen;
		double levelStartProgress = Double.NaN;
		double xpSinceLevelStart;
	}

	/** Momentaufnahme für die Anzeige. Werte, die (noch) nicht bekannt sind, sind NaN bzw. -1. */
	public record Snapshot(String job, int level, double progress, double sessionXp, double sessionMoney,
						   double xpPerHour, double moneyPerHour, long etaMs, boolean paused, boolean demo) {
	}

	private static final Map<String, JobStats> JOBS = new HashMap<>();
	private static String currentJob;
	private static long sessionStart;
	private static long lastGain;
	private static double totalMoney;
	private static long totalActiveMs;

	private JobTracker() {
	}

	/** Wird für jede Actionbar-Nachricht aufgerufen. */
	public static void onActionbar(String text) {
		try {
			JobParser.parse(text).ifPresent(JobTracker::onGain);
		} catch (RuntimeException e) {
			Loothub.LOGGER.debug("Actionbar konnte nicht ausgewertet werden: {}", text, e);
		}
	}

	public static synchronized void onGain(JobParser.JobGain gain) {
		long now = System.currentTimeMillis();
		if (lastGain > 0 && now - lastGain > SESSION_TIMEOUT_MS) {
			reset();
		}

		long gap = lastGain == 0 ? 0 : now - lastGain;
		long active = gap <= PAUSE_AFTER_MS ? gap : 0;

		if (sessionStart == 0) {
			sessionStart = now;
		}
		String job = gain.job() != null ? gain.job() : currentJob != null ? currentJob : "Job";
		JobStats s = JOBS.computeIfAbsent(job, k -> new JobStats());
		if (!s.seen) {
			s.seen = true;
			s.firstLevel = gain.level();
			s.firstProgress = gain.progress();
		}
		s.xp += gain.xp();
		s.money += gain.money();
		s.activeMs += active;
		totalActiveMs += active;
		totalMoney += gain.money();

		// Level-Fortschritt: neuer Abschnitt bei Level-Wechsel oder wenn der Prozentwert zurückspringt
		boolean levelChanged = gain.level() >= 0 && s.level >= 0 && gain.level() != s.level;
		boolean progressDropped = !Double.isNaN(gain.progress()) && !Double.isNaN(s.progress)
				&& gain.progress() < s.progress - 0.001;
		if (levelChanged || progressDropped || Double.isNaN(s.levelStartProgress)) {
			s.levelStartProgress = gain.progress();
			s.xpSinceLevelStart = 0;
		} else {
			s.xpSinceLevelStart += gain.xp();
		}
		if (gain.level() >= 0) {
			s.level = gain.level();
		}
		if (!Double.isNaN(gain.progress())) {
			s.progress = gain.progress();
		}

		currentJob = job;
		lastGain = now;
	}

	/** Beendet die Sitzung: speichert sie im Verlauf (falls etwas verdient wurde) und beginnt von vorn. */
	public static synchronized void reset() {
		try {
			JobHistory.Session session = buildSession();
			if (session != null && (session.money() > 0 || session.xp() > 0)) {
				JobHistory.add(session);
			}
		} catch (RuntimeException e) {
			Loothub.LOGGER.warn("Job-Sitzung konnte nicht gespeichert werden", e);
		}
		JOBS.clear();
		currentJob = null;
		sessionStart = 0;
		lastGain = 0;
		totalMoney = 0;
		totalActiveMs = 0;
	}

	public static synchronized boolean hasData() {
		return currentJob != null;
	}

	/** Liefert null, solange in dieser Sitzung noch keine Job-Aktion erkannt wurde. */
	public static synchronized Snapshot snapshot() {
		if (currentJob == null) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (now - lastGain > SESSION_TIMEOUT_MS) {
			reset();
			return null;
		}
		JobStats s = JOBS.get(currentJob);

		double xpPerHour = s.activeMs >= MIN_ACTIVE_FOR_RATES_MS ? s.xp / (s.activeMs / 3_600_000.0) : Double.NaN;
		double moneyPerHour = totalActiveMs >= MIN_ACTIVE_FOR_RATES_MS ? totalMoney / (totalActiveMs / 3_600_000.0) : Double.NaN;

		// Zeit bis Level-Up: aus XP pro Prozentpunkt im aktuellen Level hochgerechnet
		long eta = -1;
		double gained = s.progress - s.levelStartProgress;
		if (!Double.isNaN(gained) && gained > 0.01 && s.xpSinceLevelStart > 0 && xpPerHour > 0 && s.progress < 100) {
			double xpToNext = s.xpSinceLevelStart * (100 - s.progress) / gained;
			eta = (long) (xpToNext / xpPerHour * 3_600_000.0);
		}

		return new Snapshot(currentJob, s.level, s.progress, s.xp, totalMoney, xpPerHour, moneyPerHour, eta,
				now - lastGain > PAUSE_AFTER_MS, false);
	}

	/** Die laufende Sitzung für die Anzeige im Verlauf, oder null. Wird nicht gespeichert. */
	public static synchronized JobHistory.Session liveSession() {
		if (currentJob == null || System.currentTimeMillis() - lastGain > SESSION_TIMEOUT_MS) {
			return null;
		}
		JobHistory.Session session = buildSession();
		if (session != null) {
			session.live = true;
		}
		return session;
	}

	private static JobHistory.Session buildSession() {
		if (currentJob == null || JOBS.isEmpty()) {
			return null;
		}
		JobHistory.Session session = new JobHistory.Session();
		session.start = sessionStart;
		session.end = lastGain;
		session.activeMs = totalActiveMs;
		for (Map.Entry<String, JobStats> e : JOBS.entrySet()) {
			JobStats st = e.getValue();
			JobHistory.JobEntry entry = new JobHistory.JobEntry();
			entry.job = e.getKey();
			entry.xp = st.xp;
			entry.money = st.money;
			entry.activeMs = st.activeMs;
			entry.levelStart = st.firstLevel;
			entry.levelEnd = st.level;
			entry.progressStart = Double.isNaN(st.firstProgress) ? -1 : st.firstProgress;
			entry.progressEnd = Double.isNaN(st.progress) ? -1 : st.progress;
			session.jobs.add(entry);
		}
		return session;
	}

	/** Beispieldaten für den Editor, solange noch keine echten Daten vorliegen. */
	public static Snapshot demo() {
		return new Snapshot("Holzfäller", 58, 13.85, 1240, 6320.5, 4100, 21000, 38 * 60_000L, false, true);
	}
}
