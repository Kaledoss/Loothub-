package de.loothub.client.bau;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import de.loothub.Loothub;
import de.loothub.client.MarketPrices;

/**
 * Was ein Item kosten würde, wenn man es aus seinen Zutaten herstellt (Werkbank, Steinsäge, Ofen) – mit den Marktpreisen
 * der Zutaten, und die Zutaten selbst wieder günstigst (gekauft oder hergestellt). Rezepte kommen aus dem Rezeptbuch,
 * das der Server dem Spieler schickt: Was dort fehlt, kennt die Mod nicht. Brennstoff im Ofen zählt nicht mit.
 */
public final class Recipes {
	/** Ein Rezept: Ergebnis-Item und -Menge, je Platz die möglichen Zutaten (z. B. alle Holzbretter). */
	record Recipe(Item result, int count, List<List<Item>> slots, String kind) {
	}

	/** Eine Zutat in der Aufschlüsselung: Item, Menge (kann krumm sein) und Kosten; {@code bought} = kaufen statt bauen. */
	public record Part(Item item, double amount, double cost) {
	}

	private static final int MAX_DEPTH = 5;
	private static Map<Item, List<Recipe>> byResult = Map.of();
	private static int knownSize = -1;
	private static int pricesVersion = -1;
	private static final Map<Item, Double> COST = new HashMap<>();
	/** Gewähltes Rezept je Item (null = kaufen günstiger bzw. kein Rezept). */
	private static final Map<Item, Recipe> CHOICE = new HashMap<>();

	private Recipes() {
	}

	/** Rezepte neu einlesen, wenn sich das Rezeptbuch oder die Preise geändert haben. */
	static void refresh() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		try {
			var known = LitematicaBridge.knownRecipes();
			if (known.size() != knownSize) {
				knownSize = known.size();
				byResult = read(known, SlotDisplayContext.fromLevel(mc.level));
				COST.clear();
				CHOICE.clear();
			}
		} catch (RuntimeException | LinkageError e) {
			Loothub.LOGGER.debug("Rezepte nicht lesbar", e);
		}
		if (MarketPrices.version() != pricesVersion) {
			pricesVersion = MarketPrices.version();
			COST.clear();
			CHOICE.clear();
		}
	}

	private static Map<Item, List<Recipe>> read(java.util.Collection<RecipeDisplayEntry> entries, ContextMap ctx) {
		Map<Item, List<Recipe>> out = new HashMap<>();
		for (RecipeDisplayEntry entry : entries) {
			RecipeDisplay d = entry.display();
			try {
				List<SlotDisplay> inputs;
				String kind;
				if (d instanceof ShapedCraftingRecipeDisplay s) {
					inputs = s.ingredients();
					kind = "crafting";
				} else if (d instanceof ShapelessCraftingRecipeDisplay s) {
					inputs = s.ingredients();
					kind = "crafting";
				} else if (d instanceof StonecutterRecipeDisplay s) {
					inputs = List.of(s.input());
					kind = "stonecutter";
				} else if (d instanceof FurnaceRecipeDisplay s) {
					inputs = List.of(s.ingredient());
					kind = "furnace";
				} else {
					continue;
				}
				ItemStack result = d.result().resolveForFirstStack(ctx);
				if (result.isEmpty()) {
					continue;
				}
				List<List<Item>> slots = new ArrayList<>();
				for (SlotDisplay slot : inputs) {
					List<Item> alts = new ArrayList<>();
					for (ItemStack st : slot.resolveForStacks(ctx)) {
						if (!st.isEmpty() && !alts.contains(st.getItem())) {
							alts.add(st.getItem());
						}
					}
					if (!alts.isEmpty()) {
						slots.add(alts);
					}
				}
				if (slots.isEmpty()) {
					continue;
				}
				out.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
						.add(new Recipe(result.getItem(), result.getCount(), slots, kind));
			} catch (RuntimeException e) {
				// ein kaputtes Rezept überspringen
			}
		}
		return out;
	}

	/** Gibt es überhaupt ein Rezept für dieses Item? */
	public static boolean craftable(String id) {
		Item item = item(id);
		return item != null && byResult.containsKey(item);
	}

	/** Günstigster Stückpreis über Rezepte (Zutaten gekauft oder selbst hergestellt); NaN = kein Rezept mit Preisen. */
	public static double recipeUnit(String id) {
		Item item = item(id);
		if (item == null || !byResult.containsKey(item)) {
			return Double.NaN;
		}
		double best = Double.NaN;
		for (Recipe r : byResult.get(item)) {
			double c = recipeCost(r, 0, new HashSet<>(Set.of(item)));
			if (!Double.isNaN(c) && (Double.isNaN(best) || c < best)) {
				best = c;
			}
		}
		return best;
	}

	/** Stückpreis eines Rezepts (alle Zutaten günstigst); NaN, wenn eine Zutat keinen Preis hat. */
	private static double recipeCost(Recipe r, int depth, Set<Item> visiting) {
		double sum = 0;
		for (List<Item> alts : r.slots()) {
			double slot = Double.NaN;
			for (Item alt : alts) {
				double c = cost(alt, depth + 1, visiting);
				if (!Double.isNaN(c) && (Double.isNaN(slot) || c < slot)) {
					slot = c;
				}
			}
			if (Double.isNaN(slot)) {
				return Double.NaN;
			}
			sum += slot;
		}
		return sum / Math.max(1, r.count());
	}

	/** Günstigster Stückpreis: kaufen oder herstellen, je nachdem, was billiger ist; NaN = gar kein Preis. */
	private static double cost(Item item, int depth, Set<Item> visiting) {
		Double cached = COST.get(item);
		if (cached != null) {
			return cached;
		}
		double market = market(item);
		double best = market;
		Recipe choice = null;
		boolean evaluated = depth < MAX_DEPTH && visiting.add(item);
		if (evaluated) {
			for (Recipe r : byResult.getOrDefault(item, List.of())) {
				double c = recipeCost(r, depth, visiting);
				if (!Double.isNaN(c) && (Double.isNaN(best) || c < best - 1e-9)) {
					best = c;
					choice = r;
				}
			}
			visiting.remove(item);
		}
		// merken – außer wenn wegen eines Kreises (Block ↔ Barren) oder der Tiefe gar nicht gerechnet wurde
		if (evaluated || !byResult.containsKey(item)) {
			COST.put(item, best);
			CHOICE.put(item, choice);
		}
		return best;
	}

	/**
	 * Aufschlüsselung auf Grundmaterialien: Was man für {@code amount} Stück kaufen müsste, wenn man alles günstigst
	 * selbst herstellt (die Zutaten, die man besser kauft, stehen als Teile da). Leer = kein Rezept.
	 */
	public static List<Part> breakdown(String id, double amount) {
		Item item = item(id);
		Map<Item, Double> leaves = new LinkedHashMap<>();
		if (item == null || !byResult.containsKey(item)) {
			return List.of();
		}
		// erste Stufe immer über das Rezept, auch wenn Kaufen billiger wäre (man will ja sehen, woraus es besteht)
		Recipe first = null;
		double firstCost = Double.NaN;
		for (Recipe r : byResult.get(item)) {
			double c = recipeCost(r, 0, new HashSet<>(Set.of(item)));
			if (!Double.isNaN(c) && (Double.isNaN(firstCost) || c < firstCost)) {
				firstCost = c;
				first = r;
			}
		}
		if (first == null) {
			first = byResult.get(item).get(0);
		}
		expandRecipe(first, amount, leaves, 0, new HashSet<>(Set.of(item)));
		List<Part> out = new ArrayList<>();
		leaves.forEach((it, n) -> {
			double unit = market(it);
			out.add(new Part(it, n, Double.isNaN(unit) ? Double.NaN : unit * n));
		});
		return out;
	}

	private static void expandRecipe(Recipe r, double amount, Map<Item, Double> leaves, int depth, Set<Item> visiting) {
		double crafts = amount / Math.max(1, r.count());
		for (List<Item> alts : r.slots()) {
			Item pick = alts.get(0);
			double bestC = Double.NaN;
			for (Item alt : alts) {
				double c = cost(alt, depth + 1, visiting);
				if (!Double.isNaN(c) && (Double.isNaN(bestC) || c < bestC)) {
					bestC = c;
					pick = alt;
				}
			}
			expand(pick, crafts, leaves, depth + 1, visiting);
		}
	}

	private static void expand(Item item, double amount, Map<Item, Double> leaves, int depth, Set<Item> visiting) {
		cost(item, depth, visiting);
		Recipe r = CHOICE.get(item);
		if (r == null || depth >= MAX_DEPTH || !visiting.add(item)) {
			leaves.merge(item, amount, Double::sum);
			return;
		}
		expandRecipe(r, amount, leaves, depth, visiting);
		visiting.remove(item);
	}

	private static double market(Item item) {
		MarketPrices.Entry e = MarketPrices.get(BuiltInRegistries.ITEM.getKey(item).toString());
		if (e == null) {
			return Double.NaN;
		}
		if (e.buy().available()) {
			return e.buy().price();
		}
		return e.sell().available() ? e.sell().price() : Double.NaN;
	}

	private static Item item(String id) {
		Identifier identifier = Identifier.tryParse(id);
		return identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
	}

	/** Anzahl bekannter Rezepte (für Hinweise). */
	public static int known() {
		return Math.max(0, knownSize);
	}
}
