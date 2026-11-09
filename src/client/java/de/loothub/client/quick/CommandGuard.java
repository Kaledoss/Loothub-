package de.loothub.client.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import de.loothub.Loothub;

/**
 * Einziger Weg, über den Loothub Befehle an den Server schickt – immer nur auf einen Klick hin. Sicherung gegen
 * versehentliches Mehrfach-Senden: höchstens ein Befehl pro 1,2 Sekunden, und jeder gesendete Befehl steht mit
 * seiner Quelle im Log.
 */
public final class CommandGuard {
	private static final long MIN_GAP_MS = 1_200;
	private static long lastAt;

	private CommandGuard() {
	}

	/** @return true, wenn der Befehl gesendet wurde. */
	public static synchronized boolean send(String command, String source) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null || command == null || command.isBlank()) {
			return false;
		}
		long now = System.currentTimeMillis();
		String cmd = command.strip().replaceFirst("^/", "");
		if (now - lastAt < MIN_GAP_MS) {
			Loothub.LOGGER.info("Loothub: Befehl /{} ({}) nicht gesendet – zu schnell hintereinander", cmd, source);
			if (mc.player != null) {
				mc.gui.setOverlayMessage(Component.translatable("loothub.command.too_fast").withStyle(ChatFormatting.GRAY), false);
			}
			return false;
		}
		lastAt = now;
		Loothub.LOGGER.info("Loothub: sende Befehl /{} ({})", cmd, source);
		mc.getConnection().sendCommand(cmd);
		return true;
	}
}
