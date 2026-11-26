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
			String title = mc.screen == null ? "" : mc.screen.getTitle().getString().toLowerCase(Locale.ROOT);
			// Marktplatz und das /sell-Menü (Verkaufen) – das Geld daraus landet beides im Markt-Verlauf
			if (mc.screen != null && (title.contains("marktplatz") || title.contains("verkauf") || title.contains("sell"))
					&& !title.contains("fanglager") && !title.contains("rucksack")) {
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
		Double found = de.loothub.client.util.Parse.marketAmount(plain);
		if (found == null) {
			return;
		}
		double amount = found;
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
		return de.loothub.client.util.Parse.money(s);
	}
}
