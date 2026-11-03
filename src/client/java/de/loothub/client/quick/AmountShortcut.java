package de.loothub.client.quick;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

/**
 * Kurzschreibweise für Beträge in selbst eingegebenen Befehlen:
 * „/bank einzahlen 1m“ wird als „/bank einzahlen 1000000“ gesendet.
 * k = Tausend, m = Million, b = Milliarde; Kommazahlen wie 1,5m oder 2.5k gehen auch.
 * Gilt für /bank, /clan bank, /clanbank, /pay und das Auktionshaus (/ah …).
 * Es wird nur der Text umgeschrieben, den der Spieler selbst abschickt.
 */
public final class AmountShortcut {
	private AmountShortcut() {}

	/** Befehle, in denen Beträge umgeschrieben werden (Bank, Zahlungen, Auktionshaus). */
	private static final Set<String> COMMANDS = Set.of("bank", "clanbank", "pay",
			"ah", "auction", "auctions", "auktion", "auktionshaus");

	private static final Pattern AMOUNT = Pattern.compile("(?i)^(\\d+(?:[.,]\\d+)?)([kmb])$");

	public static void register() {
		ClientSendMessageEvents.MODIFY_COMMAND.register(AmountShortcut::rewrite);
	}

	static String rewrite(String command) {
		String[] parts = command.split(" ", -1);
		if (parts.length < 2) return command;
		String root = parts[0].toLowerCase(Locale.ROOT);
		// Clanbank: „/clan bank einzahlen 1m“
		boolean clanBank = root.equals("clan") && parts[1].equalsIgnoreCase("bank");
		if (!clanBank && !COMMANDS.contains(root)) return command;
		boolean changed = false;
		for (int i = 1; i < parts.length; i++) {
			Matcher m = AMOUNT.matcher(parts[i]);
			if (!m.matches()) continue;
			BigDecimal value = new BigDecimal(m.group(1).replace(',', '.'));
			int zeros = switch (Character.toLowerCase(m.group(2).charAt(0))) {
				case 'k' -> 3;
				case 'm' -> 6;
				default -> 9;
			};
			value = value.movePointRight(zeros).stripTrailingZeros();
			if (value.scale() > 0) continue; // ergäbe Nachkommastellen – lieber unverändert lassen
			parts[i] = value.toPlainString();
			changed = true;
		}
		return changed ? String.join(" ", parts) : command;
	}
}
