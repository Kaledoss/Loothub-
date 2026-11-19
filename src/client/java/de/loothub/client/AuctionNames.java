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
	/** Einzelverkäufe je Name; ältere werden zu Tageswerten zusammengefasst (der Verlauf bleibt für immer). */
	private static final int MAX_SALES = 300;

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

	/** Zusammengefasste ältere Verkäufe je Name und Tag: Summe, Anzahl, kleinster, größter Preis. */
	static final class DaySum {
		double sum;
		int n;
		double min = Double.MAX_VALUE;
		double max;
	}

	/** Gesamtwerte seit Beginn der Aufzeichnung. */
	public record Total(int count, double avg, double min, double max, long since) {
	}

	private static Map<String, Map<String, DaySum>> older;
	private static volatile Map<String, Now> current = Map.of();

	// ------------------------------------------------------------------ Server (lückenloser Verlauf)

	/** Zusammenfassung vom Loothub-Server je Schlüssel. */
	private record Server(int n, double sum, double min, double max, long since, double lastP, long lastT,
			Map<String, double[]> days, Now offers) {
	}

	private static volatile Map<String, Server> server = Map.of();
	private static volatile long serverUpdated;
	private static long serverFetched;
	private static boolean serverLoading;
	private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
			.connectTimeout(java.time.Duration.ofSeconds(8)).build();

	/** Server-Daten frisch genug (Zeitplan lief in den letzten 20 Minuten)? Sonst gilt die eigene Mitschrift. */
	private static boolean serverFresh() {
		return System.currentTimeMillis() - serverUpdated < 20 * 60_000;
	}

	/** Alle 5 Minuten die Zusammenfassung vom Server holen (aus dem Client-Tick). */
	public static synchronized void refreshServer() {
		long now = System.currentTimeMillis();
		if (serverLoading || now - serverFetched < 5 * 60_000 || de.loothub.client.cosmetics.CosmeticsServer.BASE.isBlank()) {
			return;
		}
		serverLoading = true;
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(de.loothub.client.cosmetics.CosmeticsServer.BASE + "/ah"))
				.timeout(java.time.Duration.ofSeconds(20)).header("User-Agent", "Loothub-Mod").GET().build();
		HTTP.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
			try {
				if (err == null && res.statusCode() == 200) {
					parseServer(JsonParser.parseString(res.body()).getAsJsonObject());
				}
			} catch (RuntimeException e) {
				Loothub.LOGGER.debug("AH-Server nicht lesbar: {}", e.toString());
			} finally {
				synchronized (AuctionNames.class) {
					serverFetched = System.currentTimeMillis();
					serverLoading = false;
				}
			}
		});
	}

	private static void parseServer(JsonObject root) {
		JsonObject items = root.has("items") && root.get("items").isJsonObject() ? root.getAsJsonObject("items") : new JsonObject();
		Map<String, Server> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : items.entrySet()) {
			if (!e.getValue().isJsonObject()) {
				continue;
			}
			JsonObject o = e.getValue().getAsJsonObject();
			Map<String, double[]> days = new HashMap<>();
			if (o.has("d") && o.get("d").isJsonObject()) {
				for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("d").entrySet()) {
					if (d.getValue().isJsonArray() && d.getValue().getAsJsonArray().size() >= 2) {
						days.put(d.getKey(), new double[]{d.getValue().getAsJsonArray().get(0).getAsDouble(),
								d.getValue().getAsJsonArray().get(1).getAsDouble()});
					}
				}
			}
			Now offers = null;
			if (o.has("o") && o.get("o").isJsonArray() && o.getAsJsonArray("o").size() >= 2) {
				offers = new Now(o.getAsJsonArray("o").get(0).getAsDouble(), o.getAsJsonArray("o").get(1).getAsInt());
			}
			out.put(e.getKey(), new Server((int) OpsuchtApi.num(o, "n"), OpsuchtApi.num(o, "s"), OpsuchtApi.num(o, "mn"),
					OpsuchtApi.num(o, "mx"), (long) OpsuchtApi.num(o, "since"),
					o.has("lp") ? OpsuchtApi.num(o, "lp") : Double.NaN, o.has("lt") ? (long) OpsuchtApi.num(o, "lt") : 0L,
					days, offers));
		}
		server = out;
		serverUpdated = (long) OpsuchtApi.num(root, "updated");
	}

	/** Letzter Verkauf: {Preis, Zeitpunkt} oder null. */
	public static synchronized double[] last(String key) {
		if (key == null) {
			return null;
		}
		Server sv = serverFresh() ? server.get(key) : null;
		if (sv != null && sv.lastT() > 0) {
			return new double[]{sv.lastP(), sv.lastT()};
		}
		List<Sale> l = sales(key);
		return l.isEmpty() ? null : new double[]{l.get(0).p, l.get(0).t};
	}
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
		return key(custom.getString(), net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
				starsOf(stack));
	}

	/** Name + Item-Art + Zustand-Sterne (falls das Item welche hat): „bohrer|netherite_pickaxe|1*“. */
	public static String key(String name, String material, int stars) {
		String k = key(name, material);
		return k == null || stars < 0 ? k : k + "|" + stars + "*";
	}

	/** Gefüllte Sterne in der „Zustand:“-Zeile (✯ voll, ✩ leer), oder -1 ohne Zustand. */
	public static int stars(Iterable<String> lines) {
		for (String line : lines) {
			if (line == null) {
				continue;
			}
			String plain = line.replaceAll("(?i)[§&][0-9a-fk-orx]", "");
			int i = plain.indexOf("Zustand");
			if (i < 0) {
				continue;
			}
			int n = 0;
			for (int j = i; j < plain.length(); j++) {
				char c = plain.charAt(j);
				if (c == '\u272F' || c == '\u2605') {
					n++;
				}
			}
			return n;
		}
		return -1;
	}

	/** Zustand-Sterne eines Items aus seiner Beschreibung, oder -1. */
	public static int starsOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return -1;
		}
		var lore = stack.get(DataComponents.LORE);
		if (lore == null) {
			return -1;
		}
		List<String> lines = new ArrayList<>();
		for (var c : lore.lines()) {
			lines.add(c.getString());
		}
		return stars(lines);
	}

	/** Zustand-Sterne aus der Beschreibung eines Auktions-Items der API. */
	private static int starsOf(JsonObject item) {
		JsonElement lore = item.get("lore");
		if (lore == null || lore.isJsonNull()) {
			return -1;
		}
		List<String> lines = new ArrayList<>();
		if (lore.isJsonArray()) {
			for (JsonElement l : lore.getAsJsonArray()) {
				lines.add(text(l));
			}
		} else {
			String t = lore.isJsonPrimitive() ? lore.getAsString() : lore.toString();
			try {
				JsonElement parsed = JsonParser.parseString(t);
				if (parsed.isJsonArray()) {
					for (JsonElement l : parsed.getAsJsonArray()) {
						lines.add(text(l));
					}
				} else {
					lines.add(t);
				}
			} catch (RuntimeException ex) {
				lines.add(t);
			}
		}
		return stars(lines);
	}

	/** Name + Item-Art („bohrer v3|diamond_pickaxe“): gleiche Namen auf anderen Items (z. B. umbenannte Fälschungen) zählen getrennt. */
	public static String key(String name, String material) {
		String n = key(name);
		if (n == null) {
			return null;
		}
		String m = material == null ? "" : material.toLowerCase(Locale.ROOT);
		int colon = m.indexOf(':');
		return n + "|" + (colon >= 0 ? m.substring(colon + 1) : m);
	}

	static String key(String name) {
		if (name == null) {
			return null;
		}
		String s = name.replaceAll("(?i)[§&][0-9a-fk-orx]", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
		return s.isEmpty() ? null : s;
	}

	public static Now now(String key) {
		if (key == null) {
			return null;
		}
		Server sv = serverFresh() ? server.get(key) : null;
		if (sv != null) {
			return sv.offers();
		}
		return current.get(key);
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

	/** Alle Verkäufe seit Beginn der Aufzeichnung (Einzelverkäufe + zusammengefasste Tage), oder null. */
	public static synchronized Total total(String key) {
		load();
		if (key == null) {
			return null;
		}
		Server sv = server.get(key);
		if (sv != null && sv.n() > 0) {
			// Server-Verlauf ist vollständiger als die eigene Mitschrift
			return new Total(sv.n(), sv.sum() / sv.n(), sv.min(), sv.max(), sv.since());
		}
		int n = 0;
		double sum = 0, min = Double.MAX_VALUE, max = 0;
		long since = Long.MAX_VALUE;
		for (Sale s : sales.getOrDefault(key, List.of())) {
			n++;
			sum += s.p;
			min = Math.min(min, s.p);
			max = Math.max(max, s.p);
			since = Math.min(since, s.t);
		}
		for (Map.Entry<String, DaySum> e : older.getOrDefault(key, Map.of()).entrySet()) {
			DaySum d = e.getValue();
			n += d.n;
			sum += d.sum;
			min = Math.min(min, d.min);
			max = Math.max(max, d.max);
			try {
				since = Math.min(since, java.time.LocalDate.parse(e.getKey()).atStartOfDay(java.time.ZoneId.systemDefault())
						.toInstant().toEpochMilli());
			} catch (RuntimeException ignored) {
				// kaputter Tag
			}
		}
		return n == 0 ? null : new Total(n, sum / n, min, max, since);
	}

	/** Verkäufe der letzten {@code days} Tage: Anzahl und Schnitt (Einzelverkäufe + zusammengefasste Tage), oder null. */
	public static synchronized Total window(String key, int days) {
		load();
		if (key == null) {
			return null;
		}
		Server sv = server.get(key);
		if (sv != null && sv.n() > 0) {
			String fromDayS = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(days).toString();
			int n = 0;
			double sum = 0;
			for (Map.Entry<String, double[]> d : sv.days().entrySet()) {
				if (d.getKey().compareTo(fromDayS) > 0) {
					n += (int) d.getValue()[0];
					sum += d.getValue()[1];
				}
			}
			return n == 0 ? null : new Total(n, sum / n, Double.NaN, Double.NaN, 0);
		}
		long from = System.currentTimeMillis() - days * 24L * 3600_000;
		String fromDay = java.time.LocalDate.now().minusDays(days).toString();
		int n = 0;
		double sum = 0, min = Double.MAX_VALUE, max = 0;
		for (Sale s : sales.getOrDefault(key, List.of())) {
			if (s.t >= from) {
				n++;
				sum += s.p;
				min = Math.min(min, s.p);
				max = Math.max(max, s.p);
			}
		}
		for (Map.Entry<String, DaySum> e : older.getOrDefault(key, Map.of()).entrySet()) {
			if (e.getKey().compareTo(fromDay) > 0) {
				DaySum d = e.getValue();
				n += d.n;
				sum += d.sum;
				min = Math.min(min, d.min);
				max = Math.max(max, d.max);
			}
		}
		return n == 0 ? null : new Total(n, sum / n, min, max, from);
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
			String name = key(itemName(item), OpsuchtApi.str(item, "material"), starsOf(item));
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
					// die ältesten nicht wegwerfen, sondern zu Tageswerten zusammenfassen
					List<Sale> drop = list.subList(0, list.size() - MAX_SALES);
					Map<String, DaySum> days = older.computeIfAbsent(l.name(), k -> new HashMap<>());
					for (Sale s : drop) {
						String day = java.time.Instant.ofEpochMilli(s.t).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
						DaySum d = days.computeIfAbsent(day, k -> new DaySum());
						d.sum += s.p;
						d.n++;
						d.min = Math.min(d.min, s.p);
						d.max = Math.max(d.max, s.p);
					}
					drop.clear();
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

	/** Gespeicherte Form: Einzelverkäufe und zusammengefasste Tage. */
	private static final class Store {
		Map<String, List<Sale>> sales;
		Map<String, Map<String, DaySum>> older;
	}

	private static synchronized void load() {
		if (sales != null) {
			return;
		}
		sales = new HashMap<>();
		older = new HashMap<>();
		Path p = file();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				JsonElement root = JsonParser.parseReader(r);
				if (root != null && root.isJsonObject() && root.getAsJsonObject().has("sales")) {
					Store st = GSON.fromJson(root, Store.class);
					if (st.sales != null) {
						sales.putAll(st.sales);
					}
					if (st.older != null) {
						older.putAll(st.older);
					}
				} else if (root != null && root.isJsonObject()) {
					// erste Fassung: nur Einzelverkäufe
					Map<String, List<Sale>> m = GSON.fromJson(root, new TypeToken<Map<String, List<Sale>>>() { }.getType());
					if (m != null) {
						sales.putAll(m);
					}
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.warn("AH-Namensliste nicht lesbar", e);
			}
		}
	}

	private static synchronized void save() {
		Store st = new Store();
		st.sales = sales;
		st.older = older;
		AsyncSave.write(file(), GSON.toJson(st));
	}
}
