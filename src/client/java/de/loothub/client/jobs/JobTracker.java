package de.loothub.client.jobs;

import java.util.HashMap;
import java.util.Map;

import de.loothub.Loothub;

/**
 * Sammelt die Job-Fortschritte einer Spielsitzung und berechnet XP/h, Geld/h und die Zeit bis zum Level-Up.
 *
 * <p>Nur aktive Zeit zählt: Liegen zwischen zwei Job-Aktionen mehr als {@link #PAUSE_AFTER_MS}, gilt die Lücke als
 * Pause und verfälscht den Stundenschnitt nicht. Eine Sitzung dauert, bis das Spiel geschlossen wird (oder sie in
 * den Einstellungen zurückgesetzt wird) – auch lange Pausen oder ein Serverwechsel beenden sie nicht.
 */
public final class JobTracker {
	/** Längere Lücken zwischen zwei Job-Aktionen gelten als Pause. */
	public static final long PAUSE_AFTER_MS = 20_000;
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
						   double xpPerHour, double moneyPerHour, long etaMs, long trackingMs, boolean paused, boolean demo,
						   double totalXp, double totalMoney) {
	}

	private static final Map<String, JobStats> JOBS = new HashMap<>();
	private static String currentJob;
	private static boolean loggedParseError;
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
			if (!loggedParseError) {
				loggedParseError = true;
				Loothub.LOGGER.warn("Actionbar konnte nicht ausgewertet werden (weitere Meldungen unterdrückt): {}", text, e);
			}
		}
	}

	/** Nach so langer Pause beginnt eine neue Sitzung im Verlauf. */
	private static final long SPLIT_AFTER_MS = 30 * 60_000L;

	private static boolean sameDay(long a, long b) {
		java.time.ZoneId z = java.time.ZoneId.systemDefault();
		return java.time.Instant.ofEpochMilli(a).atZone(z).toLocalDate()
				.equals(java.time.Instant.ofEpochMilli(b).atZone(z).toLocalDate());
	}

	public static synchronized void onGain(JobParser.JobGain gain) {
		long now = System.currentTimeMillis();
		// Neuer Tag oder lange Pause: bisherigen Teil im Verlauf abschließen (das Widget zählt einfach weiter)
		long histStart = historyStart != 0 ? historyStart : sessionStart;
		if (histStart != 0 && (!sameDay(histStart, now) || (lastGain != 0 && now - lastGain > SPLIT_AFTER_MS))) {
			checkpoint(now);
		}
		long gap = lastGain == 0 ? 0 : now - lastGain;
		// Längere Lücken zählen nur bis zur Pausen-Grenze – genau so, wie die Uhr im Widget stehen bleibt
		long active = Math.min(gap, PAUSE_AFTER_MS);

		if (sessionStart == 0) {
			sessionStart = now;
		}
		String job = gain.job() != null ? gain.job() : currentJob != null ? currentJob : "Job";
		if (currentJob != null && !job.equals(currentJob)) {
			active = 0; // Jobwechsel: die Lücke gehört keinem Job
		}
		JobStats s = JOBS.computeIfAbsent(job, k -> new JobStats());
		if (!s.seen) {
			s.seen = true;
			s.firstLevel = gain.level();
			s.firstProgress = gain.progress();
		}
		s.xp += gain.xp();
		addTreasureXp(gain.xp());
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
		if (levelChanged && gain.level() > s.level) {
			de.loothub.client.cosmetics.Emotes.cheer(); // Mini-Mes jubeln mit
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

	/** Beginn des Teils, der noch nicht im Verlauf steht (0 = wie sessionStart). */
	private static long historyStart;
	/** Stand je Job beim letzten Abschluss im Verlauf: {xp, money, activeMs}. */
	private static final Map<String, double[]> BASE = new java.util.HashMap<>();
	private static long baseActive;

	/** Bisherigen Teil als eigene Sitzung in den Verlauf schreiben, Widget-Werte bleiben. */
	private static void checkpoint(long now) {
		try {
			JobHistory.Session session = buildSession();
			if (session != null && (session.money() > 0 || session.xp() > 0)) {
				JobHistory.add(session);
			}
		} catch (RuntimeException e) {
			Loothub.LOGGER.warn("Job-Sitzung konnte nicht gespeichert werden", e);
		}
		for (Map.Entry<String, JobStats> e : JOBS.entrySet()) {
			JobStats st = e.getValue();
			BASE.put(e.getKey(), new double[]{st.xp, st.money, st.activeMs});
		}
		baseActive = totalActiveMs;
		historyStart = now;
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
		BASE.clear();
		baseActive = 0;
		historyStart = 0;
		currentJob = null;
		sessionStart = 0;
		lastGain = 0;
		totalMoney = 0;
		totalActiveMs = 0;
	}

	/** Alle 100.000 Job-XP gibt es eine Chance auf einen Jobschatz – Fortschritt dauerhaft mitzählen. */
	public static final double TREASURE_STEP = 100_000;
	private static long lastTreasureSave;

	private static void addTreasureXp(double xp) {
		if (xp <= 0) {
			return;
		}
		WidgetConfig c = WidgetConfig.get();
		c.treasureXp += xp;
		boolean crossed = false;
		while (c.treasureXp >= TREASURE_STEP) {
			c.treasureXp -= TREASURE_STEP;
			c.treasureChances++;
			crossed = true;
		}
		long now = System.currentTimeMillis();
		if (crossed || now - lastTreasureSave > 60_000) {
			lastTreasureSave = now;
			c.save();
		}
		if (crossed && c.jobWidgetEnabled) {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			mc.execute(() -> {
				if (mc.player != null) {
					mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("loothub.treasure.chance")
							.withStyle(net.minecraft.ChatFormatting.GOLD), false);
					mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
							net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.4f));
				}
			});
		}
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
		JobStats s = JOBS.get(currentJob);

		// Werte des aktuellen Jobs – jeder Job zählt für sich und macht beim Zurückwechseln weiter
		double xpPerHour = s.activeMs >= MIN_ACTIVE_FOR_RATES_MS ? s.xp / (s.activeMs / 3_600_000.0) : Double.NaN;
		double moneyPerHour = s.activeMs >= MIN_ACTIVE_FOR_RATES_MS ? s.money / (s.activeMs / 3_600_000.0) : Double.NaN;
		double totalXp = 0;
		for (JobStats st : JOBS.values()) {
			totalXp += st.xp;
		}

		// Zeit bis Level-Up: aus XP pro Prozentpunkt im aktuellen Level hochgerechnet
		long eta = -1;
		double gained = s.progress - s.levelStartProgress;
		if (!Double.isNaN(gained) && gained > 0.01 && s.xpSinceLevelStart > 0 && xpPerHour > 0 && s.progress < 100) {
			double xpToNext = s.xpSinceLevelStart * (100 - s.progress) / gained;
			eta = (long) (xpToNext / xpPerHour * 3_600_000.0);
		}

		return new Snapshot(currentJob, s.level, s.progress, s.xp, s.money, xpPerHour, moneyPerHour, eta,
				s.activeMs + Math.min(now - lastGain, PAUSE_AFTER_MS), now - lastGain > PAUSE_AFTER_MS, false,
				totalXp, totalMoney);
	}

	/** Die laufende Sitzung für die Anzeige im Verlauf, oder null. Wird nicht gespeichert. */
	public static synchronized JobHistory.Session liveSession() {
		if (currentJob == null) {
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
		session.start = historyStart != 0 ? historyStart : sessionStart;
		session.end = lastGain;
		session.activeMs = totalActiveMs - baseActive;
		for (Map.Entry<String, JobStats> e : JOBS.entrySet()) {
			JobStats st = e.getValue();
			double[] base = BASE.getOrDefault(e.getKey(), new double[3]);
			if (st.xp - base[0] <= 0 && st.money - base[1] <= 0) {
				continue; // in diesem Abschnitt nichts in diesem Job
			}
			JobHistory.JobEntry entry = new JobHistory.JobEntry();
			entry.job = e.getKey();
			entry.xp = st.xp - base[0];
			entry.money = st.money - base[1];
			entry.activeMs = (long) (st.activeMs - base[2]);
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
		return new Snapshot("Holzfäller", 58, 13.85, 1240, 6320.5, 4100, 21000, 38 * 60_000L, 49 * 60_000L + 55_000L, false, true,
				3480, 9875.25);
	}
}
