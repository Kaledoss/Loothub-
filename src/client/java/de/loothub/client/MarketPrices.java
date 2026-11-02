package de.loothub.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.loothub.Loothub;

/**
 * Lädt die aktuellen Marktpreise von der öffentlichen OPSUCHT-API und hält sie im Speicher.
 *
 * <p>Endpunkt: {@code GET /market/prices}<br>
 * Format: {@code {Kategorie: {MATERIAL: [{orderSide: "BUY"|"SELL", activeOrders, price}]}}}
 *
 * <p>Die API cached selbst 60 Sekunden. Wir fragen höchstens alle 5 Minuten neu an, und nur dann,
 * wenn gerade ein Tooltip oder das Markt-Menü angezeigt wird – keine Dauerabfrage im Hintergrund.
 */
public final class MarketPrices {
	private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;
	private static final long RETRY_AFTER_ERROR_MS = 60 * 1000;

	/** Eine Seite des Marktes. {@code price} ist NaN, wenn es keine aktiven Aufträge gibt. */
	public record Side(double price, int activeOrders) {
		public boolean available() {
			return activeOrders > 0 && price > 0 && !Double.isNaN(price);
		}
	}

	/** Kaufaufträge (BUY) und Verkaufsangebote (SELL) eines Items. */
	public record Entry(Side buy, Side sell) {
	}

	/** Ein Item am Marktplatz. {@code material} ist der API-Name (z. B. DIAMOND_BLOCK), {@code itemId} die Minecraft-ID. */
	public record Listing(String material, String itemId, String category, Entry entry) {
	}

	private static final AtomicBoolean LOADING = new AtomicBoolean(false);
	private static volatile Map<String, Entry> byItemId = Map.of();
	private static volatile List<Listing> listings = List.of();
	private static volatile int version = 0;
	private static volatile long lastSuccess = 0;
	private static volatile long lastAttempt = 0;
	private static volatile boolean lastAttemptFailed = false;

	private MarketPrices() {
	}

	/** Item-ID wie {@code minecraft:diamond}. Liefert null, wenn das Item nicht am Markt gehandelt wird. */
	public static Entry get(String itemId) {
		return byItemId.get(itemId);
	}

	public static List<Listing> listings() {
		return listings;
	}

	/** Wird bei jeder erfolgreichen Aktualisierung erhöht – so merkt das Menü, dass neue Daten da sind. */
	public static int version() {
		return version;
	}

	public static boolean hasData() {
		return lastSuccess > 0;
	}

	public static boolean isLoading() {
		return LOADING.get();
	}

	public static boolean lastAttemptFailed() {
		return lastAttemptFailed;
	}

	public static long lastUpdate() {
		return lastSuccess;
	}

	/** Startet eine Abfrage im Hintergrund, falls die Daten veraltet sind. Kehrt sofort zurück. */
	public static void refreshIfStale() {
		long now = System.currentTimeMillis();
		long wait = lastAttemptFailed ? RETRY_AFTER_ERROR_MS : REFRESH_INTERVAL_MS;
		if (now - lastAttempt < wait || !LOADING.compareAndSet(false, true)) {
			return;
		}
		lastAttempt = now;

		OpsuchtApi.get("/market/prices")
				.thenApply(MarketPrices::parse)
				.whenComplete((result, error) -> {
					if (error != null) {
						lastAttemptFailed = true;
						Loothub.LOGGER.warn("Marktpreise konnten nicht geladen werden: {}", error.toString());
					} else {
						Map<String, Entry> map = new HashMap<>();
						for (Listing l : result) {
							map.put(l.itemId(), l.entry());
						}
						byItemId = map;
						listings = List.copyOf(result);
						lastSuccess = System.currentTimeMillis();
						lastAttemptFailed = false;
						version++;
						Loothub.LOGGER.info("Marktpreise aktualisiert ({} Items)", result.size());
					}
					LOADING.set(false);
				});
	}

	private static List<Listing> parse(JsonElement json) {
		List<Listing> out = new ArrayList<>();
		if (!json.isJsonObject()) {
			return out;
		}
		for (Map.Entry<String, JsonElement> category : json.getAsJsonObject().entrySet()) {
			if (!category.getValue().isJsonObject()) {
				continue;
			}
			for (Map.Entry<String, JsonElement> material : category.getValue().getAsJsonObject().entrySet()) {
				String itemId = toItemId(material.getKey());
				if (itemId == null || !material.getValue().isJsonArray()) {
					continue;
				}
				Side buy = new Side(Double.NaN, 0);
				Side sell = new Side(Double.NaN, 0);
				for (JsonElement el : material.getValue().getAsJsonArray()) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject o = el.getAsJsonObject();
					Side side = new Side(OpsuchtApi.num(o, "price"), (int) OpsuchtApi.num(o, "activeOrders"));
					String orderSide = OpsuchtApi.str(o, "orderSide");
					if ("BUY".equalsIgnoreCase(orderSide)) {
						buy = side;
					} else if ("SELL".equalsIgnoreCase(orderSide)) {
						sell = side;
					}
				}
				out.add(new Listing(material.getKey(), itemId, category.getKey(), new Entry(buy, sell)));
			}
		}
		return out;
	}

	/** "DIAMOND_BLOCK" -> "minecraft:diamond_block". Sonderitems (mit Zusatzdaten in Klammern) werden ignoriert. */
	private static String toItemId(String material) {
		if (material == null || material.isBlank() || material.contains("[") || material.contains("{")) {
			return null;
		}
		String id = material.trim().toLowerCase(Locale.ROOT);
		return id.contains(":") ? id : "minecraft:" + id;
	}
}
