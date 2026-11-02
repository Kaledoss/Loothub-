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
	public double jobWidgetX = 0.0;
	public double jobWidgetY = 0.35;

	public static WidgetConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(Loothub.MOD_ID + ".json");
	}

	private static WidgetConfig load() {
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				WidgetConfig c = GSON.fromJson(r, WidgetConfig.class);
				if (c != null) {
					c.jobWidgetX = clamp01(c.jobWidgetX);
					c.jobWidgetY = clamp01(c.jobWidgetY);
					if (c.quickCommands != null && c.quickCommandsVersion < de.loothub.client.quick.QuickCommand.DEFAULTS_VERSION) {
						// Der Händler-Knopf öffnet jetzt das Server-Menü (/shards) statt der Übersicht
						for (de.loothub.client.quick.QuickCommand q : c.quickCommands) {
							if (de.loothub.client.quick.QuickCommand.MERCHANT.equals(q.command)) {
								q.command = "shards";
								q.icon = "minecraft:amethyst_shard";
							}
						}
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
					if (c.pcgChannel == null) {
						c.pcgChannel = "";
					}
					if (c.pcgUser == null) {
						c.pcgUser = "";
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
		try {
			Files.createDirectories(path().getParent());
			try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			Loothub.LOGGER.warn("Einstellungen konnten nicht gespeichert werden", e);
		}
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
