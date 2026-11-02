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

	public static String time(long epochMillis) {
		return TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
	}
}
