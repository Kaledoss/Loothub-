package de.loothub.client.jobs;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die Job-Fortschrittsanzeige von OPSUCHT (Actionbar, einschalten mit {@code /job toggle}).
 *
 * <p>Bekanntes Format: {@code +2,5 XP • +12,73$ • Holzfäller Level 58 • [|||||] 13,85%}.
 * Die einzelnen Werte werden unabhängig voneinander gesucht, damit kleine Formatänderungen nicht gleich alles kaputt machen.
 */
public final class JobParser {
	/** Ein erkannter Job-Fortschritt. {@code progress} ist NaN, wenn keine Prozentangabe dabei war. */
	public record JobGain(String job, int level, double xp, double money, double progress) {
	}

	/** Job-Namen laut OPSUCHT-Wiki. */
	private static final List<String> JOBS = List.of(
			"Minenarbeiter", "Holzfäller", "Gräber", "Jäger", "Angler", "Farmer", "Builder");

	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final Pattern XP = Pattern.compile(
			"(?i)(?:\\+\\s*([0-9][0-9.,]*)\\s*(?:Job-?)?XP\\b|\\bXP\\s*:\\s*\\+?\\s*([0-9][0-9.,]*))");
	private static final Pattern MONEY = Pattern.compile(
			"(?:\\+\\s*([0-9][0-9.,]*)\\s*\\$|\\+?\\s*\\$\\s*([0-9][0-9.,]*))");
	private static final Pattern LEVEL = Pattern.compile("(?i)\\b(?:Level|Lvl\\.?|Lv\\.?)\\s*([0-9]+)");
	private static final Pattern PROGRESS = Pattern.compile("([0-9][0-9.,]*)\\s*%");
	private static final Pattern NAME_BEFORE_LEVEL = Pattern.compile(
			"(\\p{L}[\\p{L}-]{2,24})\\s*[·•|»:\\-]?\\s*(?i:Level|Lvl)");

	private JobParser() {
	}

	public static Optional<JobGain> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String line = FORMATTING.matcher(raw).replaceAll("").strip();
		if (line.isEmpty()) {
			return Optional.empty();
		}

		// Ohne Level-Angabe ist es keine Job-Zeile
		String levelRaw = first(LEVEL, line);
		if (levelRaw == null) {
			return Optional.empty();
		}
		double xp = parseNumber(first(XP, line));
		double money = parseNumber(first(MONEY, line));
		if (Double.isNaN(xp) && Double.isNaN(money)) {
			return Optional.empty();
		}

		String job = findKnownJob(line);
		if (job == null) {
			String candidate = first(NAME_BEFORE_LEVEL, line);
			if (candidate != null && !candidate.equalsIgnoreCase("XP")) {
				job = candidate;
			}
		}

		int level;
		try {
			level = Integer.parseInt(levelRaw);
		} catch (NumberFormatException e) {
			level = -1;
		}

		double progress = Double.NaN;
		Matcher pm = PROGRESS.matcher(line);
		while (pm.find()) {
			progress = parseNumber(pm.group(1)); // letzte Prozentangabe zählt
		}
		if (!Double.isNaN(progress) && (progress < 0 || progress > 100)) {
			progress = Double.NaN;
		}

		return Optional.of(new JobGain(job, level,
				Double.isNaN(xp) ? 0 : xp,
				Double.isNaN(money) ? 0 : money,
				progress));
	}

	private static String findKnownJob(String line) {
		String lower = line.toLowerCase(Locale.ROOT);
		for (String job : JOBS) {
			if (lower.contains(job.toLowerCase(Locale.ROOT))) {
				return job;
			}
		}
		return null;
	}

	/** Erste nicht-leere Gruppe des ersten Treffers. */
	private static String first(Pattern pattern, String line) {
		Matcher m = pattern.matcher(line);
		if (!m.find()) {
			return null;
		}
		for (int i = 1; i <= m.groupCount(); i++) {
			if (m.group(i) != null) {
				return m.group(i);
			}
		}
		return null;
	}

	/**
	 * Liest Zahlen in deutscher und englischer Schreibweise: 12,73 · 12.73 · 1.250,50 · 1,250.50.
	 * Ein einzelner Punkt mit genau drei Ziffern dahinter gilt als Tausenderpunkt (1.000 = tausend).
	 */
	static double parseNumber(String raw) {
		if (raw == null) {
			return Double.NaN;
		}
		String s = raw.strip();
		if (s.isEmpty()) {
			return Double.NaN;
		}
		int lastDot = s.lastIndexOf('.');
		int lastComma = s.lastIndexOf(',');
		String normalized;
		if (lastDot >= 0 && lastComma >= 0) {
			normalized = lastComma > lastDot
					? s.replace(".", "").replace(',', '.')
					: s.replace(",", "");
		} else if (lastComma >= 0) {
			normalized = s.indexOf(',') != lastComma ? s.replace(",", "") : s.replace(',', '.');
		} else if (lastDot >= 0) {
			boolean grouping = s.indexOf('.') != lastDot || (s.length() - lastDot - 1 == 3);
			normalized = grouping ? s.replace(".", "") : s;
		} else {
			normalized = s;
		}
		try {
			return Double.parseDouble(normalized);
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}
}
