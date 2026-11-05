package de.loothub.client.jobs;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Eingesammelte Marktverkäufe als „Job“ im Job-Verlauf: Meldungen „Du hast X $ erhalten.“ zählen nur, solange das
 * Menü „Marktplatz“ offen ist (oder gerade eben noch war) – einzeln abgeschlossene Aufträge wie „alles abholen“.
 * Mehrere Meldungen kurz hintereinander werden zu einem Eintrag zusammengefasst. Nur Mitlesen.
 */
public final class MarketSales {
	public static final String JOB = "Markt";

	private static final Pattern RECEIVED = Pattern.compile("(?i)du hast\\s+([\\d.,]+)\\s*\\$\\s+erhalten");
	/** So lange nach dem Schließen des Marktplatz-Menüs zählen Meldungen noch dazu. */
	private static final long MENU_GRACE_MS = 5_000;
	/** Ohne neue Meldung so lange warten, dann den Eintrag speichern. */
	private static final long FLUSH_AFTER_MS = 3_000;

	private static long marketSeenAt;
	private static double pending;
	private static long pendingStart;
	private static long pendingLast;

	private MarketSales() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.screen != null && mc.screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("marktplatz")) {
				marketSeenAt = System.currentTimeMillis();
			}
			if (pending > 0 && System.currentTimeMillis() - pendingLast > FLUSH_AFTER_MS) {
				flush();
			}
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				onMessage(message.getString());
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> flush());
	}

	private static void onMessage(String text) {
		long now = System.currentTimeMillis();
		if (!WidgetConfig.get().marketSales || now - marketSeenAt > MENU_GRACE_MS) {
			return; // nicht am Marktplatz – anderes Geld (Belohnung, Überweisung …)
		}
		String plain = text.replaceAll("§.", "").replaceAll("\\s+", " ");
		Matcher m = RECEIVED.matcher(plain);
		if (!m.find()) {
			return;
		}
		double amount = parse(m.group(1));
		if (Double.isNaN(amount) || amount <= 0) {
			return;
		}
		synchronized (MarketSales.class) {
			if (pending == 0) {
				pendingStart = now;
			}
			pending += amount;
			pendingLast = now;
		}
	}

	private static synchronized void flush() {
		if (pending <= 0) {
			return;
		}
		JobHistory.Session s = new JobHistory.Session();
		s.start = pendingStart;
		s.end = pendingLast;
		s.activeMs = 0; // Einsammeln ist keine Arbeitszeit
		JobHistory.JobEntry e = new JobHistory.JobEntry();
		e.job = JOB;
		e.money = pending;
		s.jobs.add(e);
		pending = 0;
		JobHistory.add(s);
	}

	/** „7.715,6“ → 7715.6 */
	static double parse(String s) {
		String t = s.strip();
		if (t.contains(",")) {
			t = t.replace(".", "").replace(',', '.');
		} else if (t.matches("\\d{1,3}(\\.\\d{3})+")) {
			t = t.replace(".", "");
		}
		try {
			return Double.parseDouble(t);
		} catch (NumberFormatException ex) {
			return Double.NaN;
		}
	}
}
