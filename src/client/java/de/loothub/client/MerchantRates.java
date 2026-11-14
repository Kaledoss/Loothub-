package de.loothub.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.loothub.Loothub;

/**
 * Kurse des Wertstoff-/Rohstoffhändlers aus der OPSUCHT-API ({@code GET /merchant/rates}):
 * welche Items der Händler annimmt und wie viel es dafür gibt. Zwischengespeichert für 60 Sekunden.
 */
public final class MerchantRates {
	private static final long REFRESH_MS = 60_000;
	private static final Pattern CUSTOM_NAME = Pattern.compile("text:\\s*\"([^\"]+)\"");

	/** Ein Eintrag: {@code itemId} ohne Zusatzdaten, {@code name} (eigener Name falls vorhanden), Ziel-Währung, Kurse. */
	public record Rate(String itemId, String name, String target, double base, double rate) {
		/** Abweichung des aktuellen Kurses vom Grundkurs in Prozent. */
		public double changePercent() {
			return base > 0 && !Double.isNaN(rate) ? (rate - base) / base * 100.0 : 0;
		}

		public String targetLabel() {
			String t = target.toLowerCase(Locale.ROOT);
			if (t.contains("shard")) {
				return "OPShards";
			}
			if (t.contains("coin")) {
				return "RedCoins";
			}
			return target;
		}
	}

	private static volatile List<Rate> rates = List.of();
	private static volatile long lastFetch;
	private static volatile boolean loading;
	private static volatile boolean failed;
	private static volatile int version;

	private MerchantRates() {
	}

	public static List<Rate> rates() {
		return rates;
	}

	public static boolean isLoading() {
		return loading;
	}

	public static boolean failed() {
		return failed;
	}

	public static long lastFetch() {
		return lastFetch;
	}

	public static int version() {
		return version;
	}

	public static synchronized void refreshIfStale() {
		if (loading || System.currentTimeMillis() - lastFetch < REFRESH_MS) {
			return;
		}
		loading = true;
		OpsuchtApi.get("/merchant/rates").whenComplete((json, error) -> {
			try {
				if (error != null) {
					throw error instanceof RuntimeException r ? r : new IllegalStateException(error);
				}
				rates = parse(json);
				recordDay(rates);
				failed = false;
			} catch (RuntimeException e) {
				Loothub.LOGGER.warn("Händler-Kurse konnten nicht geladen werden", e);
				failed = true;
			} finally {
				lastFetch = System.currentTimeMillis();
				loading = false;
				version++;
			}
		});
	}

	// ------------------------------------------------------------------ Tageswechsel

	/** Datum → angenommene Items an diesem Tag (Schlüssel aus {@link #key}). */
	private static java.util.Map<String, java.util.Set<String>> days;
	private static volatile java.util.Set<String> newToday = java.util.Set.of();

	private static String key(Rate r) {
		return r.itemId() + "|" + (r.name() == null ? "" : r.name()) + "|" + r.target();
	}

	private static java.nio.file.Path daysFile() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("loothub-merchant-days.json");
	}

	private static synchronized void recordDay(List<Rate> list) {
		com.google.gson.Gson gson = new com.google.gson.Gson();
		if (days == null) {
			days = new java.util.TreeMap<>();
			try {
				java.nio.file.Path p = daysFile();
				if (java.nio.file.Files.exists(p)) {
					java.util.Map<String, java.util.Set<String>> m = gson.fromJson(java.nio.file.Files.readString(p),
							new com.google.gson.reflect.TypeToken<java.util.Map<String, java.util.Set<String>>>() { }.getType());
					if (m != null) {
						days.putAll(m);
					}
				}
			} catch (java.io.IOException | RuntimeException e) {
				Loothub.LOGGER.warn("Händler-Verlauf nicht lesbar", e);
			}
		}
		java.time.LocalDate today = java.time.LocalDate.now();
		java.util.Set<String> keys = new java.util.TreeSet<>();
		for (Rate r : list) {
			keys.add(key(r));
		}
		days.put(today.toString(), keys);
		String cutoff = today.minusDays(14).toString();
		days.keySet().removeIf(d -> d.compareTo(cutoff) < 0);
		// Vergleich mit dem letzten Tag davor, an dem Daten geholt wurden
		java.util.Set<String> before = null;
		for (java.util.Map.Entry<String, java.util.Set<String>> e : days.entrySet()) {
			if (e.getKey().compareTo(today.toString()) < 0) {
				before = e.getValue();
			}
		}
		java.util.Set<String> fresh = new java.util.HashSet<>();
		if (before != null) {
			for (String k : keys) {
				if (!before.contains(k)) {
					fresh.add(k);
				}
			}
		}
		newToday = fresh;
		try {
			java.nio.file.Files.writeString(daysFile(), gson.toJson(days));
		} catch (java.io.IOException e) {
			Loothub.LOGGER.warn("Händler-Verlauf nicht speicherbar", e);
		}
	}

	private static List<Rate> parse(JsonElement json) {
		List<Rate> out = new ArrayList<>();
		if (json == null || !json.isJsonArray()) {
			return out;
		}
		for (JsonElement e : json.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			String source = OpsuchtApi.str(o, "source");
			if (source.isBlank()) {
				continue;
			}
			String id = source;
			int bracket = id.indexOf('[');
			if (bracket >= 0) {
				id = id.substring(0, bracket);
			}
			if (!id.contains(":")) {
				id = "minecraft:" + id;
			}
			String name = null;
			Matcher m = CUSTOM_NAME.matcher(source);
			while (m.find()) {
				if (!m.group(1).isBlank()) {
					name = m.group(1);
					break;
				}
			}
			out.add(new Rate(id.toLowerCase(Locale.ROOT), name, OpsuchtApi.str(o, "target"),
					OpsuchtApi.num(o, "base"), OpsuchtApi.num(o, "exchangeRate")));
		}
		out.sort(Comparator.comparing(Rate::target).thenComparing(r -> r.name() == null ? r.itemId() : r.name()));
		return List.copyOf(out);
	}
}
