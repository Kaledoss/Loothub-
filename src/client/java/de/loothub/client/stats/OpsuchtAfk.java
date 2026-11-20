package de.loothub.client.stats;

import java.util.Locale;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import de.loothub.Loothub;

/**
 * Meldet OPSUCHT einen gerade als AFK? Erkannt an Server-Meldungen („Du bist jetzt AFK“ / „… nicht mehr AFK“, auch in
 * der Actionbar) und an einem „AFK“ beim eigenen Namen in der Tab-Liste. Nur dann zählt die aktive Spielzeit nicht.
 */
public final class OpsuchtAfk {
	private static final Pattern YOU_AFK = Pattern.compile("(?i)\\b(du bist|you are)\\b.*\\bafk\\b");
	private static final Pattern NOT_ANYMORE = Pattern.compile("(?i)nicht mehr|wieder da|zurück|no longer|not afk");
	private static volatile boolean byMessage;
	private static long lastTabCheck;
	private static boolean byTab;
	private static boolean logged;

	private OpsuchtAfk() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> scan(message));
	}

	private static void scan(Component message) {
		String text = message.getString().replaceAll("§.", "");
		String lower = text.toLowerCase(Locale.ROOT);
		if (!lower.contains("afk")) {
			return;
		}
		if (!logged) {
			// einmal ins Log, um die genaue OPSUCHT-Meldung zu sehen
			logged = true;
			Loothub.LOGGER.info("[Loothub-AFK] Server-Meldung: {}", text.length() > 200 ? text.substring(0, 200) : text);
		}
		if (YOU_AFK.matcher(text).find()) {
			byMessage = !NOT_ANYMORE.matcher(text).find();
		}
	}

	/** Gilt man laut OPSUCHT gerade als AFK? */
	public static boolean afk(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (now - lastTabCheck > 2000) {
			lastTabCheck = now;
			byTab = false;
			if (mc.getConnection() != null && mc.player != null) {
				PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
				Component shown = info == null ? null : info.getTabListDisplayName();
				if (shown != null && shown.getString().replaceAll("§.", "").toUpperCase(Locale.ROOT).matches(".*\\bAFK\\b.*")) {
					byTab = true;
				}
			}
		}
		return byMessage || byTab;
	}

	/** Beim Verbindungswechsel zurücksetzen. */
	public static void reset() {
		byMessage = false;
		byTab = false;
	}
}
