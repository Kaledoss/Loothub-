package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ein Knopf der Befehls-Leiste: Name (Tooltip), Befehl (mit oder ohne „/“) und Symbol.
 * Symbol: Item-ID wie {@code minecraft:emerald} oder eigene Grafik {@code loothub:crown} / {@code loothub:trophy}.
 */
public final class QuickCommand {
	/** Kein Server-Befehl: öffnet die Loothub+-Seite „Wertstoffhändler“. */
	public static final String MERCHANT = "loothub:merchant";
	/** Mit jeder Version neu hinzugekommene Standard-Knöpfe (für bestehende Einstellungen). */
	public static final int DEFAULTS_VERSION = 2;

	public String label;
	public String command;
	public String icon;

	public QuickCommand() {
	}

	public QuickCommand(String label, String command, String icon) {
		this.label = label;
		this.command = command;
		this.icon = icon;
	}

	/** Symbol des Knopfs – fehlt es (ältere Einstellungen), wird es anhand des Befehls gewählt. */
	public String iconOrDefault() {
		if (icon != null && !icon.isBlank()) {
			return icon;
		}
		String cmd = command == null ? "" : command.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT);
		for (QuickCommand d : defaults()) {
			if (d.command.equals(cmd)) {
				return d.icon;
			}
		}
		return "minecraft:command_block";
	}

	/** Standard-Knöpfe (Reihenfolge lässt sich im Spiel per Drag &amp; Drop ändern). */
	public static List<QuickCommand> defaults() {
		List<QuickCommand> list = new ArrayList<>();
		list.add(new QuickCommand("Markt", "markt", "minecraft:emerald"));
		list.add(new QuickCommand("Verkaufen", "sell", "minecraft:gold_nugget"));
		list.add(new QuickCommand("Auktionshaus", "ah", "minecraft:gold_block"));
		list.add(new QuickCommand("Jobs", "jobs", "minecraft:paper"));
		list.add(new QuickCommand("Fischerei", "fischerei", "minecraft:cod"));
		list.add(new QuickCommand("Jäger", "hunter", "minecraft:iron_sword"));
		list.add(new QuickCommand("Farmer", "farmer", "minecraft:wheat"));
		list.add(new QuickCommand("Clan", "clan", "loothub:crown"));
		list.add(new QuickCommand("Quests", "quest", "minecraft:book"));
		list.add(new QuickCommand("Erfolge", "achievement", "loothub:trophy"));
		list.add(new QuickCommand("Pets", "pets", "minecraft:bone"));
		list.add(new QuickCommand("Minions", "minions", "minecraft:armor_stand"));
		list.add(new QuickCommand("Wertstoffhändler", MERCHANT, "minecraft:composter"));
		return list;
	}
}
