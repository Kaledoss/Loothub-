package de.loothub.client.progress;

import java.util.Map;

import net.minecraft.network.chat.Component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Shop-Artikel: Titel, Abzeichen und Fußspuren-Stile. Preise und Liste kommen vom Server. */
public final class Shop {
	/** Fußspuren-Stile aus dem Shop: Artikel → Wert in den Cosmetics. */
	public static final Map<String, String> STEPS = Map.of("c_hearts", "hearts", "c_notes", "notes", "c_snow", "snow",
			"c_magic", "magic", "c_soul", "soul", "c_stars", "stars");

	private Shop() {
	}

	/** Abzeichen der Reihe nach als Zeichen der Schrift loothub:badges (Pixel-Icons von Ludo). */
	private static final java.util.List<String> BADGE_ORDER = java.util.List.of("b_star", "b_heart", "b_diamond", "b_sun",
			"b_moon", "b_flower", "b_sword", "b_bolt", "b_note", "b_skull", "b_crown");
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
		for (Map.Entry<String, String> e : STEPS.entrySet()) {
			if (e.getValue().equals(style)) {
				return owned(e.getKey());
			}
		}
		return true;
	}
}
