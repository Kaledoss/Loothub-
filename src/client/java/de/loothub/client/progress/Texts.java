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
			case "play", "clean" -> v >= 600 ? Fmt.whole(v / 60) + " h" : Fmt.whole(v) + " min";
			case "jobmoney" -> Fmt.compact(v) + " $";
			default -> v >= 100_000 ? Fmt.compact(v) : Fmt.whole(v);
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
