package de.loothub.client.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reine Lese-Logik ohne Minecraft (deshalb automatisch testbar): Geldbeträge im deutschen Format, Marktplatz- und
 * /sell-Meldungen, Fang-Meldungen beim Angeln.
 */
public final class Parse {
	private Parse() {
	}

	/**
	 * Zahl im OPSUCHT-Format: „1.234,56“, „94.750“ (Punkt als Tausendertrennung), „1234.5“, „12“. Unlesbar = 0.
	 */
	public static double money(String s) {
		if (s == null) {
			return 0;
		}
		String t = s.strip();
		if (t.contains(",")) {
			t = t.replace(".", "").replace(',', '.');
		} else if (t.indexOf('.') != t.lastIndexOf('.') || t.matches("\\d{1,3}(\\.\\d{3})+")) {
			t = t.replace(".", "");
		}
		try {
			return Double.parseDouble(t);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** „Du hast 1.234 $ erhalten“, beim /sell-Menü auch „… für 1.234 $ verkauft“. */
	private static final Pattern RECEIVED = Pattern.compile(
			"(?i)(?:du hast\\s+([\\d.,]+)\\s*\\$\\s+erhalten|f(?:ü|ue)r\\s+([\\d.,]+)\\s*\\$\\s+verkauft)");

	/** Betrag aus einer Marktplatz-/Verkaufs-Meldung, sonst null. */
	public static Double marketAmount(String line) {
		Matcher m = RECEIVED.matcher(line);
		if (!m.find()) {
			return null;
		}
		double v = money(m.group(1) != null ? m.group(1) : m.group(2));
		return v > 0 ? v : null;
	}

	private static final Pattern FULL = Pattern.compile(
			"Du hast (.+?) in der Größe (.+?) und der Seltenheit (.+?) gefangen", Pattern.CASE_INSENSITIVE);
	private static final Pattern NO_SIZE = Pattern.compile(
			"Du hast (.+?) (?:mit|in) der Seltenheit (.+?) gefangen", Pattern.CASE_INSENSITIVE);

	/** Fang-Meldung → {Fisch, Größe ("" wenn keine), Seltenheit}, sonst null. */
	public static String[] fishCatch(String line) {
		Matcher m = FULL.matcher(line);
		if (m.find()) {
			return new String[]{m.group(1).strip(), m.group(2).strip(), m.group(3).strip()};
		}
		Matcher n = NO_SIZE.matcher(line);
		if (n.find()) {
			return new String[]{n.group(1).strip(), "", n.group(2).strip()};
		}
		return null;
	}
}
