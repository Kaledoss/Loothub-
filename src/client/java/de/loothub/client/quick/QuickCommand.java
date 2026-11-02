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

	/** Platzhalter – werden noch gemeinsam festgelegt. */
	public static List<QuickCommand> defaults() {
		List<QuickCommand> list = new ArrayList<>();
		list.add(new QuickCommand("Markt", "markt"));
		list.add(new QuickCommand("Verkaufen", "sell"));
		list.add(new QuickCommand("Jobs", "jobs"));
		list.add(new QuickCommand("Fischerei", "fischerei"));
		return list;
	}
}
