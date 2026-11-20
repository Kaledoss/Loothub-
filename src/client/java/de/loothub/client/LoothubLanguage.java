package de.loothub.client;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Eigene Sprache für Loothub (Einstellung „Sprache von Loothub“: automatisch, Deutsch, Englisch), unabhängig von der
 * Minecraft-Sprache. Alle Texte mit „loothub.“ kommen dann aus der gewählten Loothub-Sprachdatei, der Rest bleibt wie
 * in Minecraft eingestellt. Umgesetzt als Hülle um die Minecraft-Sprache.
 */
public final class LoothubLanguage extends Language {
	private static final Map<String, Map<String, String>> FILES = new HashMap<>();
	private final Language base;
	private final Map<String, String> own;

	private LoothubLanguage(Language base, Map<String, String> own) {
		this.base = base;
		this.own = own;
	}

	/** Gewählte Sprache: "auto", "de_de" oder "en_us". */
	public static String choice() {
		String v = WidgetConfig.get().loothubLanguage;
		return v == null || v.isEmpty() ? "auto" : v;
	}

	/** Jeden Tick: Hülle einsetzen bzw. entfernen, auch nach einem Neuladen der Ressourcen. */
	public static void tick() {
		Language current = Language.getInstance();
		String want = choice();
		if (current instanceof LoothubLanguage l) {
			if (want.equals("auto")) {
				Language.inject(l.base);
			} else if (l.own != file(want)) {
				Language.inject(new LoothubLanguage(l.base, file(want)));
			}
		} else if (!want.equals("auto")) {
			Map<String, String> f = file(want);
			if (!f.isEmpty()) {
				Language.inject(new LoothubLanguage(current, f));
			}
		}
	}

	private static synchronized Map<String, String> file(String lang) {
		return FILES.computeIfAbsent(lang, l -> {
			Map<String, String> m = new HashMap<>();
			try (InputStream in = LoothubLanguage.class.getResourceAsStream("/assets/loothub/lang/" + l + ".json")) {
				if (in != null) {
					JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
					for (Map.Entry<String, JsonElement> e : o.entrySet()) {
						if (e.getValue().isJsonPrimitive()) {
							m.put(e.getKey(), e.getValue().getAsString());
						}
					}
				}
			} catch (Exception e) {
				Loothub.LOGGER.warn("Loothub-Sprachdatei {} nicht lesbar", l, e);
			}
			return m;
		});
	}

	@Override
	public String getOrDefault(String key, String fallback) {
		String v = own.get(key);
		return v != null ? v : base.getOrDefault(key, fallback);
	}

	@Override
	public boolean has(String key) {
		return own.containsKey(key) || base.has(key);
	}

	@Override
	public boolean isDefaultRightToLeft() {
		return base.isDefaultRightToLeft();
	}

	@Override
	public FormattedCharSequence getVisualOrder(FormattedText text) {
		return base.getVisualOrder(text);
	}
}
