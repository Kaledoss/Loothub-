package de.loothub.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Auktionshaus-Preise aus der OPSUCHT-API ({@code GET /auctions/active}): günstigster Sofortkauf pro Stück
 * je Item (nur Items ohne Verzauberung). Zusätzlich ein eigener Verlauf pro Tag seit Installation der Mod
 * in {@code config/loothub-ah-history.json}.
 */
public final class AuctionPrices {
	private static final long REFRESH_MS = 2 * 60_000;
	private static final Gson GSON = new GsonBuilder().create();

	/** Aktueller Stand: Item-ID → günstigster Sofortkauf pro Stück und Anzahl Angebote. */
	public record Now(double minPerUnit, int offers) {
	}

	/** Tageswerte: niedrigster Preis pro Stück und Summe/Anzahl für den Schnitt. */
	static final class Day {
		double min = Double.MAX_VALUE;
		double sum;
		int count;
	}

	private static volatile Map<String, Now> current = Map.of();
	private static volatile long lastFetch;
	private static volatile boolean loading;
	/** Datum → (Item-ID → Tageswerte) */
	private static Map<String, Map<String, Day>> history;
	private static boolean historyDirty;

	private AuctionPrices() {
	}

	public static Now get(String itemId) {
		refreshIfStale();
		return current.get(itemId);
	}

	public static synchronized void refreshIfStale() {
		if (loading || System.currentTimeMillis() - lastFetch < REFRESH_MS) {
			return;
		}
		loading = true;
		OpsuchtApi.get("/auctions/active").whenComplete((json, error) -> {
			try {
				if (error == null && json != null && json.isJsonArray()) {
					Map<String, Now> next = parse(json);
					current = next;
					record(next);
				}
			} catch (RuntimeException e) {
				Loothub.LOGGER.warn("Auktionshaus-Preise nicht lesbar: {}", e.toString());
			} finally {
				lastFetch = System.currentTimeMillis();
				loading = false;
			}
		});
	}

	private static Map<String, Now> parse(JsonElement json) {
		Map<String, double[]> acc = new HashMap<>(); // min, anzahl
		for (JsonElement e : json.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject a = e.getAsJsonObject();
			JsonObject item = a.has("item") && a.get("item").isJsonObject() ? a.getAsJsonObject("item") : null;
			if (item == null) {
				continue;
			}
			// Verzauberte Items haben keinen vergleichbaren Preis
			if (item.has("enchantments") && item.get("enchantments").isJsonObject()
					&& !item.getAsJsonObject("enchantments").keySet().isEmpty()) {
				continue;
			}
			String material = OpsuchtApi.str(item, "material");
			if (material.isBlank()) {
				continue;
			}
			double amount = Math.max(1, OpsuchtApi.num(item, "amount"));
			double price = OpsuchtApi.num(a, "instantBuyPrice");
			if (Double.isNaN(price) || price <= 0) {
				continue; // nur Sofortkauf ist ein fester Preis
			}
			String id = "minecraft:" + material.toLowerCase(Locale.ROOT);
			double perUnit = price / amount;
			double[] v = acc.computeIfAbsent(id, k -> new double[]{Double.MAX_VALUE, 0});
			v[0] = Math.min(v[0], perUnit);
			v[1]++;
		}
		Map<String, Now> out = new HashMap<>();
		acc.forEach((k, v) -> out.put(k, new Now(v[0], (int) v[1])));
		return out;
	}

	// ------------------------------------------------------------------ Verlauf

	private static Path historyFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-ah-history.json");
	}

	private static synchronized void loadHistory() {
		if (history != null) {
			return;
		}
		history = new TreeMap<>();
		Path p = historyFile();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				Map<String, Map<String, Day>> m = GSON.fromJson(r, new TypeToken<Map<String, Map<String, Day>>>() { }.getType());
				if (m != null) {
					history.putAll(m);
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.warn("AH-Verlauf nicht lesbar", e);
			}
		}
	}

	private static synchronized void record(Map<String, Now> now) {
		loadHistory();
		Map<String, Day> today = history.computeIfAbsent(LocalDate.now().toString(), d -> new HashMap<>());
		now.forEach((id, n) -> {
			Day d = today.computeIfAbsent(id, k -> new Day());
			d.min = Math.min(d.min, n.minPerUnit());
			d.sum += n.minPerUnit();
			d.count++;
		});
		// höchstens ein Jahr aufheben
		String cutoff = LocalDate.now().minusDays(366).toString();
		history.keySet().removeIf(day -> day.compareTo(cutoff) < 0);
		historyDirty = true;
		saveHistory();
	}

	private static synchronized void saveHistory() {
		if (!historyDirty) {
			return;
		}
		try (Writer w = Files.newBufferedWriter(historyFile(), StandardCharsets.UTF_8)) {
			GSON.toJson(history, w);
			historyDirty = false;
		} catch (IOException e) {
			Loothub.LOGGER.warn("AH-Verlauf nicht speicherbar", e);
		}
	}

	/** Durchschnittlicher günstigster Preis pro Stück der letzten {@code days} Tage (ohne heute), oder NaN. */
	public static synchronized double average(String itemId, int days) {
		loadHistory();
		LocalDate today = LocalDate.now();
		double sum = 0;
		int n = 0;
		for (int i = 1; i <= days; i++) {
			Map<String, Day> m = history.get(today.minusDays(i).toString());
			Day d = m == null ? null : m.get(itemId);
			if (d != null && d.count > 0) {
				sum += d.sum / d.count;
				n++;
			}
		}
		return n == 0 ? Double.NaN : sum / n;
	}

	/** Seit wann es Verlaufsdaten gibt, oder null. */
	public static synchronized LocalDate firstDay() {
		loadHistory();
		return history.isEmpty() ? null : LocalDate.parse(((TreeMap<String, Map<String, Day>>) history).firstKey());
	}
}
