package de.loothub.client.jobs;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import de.loothub.Loothub;

/**
 * Einstellungen von Loothub+ (Job-Widget, Tooltip-Preise, Statistik, Updates), gespeichert in {@code config/loothub.json}.
 * Die Position wird relativ gespeichert (0 = ganz links/oben, 1 = ganz rechts/unten),
 * damit das Widget auch nach einer Fenstergrößen-Änderung an der richtigen Stelle sitzt.
 */
public final class WidgetConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static WidgetConfig instance;

	public boolean jobWidgetEnabled = true;
	/** Marktpreise im Item-Tooltip anzeigen. */
	public boolean tooltipPrices = true;
	/** Eigene Statistik zählen. */
	public boolean statsEnabled = true;
	/** Beim Start nach Updates suchen. */
	public boolean updateCheck = true;
	/** Befehls-Leiste unter dem Inventar anzeigen. */
	public boolean quickPanelEnabled = true;
	/** Rote Ränder um Inventar, Rezeptbuch und Befehls-Leiste pulsieren lassen. */
	public boolean pulseBorders = true;
	/** In Markt-Menüs des Servers alle am Marktplatz gehandelten Items grün hinterlegen. */
	public boolean sellHighlight = true;
	/** Item auf dem eigenen Kopf (z. B. Auto) nicht anzeigen – nur für einen selbst. */
	public boolean hideOwnHeadItem = false;
	/** Eigenes Cape ausblenden (nur eigene Darstellung). */
	public boolean hideOwnCape = false;
	/** Block-Info oben in der Mitte (angeschauter Block/Mob, passendes Werkzeug, Marktpreis). */
	public boolean lookInfo = true;
	/** Schilder im Loothub-Fenster beschriften (Farben, Vorschau). */
	public boolean signWindow = true;
	/** Seltenheits-Rahmen im Inventar. */
	public boolean rarityFrames = true;
	/** Chat: Uhrzeit, Klick kopiert, Erwähnung hervorheben (+ Ton). */
	public boolean chatTimestamps = true;
	public boolean chatCopy = true;
	public boolean chatMention = true;
	public boolean chatMentionSound = true;
	/** Eigener Chat (Fenster, Tabs, Farben) statt des normalen Minecraft-Chats. Standardmäßig aus. */
	public boolean customChat = false;
	public java.util.List<de.loothub.client.quick.CustomChat.Window> chatWindows = new java.util.ArrayList<>();
	/** Bisher gesehene Nachrichten-Quellen (für die Tab-Auswahl). */
	public java.util.List<String> chatSources = new java.util.ArrayList<>();
	/** Chat-Filter: Nachrichten mit diesen Wörtern ausblenden. */
	public boolean chatFilter = true;
	public java.util.List<String> chatFilterWords = new java.util.ArrayList<>();
	/** Haltbarkeit als Zahl im Slot. */
	public boolean durabilityNumbers = true;
	/** Wegwerf-Schutz: wertvolle Items erst beim zweiten Q (bzw. zweiten Wurf) fallen lassen. */
	public boolean dropProtection = true;
	/** Offhand-Filter: gewählte Items in der zweiten Hand nicht per Rechtsklick benutzen. */
	public boolean offhandFilter = true;
	/** true = alles sperren außer den gelisteten Items. */
	public boolean offhandAllExcept = false;
	public java.util.List<String> offhandItems = new java.util.ArrayList<>(java.util.List.of("minecraft:fishing_rod"));
	/** Eigene Timer (Countdown, Wiederholung, täglich) und ihre HUD-Anzeige. */
	public boolean timersEnabled = true;
	public java.util.List<de.loothub.client.quick.Timers.Timer> timers = new java.util.ArrayList<>();
	public double timerHudX = 0.99;
	public double timerHudY = 0.35;
	public boolean timerSound = true;
	/** Block-Info: Werkzeug bzw. Marktpreis zeigen, Größe, Position (x = Mitte als Anteil der Breite, y = Anteil der Höhe). */
	public boolean lookInfoTool = true;
	public boolean lookInfoPrice = true;
	/** Block-Info: Inhalt von Regalen anzeigen. */
	public boolean lookInfoContents = true;
	public float lookInfoScale = 1.0F;
	public double lookInfoX = 0.5;
	public double lookInfoY = 0.0;
	/** Ressourcenpaket: leuchtende Erze (pulsierender Rand in Erzfarbe), Stärke in %, Reichweite in Blöcken. */
	public boolean glowingOres = true;
	public int oreGlowStrength = 70;
	public int oreGlowRange = 20;
	/** Erze, die nicht leuchten sollen (z. B. "coal", "copper"). */
	public java.util.List<String> oreGlowOff = new java.util.ArrayList<>();
	/** Einmalig: alte Standard-Reichweite (10) auf die neue (20) anheben. */
	public boolean oreRangeV2 = false;
	/** Leuchtende Augen ("" = aus, sonst „#RRGGBB“). */
	/** Flügelfarben („#RRGGBB“, leer = Originalfarben). */
	public String wingColor = "";
	public String wingColorLast = "";
	/** Flügelfarbe je Mini-Me-Platz als „#RRGGBB,,#RRGGBB,…“ (leer = Originalfarben). */
	public String miniMeWingColor = "";
	public String miniMeWingColorLast = "";
	/** Zweite Flügelfarbe (Knochen und Rand), für mich bzw. je Mini-Me-Platz. */
	public String wingColorOuter = "";
	public String miniMeWingColorOuter = "";
	/** Stärke der Aura in Prozent. */
	public int auraStrength = 60;
	public String eyeColor = "";
	public String eyeLast = "";
	public boolean crown = false;
	/** Fußspuren: "" / "sparks" / "flames". */
	public String footsteps = "";
	public boolean flyTrail = false;
	/** Energie-Kegel beim Hoch-/Runterfliegen. */
	public boolean riseTrail = false;
	public boolean auraLevels = false;
	/** Haustiere je Platz als „slime,allay“ ("" = keins). */
	public String pets = "";
	public boolean miniMeEmotes = false;
	/** Lebendigkeit der Mini-Mes in Prozent (Atmen, Blick, Nachschwingen, Reaktionen; 0 = steif wie früher). */
	public int miniMeLife = 100;
	/** Charakter je Mini-Me, mit Komma je Platz (playful, shy, brave, lazy, curious; leer = normal). */
	public String miniMeTraits = "";
	/** Flug-Haltung beim Fliegen in Bewegung (Kopf voraus, waagerecht). */
	public boolean flyPose = true;
	/** Jobschatz-Zähler: XP seit der letzten 100.000er-Marke und Anzahl erreichter Marken. */
	public double treasureXp = 0;
	public int treasureChances = 0;
	/** Erhaltene Jobschätze (erkannt per Nachricht/Item). */
	public int treasuresFound = 0;
	/** Zeitpunkt des letzten Jobschatzes (für die Hervorhebung im Widget). */
	public long treasureFoundAt = 0;
	/** Talismanrucksack in der eigenen Zweithand nicht anzeigen. */
	public boolean hideOffhandBackpack = false;
	/** Inventar über der Hotbar anzeigen, mit eigener Hintergrundfarbe und Deckkraft. */
	public boolean invHudEnabled = false;
	public int invHudColor = 0x140B0D;
	public double invHudOpacity = 0.6;
	/** Position (relativ); -1 = Standard über der Hotbar. */
	public double invHudX = -1;
	public double invHudY = -1;
	/** Farben (RGB): innen = Akzente in Menüs/Inventar, außen = Rahmen, Puls = pulsierender Rand. */
	public int colorInner = de.loothub.client.Theme.DEFAULT_INNER;
	public int colorOuter = de.loothub.client.Theme.DEFAULT_OUTER;
	public int colorPulse = de.loothub.client.Theme.DEFAULT_PULSE;
	/** Deckkraft der Hintergründe von Widgets und Leisten (0,1–1). */
	public double backgroundOpacity = 1.0;
	/** Ob das schwarz-farbige Inventar-Paket beim Farbwechsel aktiviert werden soll. */
	public boolean themePackWanted = true;
	/** Tauschitem des Tages beim Rohstoffhändler (aus dem Händler-Menü gelesen). */
	public String dailyItemDate = "";
	public String dailyItemId = "";
	public String dailyItemName = "";
	public double dailyItemRate = 0;
	public String dailyItemCurrency = "";
	public long dailyItemUsed = -1;
	public long dailyItemLimit = -1;
	/** Loothub-Cosmetics (z. B. Mini-Me) bei Spielern anzeigen. */
	public boolean cosmetics = true;
	/** Begleiter (Haustiere, freie Mini-Mes) auch in der Ego-Perspektive zeigen. */
	public boolean cosmeticsFirstPerson = false;
	/** Inventar-Werkzeuge: Sortieren, Verschieben, Auto-Refill, Werkzeugschutz. */
	public boolean invSort = false;
	public boolean invMove = false;
	public boolean autoRefill = false;
	public boolean toolSaver = false;
	/** Kompressor: kleines Symbol des Ziel-Items am Kompressor im Inventar/in der Schnellleiste. */
	public boolean compressorIcon = true;
	/** Früher: ein Mini-Me-Skin (wird in {@link #miniMes} übernommen). */
	public String miniMeSkin = "";
	/** Bis zu 4 eigene Mini-Mes – je ein Spielername, dessen Skin es trägt. */
	public java.util.List<String> miniMes = new java.util.ArrayList<>();
	/** Eigene Aura-Farbe als „#RRGGBB“ (leer = keine Aura). */
	public String auraColor = "";
	/** Flügel für einen selbst und für die Mini-Mes (dragon, devil, angel; leer = keine). */
	public String wings = "";
	/** Flügel je Mini-Me-Platz als „stil1,stil2,,…“ (leer = keine); ein Stil ohne Komma gilt für alle. */
	public String miniMeWings = "";
	/** Zuletzt gewählte Aura-Farbe (zum Wiedereinschalten). */
	public String auraLast = "";
	/** AFK-Ansicht: nach 5 Minuten ohne Eingabe herauszoomen und den Charakter schlafen legen. */
	public boolean afkView = true;
	/** Eingesammelte Marktverkäufe als „Markt“ im Job-Verlauf. */
	public boolean marketSales = true;
	/** Marktwert neben geöffneten Kisten, Fässern und Shulkerkisten. */
	public boolean containerValue = true;
	/** Inventar-Design: schwarz-rotes Paket, „Loothub+“-Überschrift und Knöpfe im Inventar, helle Menü-Titel. */
	public boolean inventoryDesign = true;
	/** Betrags-Abkürzungen (1m, 2,5k …) in Bank-, Pay- und Auktionshaus-Befehlen. */
	public boolean amountShortcut = true;
	/** Itemdex: Server-Items sammeln. */
	public boolean itemdexEnabled = true;
	/** Eigenes Fenster für /rename und /sign ohne Text. */
	public boolean renameWindow = true;
	/** Werkzeugschutz: warnen und Benutzen sperren, wenn die Haltbarkeit fast aufgebraucht ist. */
	public boolean toolProtection = true;
	/** Ab so viel Prozent Resthaltbarkeit wird gesperrt (1–100). */
	public int toolProtectionPercent = 5;
	/** Werkzeugarten, die NICHT geschützt werden (Schlüssel aus ToolProtection.KINDS). */
	public java.util.List<String> toolProtectionOff = new java.util.ArrayList<>();
	/** YouTube Music (Desktop-App, API Server): „Läuft gerade“ im HUD. */
	public boolean musicEnabled = false;
	public boolean musicLocked = false;
	public int musicPort = 26538;
	public double musicX = 0.0;
	public double musicY = 1.0;
	/** Pokémon Community Game (Twitch): Spawn-Anzeige und Knöpfe über dem Inventar. */
	public boolean pcgEnabled = false;
	/** Twitch-Kanal, in dem gespielt wird. */
	public String pcgChannel = "";
	/** Eigener Twitch-Name (nur zum Senden nötig; der Token liegt in config/loothub-twitch-token.txt). */
	public String pcgUser = "";
	/** Pokémon-Leiste über dem Inventar: gesperrt und Verschiebung gegenüber der Standardposition. */
	public boolean pcgPanelLocked = false;
	/** Ton, wenn ein Pokémon erscheint. */
	public boolean pcgSound = true;
	/** Position der Pokémon-Anzeige im HUD (relativ, Standard oben rechts). */
	public double pcgOverlayX = 1.0;
	public double pcgOverlayY = 0.0;
	public int pcgPanelOffsetX = 0;
	public int pcgPanelOffsetY = 0;
	/** Timer-Ton: "bell" (Glocke), "gong" oder "alarm" (alter Wecker). */
	public String timerSoundType = "bell";
	/** Lautstärke je Ton-Bereich in Prozent (siehe Sounds.Group), fehlend = 100. */
	public java.util.Map<String, Integer> soundVolumes = new java.util.HashMap<>();
	/** Lage der Twitch-Einblendungen (Anteil, Standard oben Mitte). */
	public double streamAlertX = 0.5;
	public double streamAlertY = 0.03;
	/** Fang-Einblendung: gefangener Fisch im Stil der Twitch-Einblendungen. */
	public boolean fishAlert = true;
	public double fishAlertX = 0.5;
	public double fishAlertY = 0.2;
	/** Seltenheiten (klein geschrieben), die nicht eingeblendet werden. */
	public java.util.List<String> fishAlertOff = new java.util.ArrayList<>();
	/** Fang-Meldungen von OPSUCHT im Chat ausblenden. */
	public boolean fishChatHide = false;
	/** OPSUCHT-Fangton beim Angeln stumm (gelernt, siehe FishSounds). */
	public boolean fishSoundMute = false;
	/** Gelernt: Ton → wie oft er zusammen mit einer Fang-Meldung kam. */
	public java.util.Map<String, Integer> fishSoundSeen = new java.util.HashMap<>();
	public int fishSoundCatches = 0;
	/** Gelernte Töne, die trotzdem hörbar bleiben sollen. */
	public java.util.List<String> fishSoundAllowed = new java.util.ArrayList<>();
	/** Warnung, wenn die Köder ausgehen. */
	public boolean baitWarning = true;
	/** Alarm kurz vor dem Ende gemerkter Auktionen (Merkliste). */
	public boolean auctionAlarm = true;
	public int auctionAlarmMinutes = 10;
	/** Entwurf aus dem /chat-Editor. */
	public String chatDraft = "";
	/** Notizblock im Nachrichten-Editor: gespeicherte Sätze (mit Farbcodes). */
	public java.util.List<String> chatNotes = new java.util.ArrayList<>();
	/** Menüseite „Pokédex“ anzeigen. */
	public boolean pokedexPage = true;
	/** Krönchen vor Loothub-Nutzern in der Tab-Liste. */
	public boolean loothubCrown = true;
	/** Verbundene Texturen (Glas ohne Ränder zwischen gleichen Blöcken). */
	public boolean connectedTextures = true;
	/** Streamer-Modus: eigene Menüseite mit Twitch-Konto, Twitch-Chat im Minecraft-Chat und Kanalbelohnungen. */
	public boolean streamerMode = false;
	/** Twitch-Chat des eigenen Kanals im Minecraft-Chat anzeigen. */
	public boolean streamerChat = false;
	/** Offene Kanalbelohnungen als Liste im HUD. */
	public boolean streamerHud = true;
	public boolean streamerHudLocked = false;
	public double streamerHudX = 1.0;
	public double streamerHudY = 0.5;
	/** Höchstens so viele Einträge im HUD. */
	public int streamerHudRows = 5;
	/** Ereignis-Einblendungen (Follow, Abo, Raid …) oben in der Mitte; ausgeschaltete Arten stehen in der Liste. */
	public boolean streamerAlerts = true;
	public java.util.List<String> streamerAlertsOff = new java.util.ArrayList<>();
	/** Ton bei einer neuen Einlösung. */
	public boolean streamerSound = true;
	/** Befehls-Leiste gesperrt: Knöpfe und Feld lassen sich nicht verschieben. */
	public boolean quickPanelLocked = false;
	/** Verschiebung des Feldes gegenüber der Standardposition unter dem Inventar. */
	public int quickPanelOffsetX = 0;
	public int quickPanelOffsetY = 0;
	/** Knöpfe der Befehls-Leiste (Beschriftung + Befehl). */
	public java.util.List<de.loothub.client.quick.QuickCommand> quickCommands = de.loothub.client.quick.QuickCommand.defaults();
	/** Stand der Standard-Knöpfe; ältere Einstellungen bekommen neue Knöpfe hinten angehängt. */
	public int quickCommandsVersion = 0; // fehlt in alten Dateien → 0, damit neue Knöpfe ergänzt werden
	/** Ausgeblendete Zeilen des Job-Widgets (IDs aus {@link JobWidget#ROW_IDS}). */
	public java.util.List<String> jobWidgetHiddenRows = new java.util.ArrayList<>();
	/** Markt-Favoriten (Material-Namen der API). */
	public java.util.List<String> marketFavorites = new java.util.ArrayList<>();
	public double jobWidgetX = 0.0;
	public double jobWidgetY = 0.35;
	/** Größe des Job-Widgets (1 = normal), im Editor an den Rändern ziehbar. */
	public double jobWidgetScale = 1.0;

	public static WidgetConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(Loothub.MOD_ID + ".json");
	}

	/**
	 * Erste Nutzung (noch keine Einstellungsdatei): alle Loothub-Funktionen aus – jeder schaltet in den
	 * Einstellungen nur ein, was er möchte. Neue Schalter stehen in der Klasse auf „an“, damit bestehende
	 * Nutzer neue Funktionen behalten; für Neulinge werden sie hier abgeschaltet.
	 */
	private void freshInstallDefaults() {
		compressorIcon = false;
		jobWidgetEnabled = false;
		tooltipPrices = false;
		statsEnabled = false;
		quickPanelEnabled = false;
		pulseBorders = false;
		sellHighlight = false;
		themePackWanted = false;
		toolProtection = false;
		inventoryDesign = false;
		amountShortcut = false;
		itemdexEnabled = false;
		renameWindow = false;
		containerValue = false;
		marketSales = false;
		afkView = false;
		cosmetics = false;
		hideOwnHeadItem = false;
		hideOwnCape = false;
		lookInfo = false;
		signWindow = false;
		rarityFrames = false;
		chatTimestamps = false;
		chatCopy = false;
		chatMention = false;
		chatMentionSound = false;
		chatFilter = false;
		customChat = false;
		durabilityNumbers = false;
		dropProtection = false;
		offhandFilter = false;
		timersEnabled = false;
		glowingOres = false;
		flyPose = false;
		hideOffhandBackpack = false;
		invHudEnabled = false;
		musicEnabled = false;
		pcgEnabled = false;
		streamerMode = false;
		streamerChat = false;
		connectedTextures = false;
		loothubCrown = false;
		fishAlert = false;
		baitWarning = false;
		auctionAlarm = false;
	}

	private static WidgetConfig load() {
		Path p = path();
		if (!Files.exists(p)) {
			WidgetConfig fresh = new WidgetConfig();
			fresh.freshInstallDefaults();
			instance = fresh;
			fresh.save();
			return fresh;
		}
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				WidgetConfig c = GSON.fromJson(r, WidgetConfig.class);
				if (c != null) {
					c.jobWidgetX = clamp01(c.jobWidgetX);
					c.jobWidgetY = clamp01(c.jobWidgetY);
					c.jobWidgetScale = JobWidget.clampScale(c.jobWidgetScale);
					if (c.quickCommands != null && c.quickCommandsVersion < de.loothub.client.quick.QuickCommand.DEFAULTS_VERSION) {
						// Alte Befehle auf die richtigen OPSUCHT-Befehle umstellen; Pets liegt jetzt im Inventar
						for (de.loothub.client.quick.QuickCommand q : c.quickCommands) {
							String cmd = q.command == null ? "" : q.command.strip().replaceFirst("^/", "");
							if (de.loothub.client.quick.QuickCommand.MERCHANT.equals(cmd) || cmd.equals("shards")) {
								q.command = "merchant";
								q.icon = "minecraft:amethyst_shard";
							} else if (cmd.equals("minions")) {
								q.command = "minion";
							}
						}
						c.quickCommands.removeIf(q -> q.command != null
								&& q.command.strip().replaceFirst("^/", "").equals("pets"));
						java.util.Set<String> have = new java.util.HashSet<>();
						for (de.loothub.client.quick.QuickCommand q : c.quickCommands) {
							have.add(q.command == null ? "" : q.command.strip().replaceFirst("^/", ""));
						}
						for (de.loothub.client.quick.QuickCommand d : de.loothub.client.quick.QuickCommand.defaults()) {
							if (!have.contains(d.command)) {
								c.quickCommands.add(d);
							}
						}
						c.quickCommandsVersion = de.loothub.client.quick.QuickCommand.DEFAULTS_VERSION;
						c.save();
					}
					if (c.toolProtectionOff == null) {
						c.toolProtectionOff = new java.util.ArrayList<>();
					}
					c.toolProtectionPercent = Math.max(1, Math.min(100, c.toolProtectionPercent));
					if (c.pcgChannel == null) {
						c.pcgChannel = "";
					}
					if (c.pcgUser == null) {
						c.pcgUser = "";
					}
					if (!c.oreRangeV2) {
						if (c.oreGlowRange == 10) {
							c.oreGlowRange = 20;
						}
						c.oreRangeV2 = true;
					}
					if (c.miniMes == null) {
						c.miniMes = new java.util.ArrayList<>();
					}
					if (c.miniMes.isEmpty() && c.miniMeSkin != null && !c.miniMeSkin.isBlank()) {
						c.miniMes.add(c.miniMeSkin.strip());
						c.miniMeSkin = "";
					}
					if (c.fishSoundSeen == null) {
						c.fishSoundSeen = new java.util.HashMap<>();
					}
					if (c.fishSoundAllowed == null) {
						c.fishSoundAllowed = new java.util.ArrayList<>();
					}
					if (c.soundVolumes == null) {
						c.soundVolumes = new java.util.HashMap<>();
					}
					if (c.fishAlertOff == null) {
						c.fishAlertOff = new java.util.ArrayList<>();
					}
					if (c.timerSoundType == null) {
						c.timerSoundType = "bell";
					}
					if (c.streamerAlertsOff == null) {
						c.streamerAlertsOff = new java.util.ArrayList<>();
					}
					if (c.marketFavorites == null) {
						c.marketFavorites = new java.util.ArrayList<>();
					}
					if (c.jobWidgetHiddenRows == null) {
						c.jobWidgetHiddenRows = new java.util.ArrayList<>();
					}
					return c;
				}
			} catch (IOException | RuntimeException e) {
				Loothub.LOGGER.warn("Einstellungen konnten nicht gelesen werden, nutze Standardwerte", e);
			}
		}
		return new WidgetConfig();
	}

	public void save() {
		// im Hintergrund schreiben – z. B. beim Ziehen eines Reglers hängt das Spiel nicht
		de.loothub.client.AsyncSave.write(path(), GSON.toJson(this));
	}

	public boolean jobRowVisible(String id) {
		return !jobWidgetHiddenRows.contains(id);
	}

	public void setJobRowVisible(String id, boolean visible) {
		jobWidgetHiddenRows.remove(id);
		if (!visible) {
			jobWidgetHiddenRows.add(id);
		}
	}

	static double clamp01(double v) {
		return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
	}
}
