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

	/** Ein Verkauf aus dem Rucksack. */
	static final class Sale {
		long t;
		double money, shards;
	}

	/** Gespeicherter Stand: Seiten des Fanglagers, Fänge seit dem letzten Öffnen und Verkäufe. */
	static final class Data {
		Map<String, List<Stack>> pages = new LinkedHashMap<>();
		List<Stack> pending = new ArrayList<>();
		List<Sale> sales = new ArrayList<>();
		long seen;
	}

	private static final java.util.regex.Pattern MONEY = java.util.regex.Pattern.compile("([0-9][0-9.,]*)\\s*\\$");
	private static final java.util.regex.Pattern SHARDS = java.util.regex.Pattern.compile("(?i)([0-9][0-9.,]*)\\s*(?:op)?shards");

	/** „1.234,56“, „94.750“ (deutscher Tausenderpunkt) oder „1234.5“ als Zahl. */
	private static double number(String s) {
		return de.loothub.client.util.Parse.money(s);
	}

	/** Verkaufserlös (in $ und OPShards) zwischen zwei Zeitpunkten. */
	public static double[] salesBetween(long from, long to) {
		double m = 0, sh = 0;
		for (Sale s : data().sales) {
			if (s.t >= from && s.t < to) {
				m += s.money;
				sh += s.shards;
			}
		}
		return new double[]{m, sh};
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
		de.loothub.client.util.Guard.tick("BackpackValue", mc -> {
			watchInventory(mc);
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
			if ((title.contains("fanglager") || title.contains("rucksack")) && (text.contains("$") || text.contains("shards"))
					&& (text.contains("erhalten") || text.contains("verkauft"))) {
				Data d = data();
				// Erlös merken (für die Angel-Übersicht)
				Sale s = new Sale();
				s.t = System.currentTimeMillis();
				java.util.regex.Matcher mm = MONEY.matcher(message.getString());
				if (mm.find()) {
					s.money = number(mm.group(1));
				}
				java.util.regex.Matcher sm = SHARDS.matcher(message.getString());
				if (sm.find()) {
					s.shards = number(sm.group(1));
				}
				if (s.money > 0 || s.shards > 0) {
					if (d.sales == null) {
						d.sales = new ArrayList<>();
					}
					d.sales.add(s);
					if (d.sales.size() > 20_000) {
						d.sales.subList(0, d.sales.size() - 20_000).clear();
					}
				}
				d.pages.clear();
				d.pending.clear();
				save();
			}
		});
	}

	/** Rucksack in der Nebenhand? */
	public static boolean wearing() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return false;
		}
		String name = mc.player.getOffhandItem().getHoverName().getString().toLowerCase(Locale.ROOT);
		return name.contains("rucksack") && !name.contains("talisman"); // nur der Anglerrucksack, nicht der Talisman-Rucksack
	}

	/**
	 * Neuer Fang (aus dem Angel-Verlauf), wenn der Rucksack in der Nebenhand ist: Fische zählen zum Rucksack, Vanilla nie
	 * (landet immer im Inventar). Trödel und Schätze nur, wenn sie nicht im Inventar auftauchen – das hängt von der
	 * OPSUCHT-Einstellung des Spielers ab.
	 */
	static void onCatch(String fish, String size, String rarity) {
		if (!wearing()) {
			return;
		}
		String r = rarity.strip().toLowerCase(Locale.ROOT);
		if (r.equals("vanilla")) {
			return; // Vanilla-Fänge landen immer im Inventar
		}
		Stack st = new Stack(fish.strip(), size.strip(), rarity.strip(), 1);
		if (!paysShards(r)) {
			Data d = data(); // Fische landen immer im Rucksack
			d.pending.add(st);
			save();
			return;
		}
		// Trödel und Schätze: je nach OPSUCHT-Einstellung im Rucksack oder im Inventar – kurz beobachten
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> CANDIDATES.add(new Candidate(st, invTick)));
	}

	private record Candidate(Stack stack, long at) {
	}

	private static final List<Candidate> CANDIDATES = new ArrayList<>();
	/** Anzahl je Item-Name im Inventar, je Tick (die letzten 2 Sekunden). */
	private static final java.util.ArrayDeque<Snap> INV_HISTORY = new java.util.ArrayDeque<>();
	/** Schon einem Fang zugeordnete Inventar-Zugänge je Name (damit ein Item nicht für zwei Fänge zählt). */
	private static final Map<String, Integer> CLAIMED = new HashMap<>();
	private static long invTick, lastClaim;

	private record Snap(long tick, Map<String, Integer> counts) {
	}
	/** Der Fang fliegt erst vom Schwimmer zum Spieler – bei weitem Wurf dauert das ein paar Sekunden. */
	private static final int WAIT_TICKS = 100, HISTORY_TICKS = 140;

	private static void watchInventory(Minecraft mc) {
		invTick++;
		if (mc.player == null) {
			INV_HISTORY.clear();
			CANDIDATES.clear();
			return;
		}
		if (CANDIDATES.isEmpty() && invTick % 5 != 0) {
			return; // ohne offenen Fang reicht ein Blick alle paar Ticks
		}
		Map<String, Integer> now = new HashMap<>();
		var inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) {
				now.merge(key(st.getHoverName().getString()), st.getCount(), Integer::sum);
			}
		}
		INV_HISTORY.addLast(new Snap(invTick, now));
		while (INV_HISTORY.size() > HISTORY_TICKS) {
			INV_HISTORY.removeFirst();
		}
		if (invTick - lastClaim > HISTORY_TICKS) {
			CLAIMED.clear();
		}
		if (CANDIDATES.isEmpty()) {
			return;
		}
		boolean changed = false;
		for (java.util.Iterator<Candidate> it = CANDIDATES.iterator(); it.hasNext();) {
			Candidate c = it.next();
			String k = key(c.stack().fish);
			int cur = now.getOrDefault(k, 0);
			// kleinster Stand rund um den Fang (der Zugang kann kurz vor oder nach der Chat-Meldung kommen)
			int min = cur;
			for (Snap h : INV_HISTORY) {
				if (h.tick() >= c.at() - 20) {
					min = Math.min(min, h.counts().getOrDefault(k, 0));
				}
			}
			int gained = cur - min - CLAIMED.getOrDefault(k, 0);
			if (gained > 0) {
				CLAIMED.merge(k, 1, Integer::sum); // liegt im Inventar, nicht im Rucksack
				lastClaim = invTick;
				it.remove();
			} else if (invTick - c.at() >= WAIT_TICKS) {
				data().pending.add(c.stack()); // nicht im Inventar aufgetaucht → im Rucksack
				it.remove();
				changed = true;
			}
		}
		if (changed) {
			save();
		}
	}

	private static String key(String name) {
		return net.minecraft.ChatFormatting.stripFormatting(name).strip().toLowerCase(Locale.ROOT);
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

	private static Value cachedValue;
	private static long cachedAt;

	/** Wert des Rucksacks – höchstens zweimal pro Sekunde neu berechnet (das HUD fragt jedes Bild). */
	public static Value value() {
		long now = System.currentTimeMillis();
		if (cachedValue != null && now - cachedAt < 500) {
			return cachedValue;
		}
		cachedAt = now;
		return cachedValue = compute();
	}

	private static Value compute() {
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
		return de.loothub.client.Accounts.file("loothub-backpack.json"); // je Minecraft-Konto
	}

	private static Data data() {
		if (data == null) {
			data = new Data();
			Path p = file();
			if (Files.exists(p)) {
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					Data d = GSON.fromJson(r, Data.class);
					if (d != null && d.pages != null && d.pending != null) {
						if (d.sales == null) {
							d.sales = new ArrayList<>();
						}
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
