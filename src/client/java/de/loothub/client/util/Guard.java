package de.loothub.client.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Fehlerschutz: Jede Loothub-Funktion, die jeden Tick oder jedes Bild läuft, wird abgesichert. Wirft sie einen Fehler,
 * läuft das Spiel normal weiter; der Fehler landet im Log (mit Funktionsname). Passiert es wiederholt (3× in einer
 * Minute), wird nur diese Funktion bis zum Neustart pausiert, der Spieler bekommt einen kurzen Hinweis, und eine
 * automatische Meldung geht an die Admins (Feedback-Liste).
 */
public final class Guard {
	private static final int LIMIT = 3;
	private static final long WINDOW_MS = 60_000;
	private static final Map<String, long[]> ERRORS = new ConcurrentHashMap<>(); // {Anzahl, Fensterbeginn}
	private static final Map<String, Boolean> PAUSED = new ConcurrentHashMap<>();

	private Guard() {
	}

	/** Abgesicherter Tick-Handler. */
	public static void tick(String name, ClientTickEvents.EndTick handler) {
		final String label = "Tick: " + name;
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (PAUSED.containsKey(name)) {
				return;
			}
			long t0 = Perf.start();
			try {
				handler.onEndTick(mc);
			} catch (Throwable t) {
				failed(name, t);
			}
			Perf.end(label, t0);
		});
	}

	/** Abgesichertes HUD-Element. */
	public static HudElement hud(String name, HudElement element) {
		final String label = "HUD: " + name;
		return (g, delta) -> {
			if (PAUSED.containsKey(name)) {
				return;
			}
			long t0 = Perf.start();
			try {
				element.render(g, delta);
			} catch (Throwable t) {
				failed(name, t);
			}
			Perf.end(label, t0);
		};
	}

	/** Beliebige Aufgabe absichern (z. B. aus einem Ereignis). */
	public static void run(String name, Runnable r) {
		if (PAUSED.containsKey(name)) {
			return;
		}
		try {
			r.run();
		} catch (Throwable t) {
			failed(name, t);
		}
	}

	public static boolean paused(String name) {
		return PAUSED.containsKey(name);
	}

	private static void failed(String name, Throwable t) {
		long now = System.currentTimeMillis();
		long[] e = ERRORS.computeIfAbsent(name, k -> new long[]{0, now});
		if (now - e[1] > WINDOW_MS) {
			e[0] = 0;
			e[1] = now;
		}
		e[0]++;
		if (e[0] == 1) {
			de.loothub.Loothub.LOGGER.error("[Loothub-Fehler] {}: {}", name, t.toString(), t);
		}
		if (e[0] < LIMIT || PAUSED.putIfAbsent(name, Boolean.TRUE) != null) {
			return;
		}
		de.loothub.Loothub.LOGGER.error("[Loothub-Fehler] {} pausiert nach {} Fehlern in einer Minute", name, LIMIT);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.translatable("loothub.guard.paused", name).withStyle(ChatFormatting.GOLD), false);
			}
			de.loothub.client.feedback.Feedback.autoReport(name, t);
		});
	}
}
