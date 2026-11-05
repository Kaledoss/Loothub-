package de.loothub.client.jobs;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Erkennt die Server-Meldung „… Jobschatz erhalten …“ und setzt den Jobschatz-Zähler auf 0.
 * Der Zeitpunkt wird gemerkt, damit das Job-Widget den Fund kurz hervorheben kann.
 */
public final class TreasureWatcher {
	/** So lange wird ein frischer Fund im Widget hervorgehoben. */
	public static final long HIGHLIGHT_MS = 2 * 60_000;

	private static long lastFound;

	private TreasureWatcher() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay && isTreasureMessage(message.getString())) {
				found();
			}
		});
	}

	/** Farbcodes und doppelte Leerzeichen entfernen, dann nach „jobschatz erhalten“ suchen. */
	static boolean isTreasureMessage(String text) {
		String plain = text.replaceAll("§.", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
		return plain.contains("jobschatz erhalten");
	}

	/** Wurde in den letzten zwei Minuten ein Jobschatz gefunden? */
	public static boolean recentlyFound() {
		long at = WidgetConfig.get().treasureFoundAt;
		return at > 0 && System.currentTimeMillis() - at < HIGHLIGHT_MS;
	}

	private static void found() {
		long now = System.currentTimeMillis();
		if (now - lastFound < 3000) {
			return; // doppelte Meldung
		}
		lastFound = now;
		WidgetConfig c = WidgetConfig.get();
		c.treasureXp = 0;
		c.treasuresFound++;
		c.treasureFoundAt = now;
		c.save();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && c.jobWidgetEnabled) {
			mc.player.displayClientMessage(Component.translatable("loothub.treasure.found", c.treasuresFound)
					.withStyle(net.minecraft.ChatFormatting.GOLD), false);
		}
	}
}
