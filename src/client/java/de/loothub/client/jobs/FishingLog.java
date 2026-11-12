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
		/** Biom am Schwimmer (sonst am Spieler), z. B. "minecraft:warm_ocean"; "" = unbekannt (ältere Einträge). */
		public String biome = "";
	}

	private static final int MAX = 50_000;
	private static final Gson GSON = new Gson();
	private static final Pattern FULL = Pattern.compile(
			"Du hast (.+?) in der Größe (.+?) und der Seltenheit (.+?) gefangen", Pattern.CASE_INSENSITIVE);
	private static final Pattern NO_SIZE = Pattern.compile(
			"Du hast (.+?) (?:mit|in) der Seltenheit (.+?) gefangen", Pattern.CASE_INSENSITIVE);
	/** OPSUCHT-Seltenheiten von niedrig bis hoch – unbekannte zählen wie die niedrigste. */
	private static final List<String> ORDER = List.of("vanilla", "trödel", "schatz", "häufig", "episch", "legendär",
			"spektakulär", "sensationell");

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
				bait(message);
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

	/** Bekannte Seltenheiten in Reihenfolge (klein geschrieben). */
	public static List<String> knownRarities() {
		return ORDER;
	}

	/** Steht die Seltenheit in der bekannten Liste? */
	public static boolean known(String rarity) {
		return ORDER.contains(rarity == null ? "" : rarity.strip().toLowerCase(Locale.ROOT));
	}

	/** Rang der Seltenheit (0 = gewöhnlich/unbekannt). */
	public static int rank(String rarity) {
		int i = ORDER.indexOf(rarity == null ? "" : rarity.strip().toLowerCase(Locale.ROOT));
		return Math.max(0, i);
	}

	private static String plain(Component message) {
		return message.getString().replaceAll("§.", "").replaceAll("\\s+", " ").strip();
	}

	/** Server-Meldung („OPSUCHT » …“) – kein Spieler, der den Satz selbst in den Chat schreibt? */
	private static boolean fromServer(String plain, int at) {
		String prefix = plain.substring(0, at).strip();
		return prefix.isEmpty() || prefix.matches("(?i)opsucht\\s*[»>:]");
	}

	/** Ist das eine Fang-Meldung (für den Chat-Filter)? */
	public static boolean isCatch(Component message) {
		String plain = plain(message);
		int at = plain.indexOf("Du hast");
		return at >= 0 && plain.contains("gefangen") && plain.contains("Seltenheit") && fromServer(plain, at);
	}

	private static long lastBait;

	/** „Keine Köder mehr“: große Warnung mit Ton (einmal pro halbe Minute). */
	private static void bait(Component message) {
		if (!WidgetConfig.get().baitWarning) {
			return;
		}
		String plain = plain(message);
		String low = plain.toLowerCase(Locale.ROOT);
		// nur Server-Meldungen („OPSUCHT » …“ oder ohne Absender), keine Spieler-Nachrichten
		boolean server = low.startsWith("opsucht") || !plain.contains("»");
		if (!low.contains("köder") || !server) {
			return;
		}
		boolean empty = low.contains("keine") || low.contains("leer") || low.contains("aufgebraucht")
				|| low.contains("verbraucht") || low.contains("nicht mehr") || low.contains("kein ");
		long now = System.currentTimeMillis();
		if (empty && now - lastBait > 30_000L) {
			lastBait = now;
			FishAlert.baitEmpty();
		}
	}

	static void scan(Component message) {
		String plain = plain(message);
		int at = plain.indexOf("Du hast");
		if (at < 0 || !plain.contains("gefangen")) {
			return;
		}
		// Nur die Server-Meldung („OPSUCHT » Du hast …“), kein Spieler, der den Satz in den Chat schreibt
		if (!fromServer(plain, at)) {
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
		c.biome = currentBiome();
		List<Catch> l = all();
		l.add(c);
		if (l.size() > MAX) {
			l.subList(0, l.size() - MAX).clear();
		}
		version++;
		save();
		FishSounds.onCatch();
		FishAlert.post(c);
		// ab Legendär jubeln die Mini-Mes mit
		if (rank(c.rarity) >= 5) {
			de.loothub.client.cosmetics.Emotes.cheer();
		}
	}

	/** Biom am Schwimmer – dort beißt der Fisch –, sonst am Spieler. */
	private static String currentBiome() {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return "";
		}
		net.minecraft.core.BlockPos pos = mc.player.fishing != null ? mc.player.fishing.blockPosition() : mc.player.blockPosition();
		// ResourceKey.toString(): "ResourceKey[minecraft:worldgen/biome / minecraft:warm_ocean]"
		return mc.level.getBiome(pos).unwrapKey().map(k -> {
			String t = k.toString();
			int a = t.lastIndexOf(" / ");
			int b = t.lastIndexOf(']');
			return a >= 0 && b > a ? t.substring(a + 3, b).strip() : "";
		}).orElse("");
	}

	/** Anzeigename eines Bioms (übersetzt, sonst aus der ID gebildet). */
	public static String biomeName(String id) {
		if (id == null || id.isBlank()) {
			return "–";
		}
		int colon = id.indexOf(':');
		String ns = colon > 0 ? id.substring(0, colon) : "minecraft";
		String path = colon > 0 ? id.substring(colon + 1) : id;
		String key = "biome." + ns + "." + path.replace('/', '.');
		if (net.minecraft.client.resources.language.I18n.exists(key)) {
			return net.minecraft.client.resources.language.I18n.get(key);
		}
		String[] words = path.substring(path.lastIndexOf('/') + 1).split("_");
		StringBuilder sb = new StringBuilder();
		for (String w : words) {
			if (!w.isEmpty()) {
				sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
		}
		return sb.toString();
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
