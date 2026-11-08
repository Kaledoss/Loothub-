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
		reactMiniMes(net.minecraft.ChatFormatting.stripFormatting(plain), pm);
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
	/** Mini-Mes reagieren: Privatnachricht bekommen → Briefumschlag; von jemand anderem erwähnt → winken. */
	private static void reactMiniMes(String plain, boolean pm) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		String me = mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
		String low = plain.toLowerCase(Locale.ROOT);
		if (pm) {
			java.util.regex.Matcher m = PM.matcher(low);
			while (m.find()) {
				String b = clean(m.group(2));
				if (SELF.contains(b) || b.equals(me)) {
					de.loothub.client.cosmetics.Emotes.privateMessage();
					return;
				}
			}
			return;
		}
		if (!me.isEmpty() && !CustomChat.isOwn(plain)
				&& java.util.regex.Pattern.compile("(^|[^a-z0-9_])" + java.util.regex.Pattern.quote(me) + "($|[^a-z0-9_])").matcher(low).find()) {
			de.loothub.client.cosmetics.Emotes.mention();
		}
	}

	/** „[A -> B]“ (auch mit → oder ➡) irgendwo in der Nachricht. */
	private static final java.util.regex.Pattern PM = java.util.regex.Pattern.compile(
			"\\[\\s*([^\\]\\[]+?)\\s*(?:->|→|➡|»)\\s*([^\\]\\[]+?)\\s*\\]");
	private static final java.util.Set<String> SELF = java.util.Set.of("du", "mir", "dir", "ich", "me", "you", "mich");

	public static boolean isPrivate(String plain) {
		String low = net.minecraft.ChatFormatting.stripFormatting(plain).toLowerCase(Locale.ROOT);
		if (low.contains("[msg]") || low.contains("flüstert") || low.contains("privatnachricht") || low.startsWith("✉")) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		String me = mc.player != null ? mc.player.getGameProfile().name().toLowerCase(Locale.ROOT) : "";
		// OPSUCHT: „FREUNDE » [Du -> Name] text“ (gesendet) und „FREUNDE » [Name -> Mir] text“ (empfangen)
		java.util.regex.Matcher m = PM.matcher(low);
		while (m.find()) {
			String a = clean(m.group(1)), b = clean(m.group(2));
			if (SELF.contains(a) || SELF.contains(b) || (!me.isEmpty() && (a.equals(me) || b.equals(me)))) {
				return true;
			}
		}
		return false;
	}

	/** Rang/Zeichen vor dem Namen weg: „~Name“ → „name“, „Ultra | Name“ → „name“. */
	private static String clean(String s) {
		int bar = Math.max(s.lastIndexOf('|'), s.lastIndexOf('┃'));
		if (bar >= 0) {
			s = s.substring(bar + 1);
		}
		return s.replaceAll("[^\\p{L}\\p{N}_]", "").strip();
	}
}
