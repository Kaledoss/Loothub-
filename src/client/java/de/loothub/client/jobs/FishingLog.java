package de.loothub.client.jobs;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Angelverlauf: erkennt „Du hast Orakelfisch in der Größe Mittel und der Seltenheit EPISCH gefangen.“ und merkt
 * sich jeden Fang mit Uhrzeit, Größe und Seltenheit – samt den Farben, in denen der Server sie anzeigt.
 * Gespeichert in {@code config/loothub-fishing.json}. Nur Mitlesen des Chats.
 */
public final class FishingLog {
	/** Ein Fang. Farben als 0xRRGGBB, -1 = unbekannt. */
	public static final class Catch {
		public long t;
		public String fish = "";
		public String size = "";
		public String rarity = "";
		public int fishColor = -1;
		public int sizeColor = -1;
		public int rarityColor = -1;
	}

	private static final int MAX = 50_000;
	private static final Gson GSON = new Gson();
	private static final Pattern FULL = Pattern.compile(
			"Du hast (.+?) in der Größe (.+?) und der Seltenheit (.+?) gefangen", Pattern.CASE_INSENSITIVE);
	private static final Pattern NO_SIZE = Pattern.compile(
			"Du hast (.+?) (?:mit|in) der Seltenheit (.+?) gefangen", Pattern.CASE_INSENSITIVE);
	/** Bekannte Seltenheiten von gewöhnlich bis selten – unbekannte zählen als gewöhnlich. */
	private static final List<String> ORDER = List.of("gewöhnlich", "ungewöhnlich", "selten", "episch", "legendär",
			"mythisch", "göttlich");

	private static List<Catch> catches;
	private static long lastText;
	private static String lastLine = "";
	private static int version;

	private FishingLog() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				scan(message);
			}
		});
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("loothub-fishing.json");
	}

	public static List<Catch> all() {
		if (catches == null) {
			catches = new ArrayList<>();
			Path p = file();
			if (Files.exists(p)) {
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					List<Catch> l = GSON.fromJson(r, new TypeToken<List<Catch>>() { }.getType());
					if (l != null) {
						l.removeIf(c -> c == null || c.fish == null);
						catches.addAll(l);
					}
				} catch (Exception e) {
					Loothub.LOGGER.warn("Angelverlauf konnte nicht gelesen werden", e);
				}
			}
		}
		return catches;
	}

	/** Ändert sich bei jedem neuen Fang (für die Anzeige). */
	public static int version() {
		return version;
	}

	public static void clear() {
		all().clear();
		version++;
		save();
	}

	private static void save() {
		de.loothub.client.AsyncSave.write(file(), GSON.toJson(all()));
	}

	/** Rang der Seltenheit (0 = gewöhnlich/unbekannt). */
	public static int rank(String rarity) {
		int i = ORDER.indexOf(rarity == null ? "" : rarity.strip().toLowerCase(Locale.ROOT));
		return Math.max(0, i);
	}

	static void scan(Component message) {
		String plain = message.getString().replaceAll("§.", "").replaceAll("\\s+", " ").strip();
		int at = plain.indexOf("Du hast");
		if (at < 0 || !plain.contains("gefangen")) {
			return;
		}
		// Nur die Server-Meldung („OPSUCHT » Du hast …“), kein Spieler, der den Satz in den Chat schreibt
		String prefix = plain.substring(0, at).strip();
		if (!prefix.isEmpty() && !prefix.matches("(?i)opsucht\\s*[»>:]")) {
			return;
		}
		String fish;
		String size = "";
		String rarity;
		Matcher m = FULL.matcher(plain);
		if (m.find()) {
			fish = m.group(1).strip();
			size = m.group(2).strip();
			rarity = m.group(3).strip();
		} else {
			Matcher n = NO_SIZE.matcher(plain);
			if (!n.find()) {
				return;
			}
			fish = n.group(1).strip();
			rarity = n.group(2).strip();
		}
		long now = System.currentTimeMillis();
		if (plain.equals(lastLine) && now - lastText < 500) {
			return; // doppelt zugestellt
		}
		lastLine = plain;
		lastText = now;
		Catch c = new Catch();
		c.t = now;
		c.fish = fish;
		c.size = size;
		c.rarity = rarity;
		c.fishColor = colorOf(message, fish);
		c.sizeColor = size.isEmpty() ? -1 : colorOf(message, size);
		c.rarityColor = colorOf(message, rarity);
		List<Catch> l = all();
		l.add(c);
		if (l.size() > MAX) {
			l.subList(0, l.size() - MAX).clear();
		}
		version++;
		save();
	}

	/** Farbe, in der der Server diesen Textteil anzeigt. */
	private static int colorOf(Component message, String part) {
		int[] found = {-1};
		message.visit((Style style, String text) -> {
			if (found[0] == -1 && !text.isBlank() && text.strip().equalsIgnoreCase(part)) {
				TextColor col = style.getColor();
				if (col != null) {
					found[0] = col.getValue();
				}
			}
			return Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}
}
