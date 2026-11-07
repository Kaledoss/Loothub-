package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Chat-Verlauf dieser Spielsitzung (für die Suche) und Privatnachrichten (eigener Reiter im Loothub-Menü), dazu der
 * Nachrichten-Filter. Nur im Speicher, nichts wird gespeichert oder verschickt.
 */
public final class ChatLog {
	public record Entry(long time, Component text, String plain, boolean pm) {
	}

	private static final int MAX = 3000;
	private static final List<Entry> ALL = new ArrayList<>();
	private static final List<Entry> PMS = new ArrayList<>();
	private static int unreadPms;

	private ChatLog() {
	}

	/** Eingehende Nachricht merken (Original, ohne Uhrzeit/Markierung). */
	public static synchronized void record(Component message) {
		String plain = message.getString();
		if (plain.isBlank()) {
			return;
		}
		boolean pm = isPrivate(plain);
		Entry e = new Entry(System.currentTimeMillis(), message, plain, pm);
		ALL.add(e);
		if (ALL.size() > MAX) {
			ALL.remove(0);
		}
		if (pm) {
			PMS.add(e);
			unreadPms++;
			if (PMS.size() > 500) {
				PMS.remove(0);
			}
		}
	}

	public static synchronized List<Entry> all() {
		return new ArrayList<>(ALL);
	}

	public static synchronized List<Entry> pms() {
		unreadPms = 0;
		return new ArrayList<>(PMS);
	}

	public static synchronized int unreadPms() {
		return unreadPms;
	}

	/** Soll die Nachricht ausgeblendet werden (eigene Filterliste)? */
	public static boolean filtered(Component message) {
		return filterHit(message) != null;
	}

	/** Das Filter-Wort, das die Nachricht ausblendet, oder null. */
	public static String filterHit(Component message) {
		WidgetConfig c = WidgetConfig.get();
		if (!c.chatFilter || c.chatFilterWords == null || c.chatFilterWords.isEmpty()) {
			return null;
		}
		if (CustomChat.isOwn(net.minecraft.ChatFormatting.stripFormatting(message.getString()))) {
			return null; // eigene Nachrichten nie ausblenden
		}
		String low = net.minecraft.ChatFormatting.stripFormatting(message.getString()).toLowerCase(Locale.ROOT);
		for (String w : c.chatFilterWords) {
			if (w != null && !w.isBlank() && low.contains(w.strip().toLowerCase(Locale.ROOT))) {
				return w.strip();
			}
		}
		return null;
	}

	/**
	 * Privatnachricht erkennen: typische Formen wie „[Name -> mir]“, „Name → Du“, „[MSG] …“ oder „flüstert“. Wird
	 * bei Bedarf an das genaue OPSUCHT-Format angepasst.
	 */
	public static boolean isPrivate(String plain) {
		String low = plain.toLowerCase(Locale.ROOT);
		if (low.contains("[msg]") || low.contains("flüstert") || low.contains("privatnachricht") || low.startsWith("✉")) {
			return true;
		}
		boolean arrow = low.contains("->") || low.contains("→") || low.contains("➡");
		if (!arrow) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		String me = mc.player != null ? mc.player.getGameProfile().name().toLowerCase(Locale.ROOT) : "";
		return low.contains(" mir") || low.contains("mir]") || low.contains(" dir") || low.contains(" du")
				|| low.contains(" me]") || (!me.isEmpty() && low.contains(me));
	}
}
