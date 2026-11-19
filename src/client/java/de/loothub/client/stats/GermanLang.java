package de.loothub.client.stats;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * Deutsche Mob-Namen aus Minecrafts eigener Sprachdatei (de_de) – unabhängig davon, welche Spielsprache eingestellt
 * ist, damit die Statistik einheitlich deutsch ist („Spider“ → „Spinne“).
 */
final class GermanLang {
	private static Map<String, String> names;

	private GermanLang() {
	}

	static String entity(EntityType<?> type) {
		String german = get(type.getDescriptionId());
		return german != null ? german : type.getDescription().getString();
	}

	private static synchronized String get(String key) {
		if (names == null) {
			names = new HashMap<>();
			try {
				var resources = Minecraft.getInstance().getResourceManager()
						.getResourceStack(Identifier.fromNamespaceAndPath("minecraft", "lang/de_de.json"));
				for (var res : resources) {
					try (Reader reader = res.openAsReader()) {
						JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
						for (Map.Entry<String, JsonElement> e : o.entrySet()) {
							if (e.getKey().startsWith("entity.") && e.getValue().isJsonPrimitive()) {
								names.put(e.getKey(), e.getValue().getAsString());
							}
						}
					}
				}
			} catch (Exception e) {
				de.loothub.Loothub.LOGGER.warn("Loothub: deutsche Mob-Namen nicht lesbar", e);
			}
		}
		return names.get(key);
	}
}
