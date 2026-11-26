package de.loothub.client.util;

import java.util.Locale;

/**
 * Suchlogik ohne Minecraft (testbar): Umlaute/Groß-Klein egal, Wortanfänge, Begriffe in Deutsch/Englisch, ein
 * Tippfehler bei längeren Wörtern. Alle Wörter der Suche müssen passen.
 */
public final class Search {
	private Search() {
	}

	/** Begriffe, die dasselbe meinen (Deutsch/Englisch/Umgangssprache). */
	private static final String[][] SYNONYMS = {
			{"rucksack", "backpack", "anglerrucksack"}, {"angeln", "angel", "fishing", "fish", "fisch", "fang"},
			{"ausblenden", "verstecken", "hide", "unsichtbar"}, {"ton", "sound", "klang", "lautstaerke", "volume"},
			{"farbe", "color", "colour"}, {"inventar", "inventory", "inv"}, {"markt", "market", "marktplatz", "sell", "verkauf"},
			{"auktion", "auktionshaus", "ah", "auction"}, {"haustier", "pet", "tier"}, {"minime", "mini", "mini me"},
			{"chat", "nachricht", "message", "nachrichten"}, {"hud", "anzeige", "overlay", "einblendung"},
			{"bau", "bauen", "build", "litematica", "schematic"}, {"job", "jobs", "beruf", "tracker"},
			{"warnung", "warn", "warning", "alarm"}, {"schild", "sign"}, {"dorfbewohner", "villager", "handel", "trade"},
			{"quest", "quests", "aufgabe"}, {"twitch", "stream", "streamer"}, {"umhang", "cape"}, {"fluegel", "wings"},
			{"essen", "food", "hunger", "saettigung", "saturation"}, {"werkzeug", "tool", "schutz", "protection"}};

	/** Klein, Umlaute als Grundbuchstabe + e, Farbcodes und Satzzeichen weg. */
	public static String fold(String s) {
		String t = (s == null ? "" : s).replaceAll("§.", "").toLowerCase(Locale.ROOT)
				.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
		return t.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
	}

	/** Passt die Suche {@code query} zum Text {@code text}? */
	public static boolean matches(String text, String query) {
		String hay = " " + fold(text) + " ";
		for (String word : fold(query).split(" ")) {
			if (!word.isEmpty() && !wordMatches(hay, word)) {
				return false;
			}
		}
		return true;
	}

	/** {@code hay} muss schon gefaltet sein und mit Leerzeichen beginnen und enden. */
	public static boolean wordMatches(String hay, String word) {
		if (hay.contains(" " + word) || (word.length() >= 4 && hay.contains(word))) {
			return true; // Wortanfang, bei längeren Suchwörtern auch mitten im Wort
		}
		for (String[] group : SYNONYMS) {
			for (String g : group) {
				if (g.startsWith(word) && word.length() >= 2 || word.equals(g)) {
					for (String other : group) {
						if (hay.contains(" " + other)) {
							return true;
						}
					}
				}
			}
		}
		if (word.length() >= 5) {
			for (String w : hay.split(" ")) {
				if (w.length() < 4) {
					continue;
				}
				// ganzes Wort oder Wortanfang (z. B. „rucksak“ → „Rucksacks“) mit einem Tippfehler
				for (int k = word.length() - 1; k <= word.length() + 1; k++) {
					if (k <= w.length() && oneEdit(w.substring(0, k), word)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Unterscheiden sich a und b um höchstens einen Buchstaben (ersetzt, eingefügt oder entfernt)? */
	public static boolean oneEdit(String a, String b) {
		int i = 0, j = 0, edits = 0;
		while (i < a.length() && j < b.length()) {
			if (a.charAt(i) == b.charAt(j)) {
				i++;
				j++;
				continue;
			}
			if (++edits > 1) {
				return false;
			}
			if (a.length() > b.length()) {
				i++;
			} else if (a.length() < b.length()) {
				j++;
			} else {
				i++;
				j++;
			}
		}
		return edits + (a.length() - i) + (b.length() - j) <= 1;
	}
}
