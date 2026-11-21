package de.loothub.client.progress;

import java.util.Map;

import net.minecraft.network.chat.Component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Shop-Artikel: Titel, Abzeichen und Fußspuren-Stile. Preise und Liste kommen vom Server. */
public final class Shop {
	/** Fußspuren-Stile aus dem Shop: Artikel → Wert in den Cosmetics. */
	public static final Map<String, String> STEPS = Map.ofEntries(Map.entry("c_hearts", "hearts"), Map.entry("c_notes", "notes"),
			Map.entry("c_snow", "snow"), Map.entry("c_magic", "magic"), Map.entry("c_soul", "soul"), Map.entry("c_stars", "stars"),
			Map.entry("c_cherry", "cherry"), Map.entry("c_glow", "glow"), Map.entry("c_totem", "totem"),
			Map.entry("c_rainbow", "rainbow"));
	/** Kronen-Stile aus dem Shop. */
	public static final Map<String, String> CROWNS = Map.of("k_flower", "flower", "k_ice", "ice", "k_nether", "nether",
			"k_halo", "halo");
	/** Haustiere aus dem Shop. */
	public static final Map<String, String> PETS = Map.of("p_phoenix", "phoenix", "p_golddragon", "golddragon");
	/** Augen-Effekte aus dem Shop. */
	public static final Map<String, String> EYES = Map.of("e_rainbow", "rainbow");

	/** Shop-Artikel, die ein Cosmetic freischalten (nicht Titel/Abzeichen)? */
	public static boolean isCosmetic(String id) {
		return STEPS.containsKey(id) || CROWNS.containsKey(id) || PETS.containsKey(id) || EYES.containsKey(id);
	}

	private Shop() {
	}

	/** Abzeichen der Reihe nach als Zeichen der Schrift loothub:badges (Pixel-Icons von Ludo). */
	private static final java.util.List<String> BADGE_ORDER = java.util.List.of("b_star", "b_heart", "b_diamond", "b_sun",
			"b_moon", "b_flower", "b_sword", "b_bolt", "b_note", "b_skull", "b_crown", "b_fire", "b_leaf", "b_shield", "b_potion",
			"b_emerald", "b_trident");
	private static final net.minecraft.resources.Identifier BADGE_FONT =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "badges");

	/** Abzeichen als Pixel-Icon (eigene Schrift), oder null. */
	public static Component badge(String id) {
		int i = id == null ? -1 : BADGE_ORDER.indexOf(id);
		if (i < 0) {
			return null;
		}
		return Component.literal(String.valueOf((char) (0xE000 + i)))
				.withStyle(s -> s.withFont(new net.minecraft.network.chat.FontDescription.Resource(BADGE_FONT)));
	}

	/** Titel als goldener Text, oder null. */
	public static Component titleText(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		return Component.translatable("loothub.lh.shop." + id).withStyle(s -> s.withColor(0xF2C14E));
	}

	/** Name eines Artikels für die Liste. */
	static Component name(String id) {
		return Component.translatable("loothub.lh.shop." + id);
	}

	/** Hat man diesen Artikel gekauft (laut letztem Abgleich)? */
	public static boolean owned(String id) {
		JsonObject s = Progress.state();
		if (s == null || !s.has("owned") || !s.get("owned").isJsonArray()) {
			return false;
		}
		for (JsonElement e : s.getAsJsonArray("owned")) {
			if (id.equals(e.getAsString())) {
				return true;
			}
		}
		return false;
	}

	/** Darf dieser Fußspuren-Stil gewählt werden (frei oder gekauft)? */
	public static boolean stepAllowed(String style) {
		return allowed(STEPS, style);
	}

	public static boolean crownAllowed(String style) {
		return allowed(CROWNS, style);
	}

	public static boolean petAllowed(String type) {
		return allowed(PETS, type);
	}

	public static boolean eyesAllowed(String effect) {
		return allowed(EYES, effect);
	}

	private static boolean allowed(Map<String, String> items, String value) {
		for (Map.Entry<String, String> e : items.entrySet()) {
			if (e.getValue().equals(value)) {
				return owned(e.getKey());
			}
		}
		return true;
	}
}
