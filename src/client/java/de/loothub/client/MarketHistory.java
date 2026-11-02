package de.loothub.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.loothub.Loothub;

/**
 * Preisverlauf eines Items vom OPSUCHT-Marktplatz.
 *
 * <p>Endpunkt: {@code GET /market/history/{MATERIAL}}<br>
 * Format: {@code {HOURLY|DAILY|WEEKLY|MONTHLY: [{avgPrice, minPrice, maxPrice, items, transactions, timestamp}]}}
 *
 * <p>Wird nur geladen, wenn das Item im Markt-Menü ausgewählt ist; pro Item höchstens alle 5 Minuten.
 */
public final class MarketHistory {
	private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;
	private static final long RETRY_AFTER_ERROR_MS = 60 * 1000;

	public enum Interval {
		HOURLY("loothub.interval.hourly", 48, true),
		DAILY("loothub.interval.daily", 30, false),
		WEEKLY("loothub.interval.weekly", 26, false),
		MONTHLY("loothub.interval.monthly", 12, false);

		private final String translationKey;
		private final int maxPoints;
		private final boolean showTime;

		Interval(String translationKey, int maxPoints, boolean showTime) {
			this.translationKey = translationKey;
			this.maxPoints = maxPoints;
			this.showTime = showTime;
		}

		public String translationKey() {
			return translationKey;
		}

		/** So viele der neuesten Werte werden im Diagramm angezeigt. */
		public int maxPoints() {
			return maxPoints;
		}

		/** Bei Stundenwerten Uhrzeit mit anzeigen, sonst nur das Datum. */
		public boolean showTime() {
			return showTime;
		}
	}

	/**
	 * Ein Zeitabschnitt. Preise sind NaN, wenn im Abschnitt nichts gehandelt wurde.
	 * {@code time} ist in Millisekunden (Epoch) oder -1, wenn der Zeitstempel unlesbar war.
	 */
	public record Point(long time, double avg, double min, double max, long items, long transactions) {
		public boolean hasAvg() {
			return !Double.isNaN(avg) && avg > 0;
		}

		public boolean hasRange() {
			return !Double.isNaN(min) && !Double.isNaN(max) && max > 0;
		}
	}

	private record State(Map<Interval, List<Point>> data, long fetchedAt, long attemptAt, String error, boolean loading) {
	}

	private static final Map<String, State> CACHE = new ConcurrentHashMap<>();

	private MarketHistory() {
	}

	/** Startet das Laden im Hintergrund, falls nötig. Darf jeden Frame aufgerufen werden. */
	public static void request(String material) {
		long now = System.currentTimeMillis();
		State old = CACHE.get(material);
		if (old != null) {
			if (old.loading()) {
				return;
			}
			long wait = old.error() != null ? RETRY_AFTER_ERROR_MS : REFRESH_INTERVAL_MS;
			if (now - old.attemptAt() < wait) {
				return;
			}
		}
		Map<Interval, List<Point>> previous = old == null ? null : old.data();
		long previousFetch = old == null ? 0 : old.fetchedAt();
		CACHE.put(material, new State(previous, previousFetch, now, null, true));

		String encoded = URLEncoder.encode(material.toUpperCase(Locale.ROOT), StandardCharsets.UTF_8);
		OpsuchtApi.get("/market/history/" + encoded)
				.thenApply(MarketHistory::parse)
				.whenComplete((result, error) -> {
					if (error != null) {
						Loothub.LOGGER.warn("Preisverlauf für {} nicht ladbar: {}", material, error.toString());
						CACHE.put(material, new State(previous, previousFetch, now, error.getClass().getSimpleName(), false));
					} else {
						CACHE.put(material, new State(result, System.currentTimeMillis(), now, null, false));
					}
				});
	}

	/** Verlauf oder null, falls (noch) keine Daten vorliegen. */
	public static List<Point> get(String material, Interval interval) {
		State s = CACHE.get(material);
		if (s == null || s.data() == null) {
			return null;
		}
		return s.data().getOrDefault(interval, List.of());
	}

	public static boolean isLoading(String material) {
		State s = CACHE.get(material);
		return s != null && s.loading();
	}

	public static boolean hasError(String material) {
		State s = CACHE.get(material);
		return s != null && s.error() != null && s.data() == null;
	}

	private static Map<Interval, List<Point>> parse(JsonElement json) {
		Map<Interval, List<Point>> out = new HashMap<>();
		if (!json.isJsonObject()) {
			return out;
		}
		for (Interval interval : Interval.values()) {
			JsonElement arr = json.getAsJsonObject().get(interval.name());
			List<Point> points = new ArrayList<>();
			if (arr != null && arr.isJsonArray()) {
				for (JsonElement el : arr.getAsJsonArray()) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject o = el.getAsJsonObject();
					points.add(new Point(
							parseTime(o.get("timestamp")),
							OpsuchtApi.num(o, "avgPrice"),
							OpsuchtApi.num(o, "minPrice"),
							OpsuchtApi.num(o, "maxPrice"),
							toLong(OpsuchtApi.num(o, "items")),
							toLong(OpsuchtApi.num(o, "transactions"))));
				}
			}
			// Älteste zuerst, damit das Diagramm von links nach rechts läuft
			if (points.stream().allMatch(p -> p.time() >= 0)) {
				points.sort((a, b) -> Long.compare(a.time(), b.time()));
			}
			out.put(interval, points);
		}
		return out;
	}

	private static long toLong(double v) {
		return Double.isNaN(v) ? 0 : (long) v;
	}

	/** Akzeptiert ISO-Zeit mit oder ohne Zeitzone sowie Unix-Zeit in Sekunden oder Millisekunden. */
	private static long parseTime(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return -1;
		}
		try {
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
				long v = e.getAsLong();
				return v < 100_000_000_000L ? v * 1000 : v;
			}
			String s = e.getAsString().trim();
			try {
				return OffsetDateTime.parse(s).toInstant().toEpochMilli();
			} catch (RuntimeException ignored) {
			}
			try {
				return Instant.parse(s).toEpochMilli();
			} catch (RuntimeException ignored) {
			}
			return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		} catch (RuntimeException ex) {
			return -1;
		}
	}
}
