package de.loothub.client.quick;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import de.loothub.client.jobs.WidgetConfig;

/**
 * AFK-Ansicht: Nach 5 Minuten ohne Eingabe (so lange, bis der Server einen als AFK führt) wechselt die Kamera aus der
 * Ich-Ansicht nach hinten, zoomt langsam heraus und der eigene Charakter liegt schlafend da. Jede Eingabe weckt ihn
 * wieder auf. Nur die eigene Darstellung – andere Spieler sehen nichts davon, am Spiel ändert sich nichts.
 */
public final class AfkView {
	public static final long AFK_AFTER_MS = 5 * 60_000L;
	/** So lange dauert das Herauszoomen. */
	private static final long ZOOM_MS = 4_000;
	/** So weit wird herausgezoomt (Vielfaches des normalen Abstands). */
	private static final float ZOOM_FACTOR = 2.5f;

	private static long lastInput = System.currentTimeMillis();
	private static double lastMouseX = Double.NaN, lastMouseY = Double.NaN;
	private static boolean sleeping;
	private static long sleepStart;
	private static CameraType previousCamera;

	private AfkView() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(AfkView::tick);
	}

	public static boolean sleeping() {
		return sleeping;
	}

	/** Faktor für den Kamera-Abstand (1 = normal), sanft eingeblendet. */
	public static float zoom() {
		if (!sleeping) {
			return 1f;
		}
		float t = Math.min(1f, (System.currentTimeMillis() - sleepStart) / (float) ZOOM_MS);
		float ease = t * t * (3 - 2 * t); // weich an- und auslaufen
		return 1f + (ZOOM_FACTOR - 1f) * ease;
	}

	private static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (mc.player == null || !WidgetConfig.get().afkView) {
			wake(mc);
			lastInput = now;
			return;
		}
		if (hadInput(mc)) {
			lastInput = now;
			wake(mc);
			return;
		}
		if (!sleeping && mc.screen == null && now - lastInput >= AFK_AFTER_MS) {
			sleeping = true;
			sleepStart = now;
			previousCamera = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
	}

	/** Maus bewegt, Taste gedrückt oder ein Fenster (Chat, Inventar …) offen? */
	private static boolean hadInput(Minecraft mc) {
		double mx = mc.mouseHandler.xpos();
		double my = mc.mouseHandler.ypos();
		boolean moved = !Double.isNaN(lastMouseX) && (Math.abs(mx - lastMouseX) > 0.5 || Math.abs(my - lastMouseY) > 0.5);
		lastMouseX = mx;
		lastMouseY = my;
		if (moved || mc.screen != null) {
			return true;
		}
		for (KeyMapping k : mc.options.keyMappings) {
			if (k.isDown()) {
				return true;
			}
		}
		return false;
	}

	private static void wake(Minecraft mc) {
		if (!sleeping) {
			return;
		}
		sleeping = false;
		// Nur zurückstellen, wenn der Spieler die Ansicht nicht selbst gewechselt hat
		if (previousCamera != null && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
			mc.options.setCameraType(previousCamera);
		}
		previousCamera = null;
	}
}
