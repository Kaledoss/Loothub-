package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;

/** Ein Knopf der Befehls-Leiste: Beschriftung und Befehl (mit oder ohne „/“). */
public final class QuickCommand {
	public String label;
	public String command;

	public QuickCommand() {
	}

	public QuickCommand(String label, String command) {
		this.label = label;
		this.command = command;
	}

	/** Standard-Knöpfe (Reihenfolge lässt sich im Spiel per Drag &amp; Drop ändern). */
	public static List<QuickCommand> defaults() {
		List<QuickCommand> list = new ArrayList<>();
		list.add(new QuickCommand("Markt", "markt"));
		list.add(new QuickCommand("Verkaufen", "sell"));
		list.add(new QuickCommand("Auktionshaus", "ah"));
		list.add(new QuickCommand("Jobs", "jobs"));
		list.add(new QuickCommand("Fischerei", "fischerei"));
		list.add(new QuickCommand("Jäger", "hunter"));
		list.add(new QuickCommand("Farmer", "farmer"));
		list.add(new QuickCommand("Clan", "clan"));
		list.add(new QuickCommand("Quests", "quest"));
		list.add(new QuickCommand("Erfolge", "achievement"));
		return list;
	}
}
