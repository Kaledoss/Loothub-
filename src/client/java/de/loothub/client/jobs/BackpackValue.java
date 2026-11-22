package de.loothub.client.jobs;

import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import de.loothub.Loothub;
import de.loothub.client.cosmetics.CosmeticsServer;

/**
 * Wert des Anglerrucksacks (OPSUCHT): Beim Öffnen des Fanglagers liest die Mod alle Seiten mit (Fisch, Größe,
 * Seltenheit, Anzahl). Neue Fänge, solange der Rucksack in der Nebenhand ist, kommen dazu; ein Verkauf leert die Liste.
 * Die Preise kommen vom Loothub-Server (gepflegt in einer Google-Tabelle), dazu der Angler-Prestige-Bonus.
 */
public final class BackpackValue {
	/** Ein Stapel im Fanglager. */
	static final class Stack {
		String fish = "", size = "", rarity = "";
		int count;

		Stack() {
		}

		Stack(String fish, String size, String rarity, int count) {
			this.fish = fish;
			this.size = size;
			this.rarity = rarity;
			this.count = count;
		}
	}

	/** Gespeicherter Stand: Seiten des Fanglagers und Fänge seit dem letzten Öffnen. */
	static final class Data {
		Map<String, List<Stack>> pages = new LinkedHashMap<>();
		List<Stack> pending = new ArrayList<>();
		long seen;
	}

	/** Bonus je Prestige-Stufe. */
	public static final double BONUS_PER_PRESTIGE = 0.25;
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final long PRICE_EVERY = 10 * 60_000L;

	private static Data data;
	/** "fisch|größe|seltenheit" → Preis (leere Teile = gilt für alle). */
	private static volatile Map<String, Double> prices = Map.of();
	private static long pricesAt;
	private static boolean loading;
	private static int tick;

	private BackpackValue() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (++tick % 10 != 0) {
				return;
			}
			if (mc.screen instanceof AbstractContainerScreen<?> cs && cs.getTitle().getString().contains("Fanglager")) {
				readPage(cs);
			}
			if (System.currentTimeMillis() - pricesAt > PRICE_EVERY && !loading && wearing()) {
				loadPrices();
			}
		});
		// Verkauf im Rucksack: Geld-Nachricht, während Rucksack oder Fanglager offen ist → leer
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			Minecraft mc = Minecraft.getInstance();
			if (overlay || mc.screen == null) {
				return;
			}
			String title = mc.screen.getTitle().getString().toLowerCase(Locale.ROOT);
			String text = message.getString().toLowerCase(Locale.ROOT);
			if ((title.contains("fanglager") || title.contains("rucksack")) && text.contains("$")
					&& (text.contains("erhalten") || text.contains("verkauft"))) {
				Data d = data();
				d.pages.clear();
				d.pending.clear();
				save();
			}
		});
	}

	/** Rucksack in der Nebenhand? */
	public static boolean wearing() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.getOffhandItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains("rucksack");
	}

	/** Neuer Fang (aus dem Angel-Verlauf): zählt dazu, wenn der Rucksack in der Nebenhand ist. */
	static void onCatch(String fish, String size, String rarity) {
		if (!wearing()) {
			return;
		}
		Data d = data();
		d.pending.add(new Stack(fish.strip(), size.strip(), rarity.strip(), 1));
		save();
	}

	private static void readPage(AbstractContainerScreen<?> cs) {
		var slots = cs.getMenu().slots;
		if (slots.size() < 54) {
			return;
		}
		List<Stack> page = new ArrayList<>();
		for (int i = 0; i < 45; i++) {
			Stack s = parse(slots.get(i).getItem());
			if (s != null) {
				page.add(s);
			}
		}
		// Seite an den Pfeilen erkennen: ohne „zurück“ = erste, ohne „weiter“ = letzte, sonst Mitte
		boolean back = hasItem(slots.get(48)), next = hasItem(slots.get(50));
		String key = !back ? "first" : next ? "middle" : "last";
		Data d = data();
		List<Stack> old = d.pages.get(key);
		if (old != null && same(old, page) && d.pending.isEmpty()) {
			return;
		}
		d.pages.put(key, page);
		d.pending.clear(); // was seitdem gefangen wurde, liegt jetzt sichtbar im Fanglager
		d.seen = System.currentTimeMillis();
		save();
	}

	private static boolean hasItem(Slot s) {
		return !s.getItem().isEmpty();
	}

	private static boolean same(List<Stack> a, List<Stack> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			Stack x = a.get(i), y = b.get(i);
			if (x.count != y.count || !x.fish.equals(y.fish) || !x.size.equals(y.size) || !x.rarity.equals(y.rarity)) {
				return false;
			}
		}
		return true;
	}

	/** Fisch aus dem Fanglager lesen: Name, „Größe: …“ und die Seltenheit (Zeile in Großbuchstaben). */
	private static Stack parse(ItemStack st) {
		if (st.isEmpty()) {
			return null;
		}
		ItemLore lore = st.get(DataComponents.LORE);
		if (lore == null) {
			return null;
		}
		String size = "", rarity = "";
		boolean fishy = false;
		for (Component line : lore.lines()) {
			String t = line.getString().strip();
			String low = t.toLowerCase(Locale.ROOT);
			if (low.startsWith("kategorie:")) {
				fishy = true;
			} else if (low.startsWith("größe:")) {
				size = t.substring(t.indexOf(':') + 1).strip();
			} else if (FishingLog.known(low)) {
				rarity = t;
			}
		}
		if (!fishy) {
			return null;
		}
		return new Stack(st.getHoverName().getString().strip(), size, rarity, st.getCount());
	}

	// ------------------------------------------------------------------ Wert

	/** Ergebnis für die Anzeige: Wert in $, Fische ohne Preis, Fische insgesamt. */
	public record Value(double money, double shards, int unknown, int fish, boolean seen) {
	}

	public static Value value() {
		Data d = data();
		int prestige = Math.min(2, WidgetConfig.get().anglerPrestige); // OPSUCHT hat Prestige 1 und 2
		double mult = 1 + Math.max(0, prestige) * BONUS_PER_PRESTIGE;
		double sum = 0, shards = 0;
		int unknown = 0, fish = 0;
		List<Stack> all = new ArrayList<>();
		d.pages.values().forEach(all::addAll);
		all.addAll(d.pending);
		for (Stack s : all) {
			fish += s.count;
			Double p = price(s);
			if (p == null) {
				unknown += s.count;
			} else if (paysShards(s.rarity)) {
				shards += p * s.count; // Trödel und Schätze geben OPShards (ohne Prestige-Bonus)
			} else {
				sum += p * s.count * mult;
			}
		}
		return new Value(sum, shards, unknown, fish, !d.pages.isEmpty());
	}

	/**
	 * Wert eines Fangs im Inventar: {$, OPShards} oder null, wenn es kein OPSUCHT-Fang ist (dann zählt der Marktpreis).
	 * Fische mit Prestige-Bonus in $, Trödel und Schätze in OPShards; ohne bekannten Preis {0, 0}.
	 */
	public static double[] itemValue(ItemStack st) {
		Stack s = parse(st);
		if (s == null) {
			return null;
		}
		if (System.currentTimeMillis() - pricesAt > PRICE_EVERY && !loading) {
			loadPrices();
		}
		Double p = price(s);
		if (p == null) {
			return new double[]{0, 0};
		}
		if (paysShards(s.rarity)) {
			return new double[]{0, p * s.count};
		}
		double mult = 1 + Math.max(0, Math.min(2, WidgetConfig.get().anglerPrestige)) * BONUS_PER_PRESTIGE;
		return new double[]{p * s.count * mult, 0};
	}

	/** Alle zuletzt im Fanglager gesehenen Arten als {Fisch, Größe, Seltenheit} (für die gemeinsame Fischliste). */
	static List<String[]> seenKinds() {
		List<String[]> out = new ArrayList<>();
		for (List<Stack> page : data().pages.values()) {
			for (Stack s : page) {
				out.add(new String[]{s.fish, s.size, s.rarity});
			}
		}
		return out;
	}

	private static boolean paysShards(String rarity) {
		String r = rarity.strip().toLowerCase(Locale.ROOT);
		return r.equals("trödel") || r.equals("schatz");
	}

	private static Double price(Stack s) {
		Map<String, Double> m = prices;
		String f = s.fish.toLowerCase(Locale.ROOT), z = s.size.toLowerCase(Locale.ROOT), r = s.rarity.toLowerCase(Locale.ROOT);
		for (String k : new String[]{f + "|" + z + "|" + r, f + "|" + z + "|", f + "||" + r, f + "||"}) {
			Double p = m.get(k);
			if (p != null) {
				return p;
			}
		}
		return null;
	}

	private static void loadPrices() {
		loading = true;
		HttpRequest req = HttpRequest.newBuilder(URI.create(CosmeticsServer.BASE + "/fish")).timeout(Duration.ofSeconds(15))
				.header("User-Agent", "Loothub-Mod").GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((res, err) -> {
			loading = false;
			pricesAt = System.currentTimeMillis();
			if (err != null || res.statusCode() != 200) {
				return;
			}
			try {
				JsonElement e = JsonParser.parseString(res.body());
				JsonArray list = e.getAsJsonObject().getAsJsonArray("list");
				Map<String, Double> m = new HashMap<>();
				for (JsonElement row : list) {
					JsonArray a = row.getAsJsonArray();
					m.put(a.get(0).getAsString() + "|" + a.get(1).getAsString() + "|" + a.get(2).getAsString(), a.get(3).getAsDouble());
				}
				prices = m;
			} catch (RuntimeException ex) {
				Loothub.LOGGER.debug("Fischpreise nicht lesbar", ex);
			}
		});
	}

	// ------------------------------------------------------------------ Speichern

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-backpack.json");
	}

	private static Data data() {
		if (data == null) {
			data = new Data();
			Path p = file();
			if (Files.exists(p)) {
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					Data d = GSON.fromJson(r, Data.class);
					if (d != null && d.pages != null && d.pending != null) {
						data = d;
					}
				} catch (Exception e) {
					Loothub.LOGGER.warn("Rucksack-Stand konnte nicht gelesen werden", e);
				}
			}
		}
		return data;
	}

	private static void save() {
		de.loothub.client.AsyncSave.write(file(), GSON.toJson(data()));
	}
}
