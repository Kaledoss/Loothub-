package de.loothub.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import de.loothub.Loothub;

/**
 * Auktionshaus nach Item-Namen statt nach Item-Art: OP-Items und Sonder-Items heißen alle „Diamantschwert“ o. Ä.,
 * unterscheiden sich aber im Namen („OP Schwert“). Je Name: aktuelle Sofortkauf-Angebote und eine Liste der
 * Verkäufe – ein Angebot, das vor seinem Ende verschwindet, gilt als verkauft. Gespeichert in
 * {@code config/loothub-ah-namen.json}.
 */
public final class AuctionNames {
	private static final Gson GSON = new GsonBuilder().create();
	private static final int MAX_SALES = 60;

	/** Aktuell: Name → günstigster Sofortkauf pro Stück, Anzahl Angebote. */
	public record Now(double minPerUnit, int offers) {
	}

	/** Ein Verkauf: Preis pro Stück, Zeitpunkt (ms). */
	static final class Sale {
		double p;
		long t;

		Sale(double p, long t) {
			this.p = p;
			this.t = t;
		}
	}

	/** Ein Angebot aus der letzten Abfrage. */
	private record Listing(String name, double perUnit, long endsAt) {
	}

	private static volatile Map<String, Now> current = Map.of();
	private static Map<String, Listing> lastListings = Map.of();
	private static Map<String, List<Sale>> sales;
	private static boolean loggedSample;

	private AuctionNames() {
	}

	/** Für den Tooltip: Name eines Items (ohne Farben), klein geschrieben, oder null ohne eigenen Namen. */
	public static String keyOf(ItemStack stack) {
		var custom = stack.get(DataComponents.CUSTOM_NAME);
		if (custom == null) {
			return null;
		}
		return key(custom.getString());
	}

	static String key(String name) {
		if (name == null) {
			return null;
		}
		String s = name.replaceAll("(?i)[§&][0-9a-fk-orx]", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
		return s.isEmpty() ? null : s;
	}

	public static Now now(String key) {
		return key == null ? null : current.get(key);
	}

	/** Verkäufe zu diesem Namen, neueste zuerst. */
	public static synchronized List<Sale> sales(String key) {
		load();
		List<Sale> l = key == null ? null : sales.get(key);
		if (l == null) {
			return List.of();
		}
		List<Sale> out = new ArrayList<>(l);
		out.sort((a, b) -> Long.compare(b.t, a.t));
		return out;
	}

	public static double price(Sale s) {
		return s.p;
	}

	public static long time(Sale s) {
		return s.t;
	}

	/** Neue Abfrage des Auktionshauses verarbeiten (aus {@link AuctionPrices}). */
	static synchronized void update(JsonElement json) {
		load();
		long now = System.currentTimeMillis();
		Map<String, double[]> acc = new HashMap<>();
		Map<String, Listing> listings = new HashMap<>();
		for (JsonElement e : json.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject a = e.getAsJsonObject();
			JsonObject item = a.has("item") && a.get("item").isJsonObject() ? a.getAsJsonObject("item") : null;
			if (item == null) {
				continue;
			}
			if (!loggedSample) {
				// einmal ein Beispiel ins Log, um die Felder der API zu sehen
				loggedSample = true;
				String sample = a.toString();
				Loothub.LOGGER.info("[Loothub-AH] Beispiel-Angebot: {}", sample.length() > 1500 ? sample.substring(0, 1500) : sample);
			}
			String name = key(itemName(item));
			if (name == null) {
				continue; // ohne eigenen Namen: das macht die Liste nach Item-Art
			}
			double amount = Math.max(1, OpsuchtApi.num(item, "amount"));
			double price = OpsuchtApi.num(a, "instantBuyPrice");
			if (Double.isNaN(price) || price <= 0) {
				continue;
			}
			double perUnit = price / amount;
			double[] v = acc.computeIfAbsent(name, k -> new double[]{Double.MAX_VALUE, 0});
			v[0] = Math.min(v[0], perUnit);
			v[1]++;
			String id = firstString(a, "id", "uuid", "auctionId", "uid", "_id");
			if (!id.isEmpty()) {
				listings.put(id, new Listing(name, perUnit, endTime(a)));
			}
		}
		// Angebote, die vor ihrem Ende verschwunden sind, wurden verkauft (Sofortkauf)
		boolean changed = false;
		for (Map.Entry<String, Listing> old : lastListings.entrySet()) {
			if (listings.containsKey(old.getKey())) {
				continue;
			}
			Listing l = old.getValue();
			boolean beforeEnd = l.endsAt() <= 0 || l.endsAt() > now + 60_000;
			if (beforeEnd) {
				List<Sale> list = sales.computeIfAbsent(l.name(), k -> new ArrayList<>());
				list.add(new Sale(l.perUnit(), now));
				if (list.size() > MAX_SALES) {
					list.subList(0, list.size() - MAX_SALES).clear();
				}
				changed = true;
			}
		}
		lastListings = listings;
		Map<String, Now> next = new HashMap<>();
		acc.forEach((k, v) -> next.put(k, new Now(v[0], (int) v[1])));
		current = next;
		if (changed) {
			save();
		}
	}

	/** Name des Items aus der API: verschiedene mögliche Felder, Text oder Chat-Komponente. */
	private static String itemName(JsonObject item) {
		for (String k : new String[]{"displayName", "customName", "name", "display_name", "itemName"}) {
			JsonElement e = item.get(k);
			String s = text(e);
			if (s != null && !s.isBlank()) {
				return s;
			}
		}
		for (String k : new String[]{"meta", "components", "itemMeta"}) {
			if (item.has(k) && item.get(k).isJsonObject()) {
				String s = itemName(item.getAsJsonObject(k));
				if (s != null) {
					return s;
				}
				JsonElement cn = item.getAsJsonObject(k).get("minecraft:custom_name");
				s = text(cn);
				if (s != null && !s.isBlank()) {
					return s;
				}
			}
		}
		return null;
	}

	/** Text aus einem String (auch als JSON-Komponente) oder einem Komponenten-Objekt. */
	private static String text(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonPrimitive()) {
			String s = e.getAsString();
			String t = s.strip();
			if (t.startsWith("{") || t.startsWith("[")) {
				try {
					return text(JsonParser.parseString(t));
				} catch (RuntimeException ignored) {
					return s;
				}
			}
			return s;
		}
		if (e.isJsonArray()) {
			StringBuilder b = new StringBuilder();
			for (JsonElement x : e.getAsJsonArray()) {
				String s = text(x);
				if (s != null) {
					b.append(s);
				}
			}
			return b.toString();
		}
		JsonObject o = e.getAsJsonObject();
		StringBuilder b = new StringBuilder();
		if (o.has("text")) {
			b.append(text(o.get("text")));
		}
		if (o.has("extra")) {
			b.append(text(o.get("extra")));
		}
		return b.toString();
	}

	private static String firstString(JsonObject o, String... keys) {
		for (String k : keys) {
			JsonElement e = o.get(k);
			if (e != null && e.isJsonPrimitive()) {
				return e.getAsString();
			}
		}
		return "";
	}

	/** Ende des Angebots in ms (Zahl in s/ms oder ISO-Zeit), 0 = unbekannt. */
	private static long endTime(JsonObject a) {
		for (String k : new String[]{"endTime", "endsAt", "end", "expiresAt", "expiry", "endDate"}) {
			JsonElement e = a.get(k);
			if (e == null || !e.isJsonPrimitive()) {
				continue;
			}
			try {
				if (e.getAsJsonPrimitive().isNumber()) {
					long v = e.getAsLong();
					return v < 10_000_000_000L ? v * 1000 : v;
				}
				return java.time.Instant.parse(e.getAsString()).toEpochMilli();
			} catch (RuntimeException ignored) {
				// nächstes Feld
			}
		}
		return 0;
	}

	// ------------------------------------------------------------------ Speichern

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-ah-namen.json");
	}

	private static synchronized void load() {
		if (sales != null) {
			return;
		}
		sales = new HashMap<>();
		Path p = file();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				Map<String, List<Sale>> m = GSON.fromJson(r, new TypeToken<Map<String, List<Sale>>>() { }.getType());
				if (m != null) {
					sales.putAll(m);
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.warn("AH-Namensliste nicht lesbar", e);
			}
		}
	}

	private static synchronized void save() {
		AsyncSave.write(file(), GSON.toJson(sales));
	}
}
