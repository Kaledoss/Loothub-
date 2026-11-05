package de.loothub.client.quick;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

import de.loothub.client.jobs.WidgetConfig;

/**
 * AFK-Ansicht: Nach 5 Minuten ohne Bewegung (so lange, bis der Server einen als AFK führt) wechselt die Kamera aus der
 * Ich-Ansicht nach hinten, zoomt langsam heraus und der eigene Charakter liegt schlafend da – auch bei offenem Chat.
 * Erst eine Bewegung (Laufen oder Umschauen) weckt ihn
 * wieder auf. Nur die eigene Darstellung – andere Spieler sehen nichts davon, am Spiel ändert sich nichts.
 */
public final class AfkView {
	public static final long AFK_AFTER_MS = 5 * 60_000L;
	/** So lange dauert das Herauszoomen. */
	private static final long ZOOM_MS = 4_000;
	/** So weit wird herausgezoomt (Vielfaches des normalen Abstands). */
	private static final float ZOOM_FACTOR = 2.5f;

	private static long lastInput = System.currentTimeMillis();
	private static double lastX = Double.NaN, lastY, lastZ;
	private static float lastYRot, lastXRot;
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
		if (!sleeping && now - lastInput >= AFK_AFTER_MS) {
			sleeping = true;
			sleepStart = now;
			previousCamera = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
	}

	/**
	 * Nur echte Bewegung zählt: Position oder Blickrichtung hat sich geändert. Chat tippen, Menüs öffnen oder Tasten
	 * ohne Bewegung halten den Charakter nicht wach.
	 */
	private static boolean hadInput(Minecraft mc) {
		var p = mc.player;
		boolean moved = !Double.isNaN(lastX)
				&& (Math.abs(p.getX() - lastX) > 0.01 || Math.abs(p.getY() - lastY) > 0.01 || Math.abs(p.getZ() - lastZ) > 0.01
						|| Math.abs(p.getYRot() - lastYRot) > 0.5 || Math.abs(p.getXRot() - lastXRot) > 0.5);
		lastX = p.getX();
		lastY = p.getY();
		lastZ = p.getZ();
		lastYRot = p.getYRot();
		lastXRot = p.getXRot();
		return moved;
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
