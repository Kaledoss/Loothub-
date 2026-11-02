package de.loothub.client;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Zahlen- und Datumsformat (deutsch: 1.234,5). */
public final class Fmt {
	private static final DecimalFormatSymbols DE = DecimalFormatSymbols.getInstance(Locale.GERMANY);
	private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.##", DE));
	private static final ThreadLocal<DecimalFormat> ONE = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.#", DE));
	private static final ThreadLocal<DecimalFormat> TWO = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00", DE));
	private static final ThreadLocal<DecimalFormat> WHOLE = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0", DE));
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yy");
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private Fmt() {
	}

	/** 1.234,56 $ */
	public static String money(double v) {
		return MONEY.get().format(v) + " $";
	}

	/** Kurz für Achsen: 950 · 12,3k · 1,2 Mio */
	public static String compact(double v) {
		double a = Math.abs(v);
		if (a >= 1e9) {
			return ONE.get().format(v / 1e9) + " Mrd";
		}
		if (a >= 1e6) {
			return ONE.get().format(v / 1e6) + " Mio";
		}
		if (a >= 1e4) {
			return ONE.get().format(v / 1e3) + "k";
		}
		return a >= 100 ? WHOLE.get().format(v) : MONEY.get().format(v);
	}

	public static String whole(long v) {
		return WHOLE.get().format(v);
	}

	public static String percent(double v) {
		return (v > 0 ? "+" : "") + ONE.get().format(v) + " %";
	}

	public static String date(long epochMillis, boolean withTime) {
		if (epochMillis < 0) {
			return "?";
		}
		return (withTime ? DATE_TIME : DATE).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
	}

	/** Dauer kurz: "< 1 Min.", "12 Min.", "1 Std. 5 Min.", "3 Std." */
	public static String duration(long ms) {
		long minutes = ms / 60_000;
		if (minutes < 1) {
			return "< 1 Min.";
		}
		if (minutes < 60) {
			return minutes + " Min.";
		}
		long hours = minutes / 60;
		long rest = minutes % 60;
		if (hours >= 100) {
			return "> 99 Std.";
		}
		return rest == 0 || hours >= 10 ? hours + " Std." : hours + " Std. " + rest + " Min.";
	}

	/** Lange Dauer: "45 Min.", "12 Std. 5 Min.", "3 T 4 Std." */
	public static String longDuration(long ms) {
		long minutes = ms / 60_000;
		if (minutes < 60) {
			return minutes + " Min.";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours + " Std. " + (minutes % 60) + " Min.";
		}
		return WHOLE.get().format(hours / 24) + " T " + (hours % 24) + " Std.";
	}

	/** Zahl mit höchstens einer Nachkommastelle, ohne Einheit: 12,4 */
	public static String compactPlain(double v) {
		return ONE.get().format(v);
	}

	public static String time(long epochMillis) {
		return TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
	}

	/** Genaue Zahl mit zwei Nachkommastellen: 1.378,95 */
	public static String exact(double v) {
		return TWO.get().format(v);
	}

	/** Uhrzeit-Dauer: 49:55 oder 1:02:07 */
	public static String clock(long ms) {
		long sec = Math.max(0, ms) / 1000;
		long h = sec / 3600;
		long m = (sec % 3600) / 60;
		long s = sec % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%02d:%02d", m, s);
	}
}
