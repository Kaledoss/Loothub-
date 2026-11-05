package de.loothub.client.cosmetics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Loothub-Cosmetics, die alle Spieler mit der Mod sehen. Wer was trägt, steht in {@code cosmetics.json} im
 * GitHub-Repository (Cosmetic → Liste von Spielernamen oder UUIDs); die Mod lädt die Datei beim Start und alle
 * 10 Minuten neu. Es wird nichts hochgeladen – jede Mod liest nur diese Liste.
 */
public final class Cosmetics {
	public static final String MINI_ME = "minime";
	public static final String AURA = "aura";
	/** Aura (und die Flug-Spur aus Aura-Flammen) vorerst ausgeschaltet. */
	public static final boolean AURA_ENABLED = false;
	public static final String WINGS = "wings";
	public static final String MINI_ME_WINGS = "minimewings";
	/** Leuchtende Augen, Wert = Farbe. */
	public static final String EYES = "eyes";
	/** Schwebende Krone über dem Kopf. */
	public static final String CROWN = "crown";
	/** Fußspuren, Wert = "sparks" oder "flames". */
	public static final String FOOTSTEPS = "footsteps";
	/** Energiestreifen hinter dem Spieler in der Flug-Haltung. */
	public static final String FLY_TRAIL = "flytrail";
	/** Aura wird beim Sprinten/Fliegen stärker, mit Blitzen auf der höchsten Stufe. */
	public static final String AURA_LEVELS = "auralevels";
	/** Haustiere neben den Mini-Mes, Wert = "slime,allay" (je Platz). */
	public static final String PETS = "pets";
	/** Mini-Mes winken, jubeln und gähnen. */
	public static final String EMOTES = "emotes";
	/** Farbe der eigenen Flügel bzw. der Mini-Me-Flügel („#RRGGBB“, leer = Originalfarben). */
	public static final String WING_COLOR = "wingcolor";
	public static final String MINI_WING_COLOR = "minimewingcolor";
	/** Zweite Flügelfarbe: Knochen und Rand. */
	public static final String WING_COLOR_OUTER = "wingcolorouter";
	public static final String MINI_WING_COLOR_OUTER = "minimewingcolorouter";
	/** Stärke der Aura in Prozent (0–100). */
	public static final String AURA_STRENGTH = "aurastrength";
	public static final List<String> FOOTSTEP_STYLES = List.of("sparks", "flames");
	public static final List<String> PET_TYPES = List.of("slime", "allay", "bee", "vex");
	public static final int MAX_PETS = 2;
	/** Flügel-Stile (Textur textures/entity/wing_<stil>.png). */
	public static final List<String> WING_STYLES = List.of("dragon", "devil", "angel");

	private static final String URL = "https://raw.githubusercontent.com/Kaledoss/Loothub-/main/cosmetics.json";
	private static final long REFRESH_MS = 10 * 60_000L;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	/** Spieler (Name klein oder UUID) → seine Cosmetics. */
	private static volatile Map<String, Set<String>> owners = Map.of();
	public static final int MAX_MINI_MES = 6;
	/** Spieler → Skins seiner Mini-Mes (Spielernamen; "" = eigener Skin). Fehlt = ein Mini-Me mit eigenem Skin. */
	private static volatile Map<String, List<String>> miniMeSkins = Map.of();
	/** Spieler → Farbe seiner Aura („#RRGGBB“ oder Name wie gold, blue …). */
	private static volatile Map<String, String> auraColors = Map.of();
	/** Spieler → einfache Werte je Cosmetic (z. B. "wings" → "angel"). */
	private static volatile Map<String, Map<String, String>> singles = Map.of();
	private static volatile long lastFetch;
	private static volatile boolean loading;

	/** Cosmetics je Render-Zustand (nur für den aktuellen Frame). */
	private static final Map<Object, Set<String>> STATES = new WeakHashMap<>();
	private static final Map<Object, List<String>> SKINS = new WeakHashMap<>();
	private static final Map<Object, String> AURAS = new WeakHashMap<>();
	private static final Map<Object, Map<String, String>> VALUES = new WeakHashMap<>();
	/** Zuletzt gesehene Cosmetics je Spieler (Entity-ID) – für Partikel im Tick. */
	private static final Map<Integer, Snapshot> BY_PLAYER = new HashMap<>();

	public record Snapshot(Set<String> set, Map<String, String> values, long time) {
	}

	private Cosmetics() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (WidgetConfig.get().cosmetics && !loading && System.currentTimeMillis() - lastFetch > REFRESH_MS) {
				fetch();
			}
		});
	}

	private static void fetch() {
		loading = true;
		lastFetch = System.currentTimeMillis();
		HttpRequest req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(15))
				.header("User-Agent", "Loothub-Mod").GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((r, t) -> {
			try {
				if (t == null && r.statusCode() == 200) {
					Map<String, List<String>> skins = new HashMap<>();
					Map<String, String> auras = new HashMap<>();
					Map<String, Map<String, String>> single = new HashMap<>();
					owners = parse(r.body(), skins, auras, single);
					singles = single;
					miniMeSkins = skins;
					auraColors = auras;
				}
			} catch (RuntimeException e) {
				Loothub.LOGGER.warn("Cosmetics-Liste nicht lesbar", e);
			} finally {
				loading = false;
			}
		});
	}

	/**
	 * {"minime": ["Kaledoss", …]} – ein Mini-Me mit eigenem Skin; {"minime": {"Kaledoss": "Notch"}} – mit fremdem Skin;
	 * {"minime": {"Kaledoss": ["Notch", "", "jeb_"]}} – bis zu 4 Mini-Mes ("" = eigener Skin). Weitere Cosmetics als Liste.
	 */
	private static Map<String, Set<String>> parse(String json, Map<String, List<String>> skins, Map<String, String> auras,
			Map<String, Map<String, String>> single) {
		Map<String, Set<String>> out = new HashMap<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		for (String cosmetic : root.keySet()) {
			String c = cosmetic.toLowerCase(Locale.ROOT);
			JsonElement value = root.get(cosmetic);
			if (value.isJsonArray()) {
				for (JsonElement who : value.getAsJsonArray()) {
					String key = who.getAsString().strip().toLowerCase(Locale.ROOT);
					out.computeIfAbsent(key, k -> new HashSet<>()).add(c);
				}
			} else if (value.isJsonObject()) {
				JsonObject map = value.getAsJsonObject();
				for (String who : map.keySet()) {
					String key = who.strip().toLowerCase(Locale.ROOT);
					out.computeIfAbsent(key, k -> new HashSet<>()).add(c);
					if (map.get(who).isJsonPrimitive()) {
						single.computeIfAbsent(key, k -> new HashMap<>()).put(c, map.get(who).getAsString().strip());
					} else if (map.get(who).isJsonArray()) {
						// je Platz ein Wert (z. B. Mini-Me-Flügel): als „a,b,,c“ ablegen
						List<String> parts = new ArrayList<>();
						for (JsonElement e : map.get(who).getAsJsonArray()) {
							parts.add(e.isJsonNull() ? "" : e.getAsString().strip());
						}
						single.computeIfAbsent(key, k -> new HashMap<>()).put(c, String.join(",", parts) + ",");
					}
					if (c.equals(AURA) && map.get(who).isJsonPrimitive()) {
						auras.put(key, map.get(who).getAsString().strip());
					}
					if (c.equals(MINI_ME)) {
						List<String> list = new ArrayList<>();
						JsonElement v = map.get(who);
						if (v.isJsonArray()) {
							for (JsonElement e : v.getAsJsonArray()) {
								if (list.size() < MAX_MINI_MES) {
									list.add(e.isJsonNull() ? null : e.getAsString().strip()); // null = Platz frei
								}
							}
						} else if (v.isJsonPrimitive()) {
							list.add(v.getAsString().strip());
						}
						if (!list.isEmpty()) {
							skins.put(key, list);
						}
					}
				}
			}
		}
		return out;
	}

	private record Resolved(Set<String> set, Map<String, String> values, List<String> skins, String aura, long time) {
	}

	/** Ergebnis je Spieler kurz zwischenspeichern – nicht in jedem Bild neu zusammensetzen. */
	private static final Map<Integer, Resolved> RESOLVED = new HashMap<>();

	private static Resolved resolve(Player p) {
		long nowMs = System.currentTimeMillis();
		Resolved cached = RESOLVED.get(p.getId());
		if (cached != null && nowMs - cached.time() < 250L) {
			return cached;
		}
		Map<String, Set<String>> o = owners;
		String uuid = p.getUUID().toString();
		String name = p.getGameProfile().name().toLowerCase(Locale.ROOT);
		Set<String> set = o.get(uuid);
		String key = uuid;
		if (set == null) {
			set = o.get(name);
			key = name;
		}
		List<String> skins = miniMeSkins.get(key);
		// Eigene Wahl in den Einstellungen: sofort sichtbar, für andere erst nach Eintrag in cosmetics.json
		List<String> own = ownMiniMes();
		if (p == net.minecraft.client.Minecraft.getInstance().player && own != null) {
			Set<String> mine = new HashSet<>(set == null ? Set.of() : set);
			mine.add(MINI_ME);
			set = mine;
			skins = own;
		}
		String aura = auraColors.get(key);
		String ownAura = WidgetConfig.get().auraColor;
		if (p == net.minecraft.client.Minecraft.getInstance().player && ownAura != null && !ownAura.isBlank()) {
			Set<String> mine = new HashSet<>(set == null ? Set.of() : set);
			mine.add(AURA);
			set = mine;
			aura = ownAura;
		}
		Map<String, String> values = new HashMap<>(singles.getOrDefault(key, Map.of()));
		if (p == net.minecraft.client.Minecraft.getInstance().player) {
			WidgetConfig cfg = WidgetConfig.get();
			Set<String> mine = new HashSet<>(set == null ? Set.of() : set);
			if (cfg.wings != null && !cfg.wings.isBlank()) {
				values.put(WINGS, cfg.wings);
				mine.add(WINGS);
			}
			if (cfg.miniMeWings != null && !cfg.miniMeWings.replace(",", "").isBlank()) {
				values.put(MINI_ME_WINGS, cfg.miniMeWings);
				mine.add(MINI_ME_WINGS);
			}
			values.put(AURA_STRENGTH, String.valueOf(cfg.auraStrength));
			putOwn(mine, values, EYES, cfg.eyeColor);
			putOwn(mine, values, WING_COLOR, cfg.wingColor);
			putOwn(mine, values, MINI_WING_COLOR, cfg.miniMeWingColor);
			putOwn(mine, values, WING_COLOR_OUTER, cfg.wingColorOuter);
			putOwn(mine, values, MINI_WING_COLOR_OUTER, cfg.miniMeWingColorOuter);
			putOwn(mine, values, FOOTSTEPS, cfg.footsteps);
			putOwn(mine, values, PETS, cfg.pets == null || cfg.pets.replace(",", "").isBlank() ? "" : cfg.pets);
			if (cfg.crown) {
				mine.add(CROWN);
			}
			if (cfg.flyTrail) {
				mine.add(FLY_TRAIL);
			}
			if (cfg.auraLevels) {
				mine.add(AURA_LEVELS);
			}
			if (cfg.miniMeEmotes) {
				mine.add(EMOTES);
			}
			set = mine.isEmpty() ? set : mine;
		}
		if (aura != null && !aura.isBlank()) {
			values.put(AURA, aura);
		}
		if (set != null && !set.isEmpty()) {
			BY_PLAYER.put(p.getId(), new Snapshot(set, values, System.currentTimeMillis()));
		} else {
			BY_PLAYER.remove(p.getId());
		}
		if (BY_PLAYER.size() > 300) {
			long cut = System.currentTimeMillis() - 60_000L;
			BY_PLAYER.values().removeIf(s -> s.time() < cut);
		}
		Resolved r = new Resolved(set, values, skins, aura, nowMs);
		RESOLVED.put(p.getId(), r);
		if (RESOLVED.size() > 300) {
			RESOLVED.values().removeIf(x -> nowMs - x.time() > 60_000L);
		}
		return r;
	}

	/** Beim Vorbereiten des Renderns: Cosmetics des Spielers an seinen Render-Zustand hängen. */
	public static void mark(Object state, LivingEntity entity) {
		SKINS.remove(state);
		AURAS.remove(state);
		VALUES.remove(state);
		if (!(entity instanceof Player p) || !WidgetConfig.get().cosmetics) {
			STATES.remove(state);
			return;
		}
		Resolved r = resolve(p);
		Set<String> set = r.set();
		Map<String, String> values = r.values();
		List<String> skins = r.skins();
		String aura = r.aura();
		if (set == null || set.isEmpty()) {
			STATES.remove(state);
		} else {
			STATES.put(state, set);
			if (!values.isEmpty()) {
				VALUES.put(state, values);
			}
			if (skins != null && !skins.isEmpty()) {
				SKINS.put(state, skins);
			}
			if (set.contains(AURA)) {
				AURAS.put(state, aura != null && !aura.isBlank() ? aura : "gold");
			}
		}
	}

	private static void putOwn(Set<String> mine, Map<String, String> values, String cosmetic, String value) {
		if (value != null && !value.isBlank()) {
			values.put(cosmetic, value);
			mine.add(cosmetic);
		}
	}

	/** Cosmetics eines Spielers, wie sie beim letzten Zeichnen galten (oder null, wenn er nicht sichtbar war). */
	public static Snapshot of(int entityId) {
		Snapshot s = BY_PLAYER.get(entityId);
		return s == null || System.currentTimeMillis() - s.time() > 2_000L ? null : s;
	}

	/** Roher Wert eines Cosmetics (ohne Prüfung), oder null. */
	public static String raw(Object state, String cosmetic) {
		if (!has(state, cosmetic)) {
			return null;
		}
		Map<String, String> v = VALUES.get(state);
		return v == null ? null : v.get(cosmetic);
	}

	/** Flügelfarbe des Mini-Mes auf Platz {@code slot} („#RRGGBB“), oder null für Originalfarben. */
	public static String miniMeWingColor(Object state, int slot) {
		return slotColor(raw(state, MINI_WING_COLOR), slot);
	}

	/** Zweite Farbe (Knochen und Rand) des Mini-Mes auf Platz {@code slot}, oder null. */
	public static String miniMeWingColorOuter(Object state, int slot) {
		return slotColor(raw(state, MINI_WING_COLOR_OUTER), slot);
	}

	private static String slotColor(String raw, int slot) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String s;
		if (raw.indexOf(',') < 0) {
			s = raw.strip(); // ein Wert für alle
		} else {
			String[] parts = raw.split(",", -1);
			s = slot < parts.length ? parts[slot].strip() : "";
		}
		return s.isEmpty() ? null : s;
	}

	/** Haustier auf Platz {@code slot} ("slime", "allay" …), oder null. */
	public static String pet(Object state, int slot) {
		String raw = raw(state, PETS);
		if (raw == null) {
			return null;
		}
		String[] parts = raw.split(",", -1);
		String s = slot < parts.length ? parts[slot].strip().toLowerCase(Locale.ROOT) : "";
		return PET_TYPES.contains(s) ? s : null;
	}

	public static boolean has(Object state, String cosmetic) {
		Set<String> set = STATES.get(state);
		return set != null && set.contains(cosmetic);
	}

	/** Einfacher Wert eines Cosmetics (z. B. Flügel-Stil), oder null. */
	public static String value(Object state, String cosmetic) {
		if (!has(state, cosmetic)) {
			return null;
		}
		Map<String, String> v = VALUES.get(state);
		String s = v == null ? null : v.get(cosmetic);
		return s == null || !WING_STYLES.contains(s.toLowerCase(Locale.ROOT)) ? null : s.toLowerCase(Locale.ROOT);
	}

	/**
	 * Flügel-Stil des Mini-Mes auf Platz {@code slot}, oder null. Gespeichert als „stil1,stil2,,…“ (je Platz);
	 * ein einzelner Stil ohne Komma gilt für alle Mini-Mes.
	 */
	public static String miniMeWing(Object state, int slot) {
		if (!has(state, MINI_ME_WINGS)) {
			return null;
		}
		Map<String, String> v = VALUES.get(state);
		return wingAt(v == null ? null : v.get(MINI_ME_WINGS), slot);
	}

	/** Stil auf Platz {@code slot} aus „a,b,,c“ (oder einem einzelnen Stil für alle), oder null. */
	public static String wingAt(String raw, int slot) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String s;
		if (raw.indexOf(',') < 0) {
			s = raw.strip();
		} else {
			String[] parts = raw.split(",", -1);
			s = slot < parts.length ? parts[slot].strip() : "";
		}
		s = s.toLowerCase(Locale.ROOT);
		return WING_STYLES.contains(s) ? s : null;
	}

	/** Farbe der Aura dieses Spielers, oder null ohne Aura. */
	public static String auraColor(Object state) {
		return AURAS.get(state);
	}

	/** Stärke der Aura in Prozent (Standard 60). */
	public static int auraStrength(Object state) {
		Map<String, String> v = VALUES.get(state);
		String s = v == null ? null : v.get(AURA_STRENGTH);
		try {
			return s == null ? 60 : Integer.parseInt(s.strip());
		} catch (NumberFormatException e) {
			return 60;
		}
	}

	/** Eigene Mini-Mes aus den Einstellungen nach Platz (null = Platz frei), oder null wenn keins gewählt ist. */
	private static List<String> ownMiniMes() {
		List<String> cfg = WidgetConfig.get().miniMes;
		if (cfg == null) {
			return null;
		}
		List<String> out = new ArrayList<>();
		boolean any = false;
		for (int i = 0; i < Math.min(MAX_MINI_MES, cfg.size()); i++) {
			String v = cfg.get(i) == null ? "" : cfg.get(i).strip();
			out.add(v.isEmpty() ? null : v);
			any |= !v.isEmpty();
		}
		return any ? out : null;
	}

	/** Skins der Mini-Mes nach Platz (Spielername, "" = eigener Skin, null = Platz frei); mindestens eins. */
	public static List<String> miniMes(Object state) {
		List<String> l = SKINS.get(state);
		return l == null || l.isEmpty() ? List.of("") : l.subList(0, Math.min(MAX_MINI_MES, l.size()));
	}
}
