package de.loothub.client.progress;

import net.minecraft.network.chat.Component;

import de.loothub.client.Fmt;

/** Texte zu Zählern: Namen, Quest-Ziele, Werte in passender Einheit. */
final class Texts {
	private Texts() {
	}

	/** Name eines Zählers („Abgebaute Blöcke“). */
	static Component stat(String stat) {
		return Component.translatable("loothub.lh.stat." + stat);
	}

	/** Quest-Ziel („300 Blöcke abbauen“). */
	static Component goal(String stat, long goal) {
		return Component.translatable("loothub.lh.goal." + stat, value(stat, goal));
	}

	/** Wert in der Einheit des Zählers (Spielzeit in Stunden/Minuten, Geld mit $, sonst Zahl). */
	static String value(String stat, long v) {
		return switch (stat) {
			case "play" -> v >= 600 ? Fmt.whole(v / 60) + " h" : Fmt.whole(v) + " min";
			case "jobmoney" -> Fmt.compact(v) + " $";
			default -> v >= 100_000 ? Fmt.compact(v) : Fmt.whole(v);
		};
	}

	/** Epischer Name eines Erfolgs („Bezwinger des Gesteins“). */
	static Component achName(String stat) {
		return Component.translatable("loothub.lh.ach.name." + stat);
	}

	private static final String[] RANKS_30 = {"bronze", "silver", "gold", "emerald", "diamond", "netherite"};
	private static final String[] RANKS_15 = {"bronze", "silver", "gold", "diamond", "netherite"};
	private static final String[] ROMAN = {"I", "II", "III", "IV", "V"};

	/** Schlüssel des Rangs einer Stufe (1 … max): Bronze, Silber, Gold, (Smaragd,) Diamant, Netherit, ganz oben Legendär. */
	static String rankKey(int tier, int max) {
		if (tier <= 0) {
			return "none";
		}
		if (tier >= max) {
			return "legendary";
		}
		String[] ranks = max >= 30 ? RANKS_30 : RANKS_15;
		int per = max >= 30 ? 5 : 3;
		return ranks[Math.min(ranks.length - 1, (tier - 1) / per)];
	}

	/** Rang als Text („Gold III“, „Legendär“). */
	static Component rank(int tier, int max) {
		String key = rankKey(tier, max);
		Component name = Component.translatable("loothub.lh.rank." + key);
		if (key.equals("none") || key.equals("legendary")) {
			return name;
		}
		int per = max >= 30 ? 5 : 3;
		return name.copy().append(" " + ROMAN[(tier - 1) % per]);
	}

	/** Farbe eines Rangs; „Legendär“ schimmert zwischen Gold und Orange. */
	static int rankColor(int tier, int max) {
		return switch (rankKey(tier, max)) {
			case "bronze" -> 0xFFD08A4E;
			case "silver" -> 0xFFD4DAE0;
			case "gold" -> 0xFFF2C14E;
			case "emerald" -> 0xFF3DDC84;
			case "diamond" -> 0xFF5CE1E6;
			case "netherite" -> 0xFFB48CC8;
			case "legendary" -> {
				double t = (Math.sin(System.currentTimeMillis() / 300.0) + 1) / 2;
				int g = (int) (0x8C + (0xD8 - 0x8C) * t);
				yield 0xFFFF0000 | (g << 8) | 0x30;
			}
			default -> 0xFF7A6A6C;
		};
	}

	/** Restzeit grob („2 h 13 min“, „3 Tage“). */
	static String left(long ms) {
		long min = Math.max(0, ms / 60_000L);
		if (min >= 48 * 60) {
			return Component.translatable("loothub.lh.left.days", min / (24 * 60)).getString();
		}
		if (min >= 60) {
			return Component.translatable("loothub.lh.left.hours", min / 60, min % 60).getString();
		}
		return Component.translatable("loothub.lh.left.minutes", min).getString();
	}
}
