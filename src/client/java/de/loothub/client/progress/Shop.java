package de.loothub.client.progress;

import java.util.Map;

import net.minecraft.network.chat.Component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Shop-Artikel: Titel, Abzeichen und Fußspuren-Stile. Preise und Liste kommen vom Server. */
public final class Shop {
	/** Abzeichen: Zeichen und Farbe. */
	private static final Map<String, Object[]> BADGES = Map.ofEntries(
			Map.entry("b_star", new Object[]{"★", 0xFFE066}),
			Map.entry("b_heart", new Object[]{"♥", 0xFF5577}),
			Map.entry("b_diamond", new Object[]{"♦", 0x55DDFF}),
			Map.entry("b_sun", new Object[]{"☀", 0xFFB833}),
			Map.entry("b_moon", new Object[]{"☽", 0xB8C4FF}),
			Map.entry("b_flower", new Object[]{"✿", 0xFF8AD8}),
			Map.entry("b_sword", new Object[]{"⚔", 0xE0E0E0}),
			Map.entry("b_bolt", new Object[]{"⚡", 0xFFE14D}),
			Map.entry("b_note", new Object[]{"♪", 0x7CE08A}),
			Map.entry("b_skull", new Object[]{"☠", 0xC9C9C9}),
			Map.entry("b_crown", new Object[]{"♛", 0xF2C14E}));
	/** Fußspuren-Stile aus dem Shop: Artikel → Wert in den Cosmetics. */
	public static final Map<String, String> STEPS = Map.of("c_hearts", "hearts", "c_notes", "notes", "c_snow", "snow",
			"c_magic", "magic", "c_soul", "soul", "c_stars", "stars");

	private Shop() {
	}

	/** Abzeichen als farbiges Zeichen, oder null. */
	public static Component badge(String id) {
		Object[] b = id == null ? null : BADGES.get(id);
		if (b == null) {
			return null;
		}
		int color = (Integer) b[1];
		return Component.literal((String) b[0]).withStyle(s -> s.withColor(color));
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
