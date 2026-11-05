package de.loothub.client.quick;

import java.util.Map;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

import de.loothub.client.jobs.WidgetConfig;

/**
 * AFK-Ansicht: Nach 5 Minuten ohne Bewegung (Laufen, Springen – Umschauen mit der Maus zählt nicht) legt sich der
 * eigene Charakter langsam hin, die Kamera gleitet aus der Ich-Ansicht nach hinten und zoomt heraus – auch bei offenem
 * Chat. Bewegt man sich, steht er wieder auf und die Kamera fährt langsam zurück. Nur die eigene Darstellung – andere
 * Spieler sehen nichts davon, am Spiel ändert sich nichts.
 */
public final class AfkView {
	public static final long AFK_AFTER_MS = 5 * 60_000L;
	private static final long LIE_DOWN_MS = 1_400;
	private static final long STAND_UP_MS = 900;
	private static final long ZOOM_OUT_MS = 4_000;
	private static final long ZOOM_IN_MS = 2_000;
	/** So weit wird herausgezoomt (Vielfaches des normalen Abstands). */
	private static final float ZOOM_FACTOR = 2.5f;

	private static long lastMove = System.currentTimeMillis();
	private static double lastX = Double.NaN, lastY, lastZ;
	private static boolean sleeping;
	/** Kamera wird gerade von der Mod gesteuert (schlafend oder beim Zurückfahren). */
	private static boolean cameraManaged;
	private static CameraType previousCamera;
	private static final Transition LIE = new Transition();
	private static final Transition ZOOM = new Transition();

	/** Liege-Fortschritt je Render-Zustand (eigener Spieler und andere AFK-Spieler), nur für diesen Frame. */
	private static final Map<Object, Float> LYING = new WeakHashMap<>();

	private AfkView() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(AfkView::tick);
	}

	/** Wie weit der eigene Charakter liegt (0 = steht, 1 = liegt). */
	public static float lieProgress() {
		return LIE.eased();
	}

	public static void markLying(Object renderState, float progress) {
		if (progress > 0.001f) {
			LYING.put(renderState, progress);
		} else {
			LYING.remove(renderState);
		}
	}

	public static float lying(Object renderState) {
		Float p = LYING.get(renderState);
		return p == null ? 0f : p;
	}

	/** Faktor für den Kamera-Abstand (1 = normal). Aus der Ich-Ansicht startet er fast im Kopf, damit kein Sprung entsteht. */
	public static float zoom() {
		if (!cameraManaged) {
			return 1f;
		}
		float base = previousCamera == CameraType.FIRST_PERSON ? 0.05f : 1f;
		return base + (ZOOM_FACTOR - base) * ZOOM.eased();
	}

	private static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (mc.player == null || !WidgetConfig.get().afkView) {
			wake();
			finishCamera(mc, true);
			lastMove = now;
			lastX = Double.NaN;
			return;
		}
		if (moved(mc)) {
			lastMove = now;
			wake();
		} else if (!sleeping && now - lastMove >= AFK_AFTER_MS) {
			sleeping = true;
			if (!cameraManaged) {
				previousCamera = mc.options.getCameraType();
				cameraManaged = true;
			}
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			LIE.to(1, LIE_DOWN_MS);
			ZOOM.to(1, ZOOM_OUT_MS);
		}
		finishCamera(mc, false);
	}

	/** Nur echte Bewegung zählt: die Position hat sich geändert. Maus, Chat und Menüs nicht. */
	private static boolean moved(Minecraft mc) {
		var p = mc.player;
		boolean moved = !Double.isNaN(lastX)
				&& (Math.abs(p.getX() - lastX) > 0.01 || Math.abs(p.getY() - lastY) > 0.01 || Math.abs(p.getZ() - lastZ) > 0.01);
		lastX = p.getX();
		lastY = p.getY();
		lastZ = p.getZ();
		return moved;
	}

	private static void wake() {
		if (!sleeping) {
			return;
		}
		sleeping = false;
		LIE.to(0, STAND_UP_MS);
		ZOOM.to(0, ZOOM_IN_MS);
	}

	/** Nach dem Zurückfahren die vorherige Ansicht wiederherstellen (sofort, wenn {@code now}). */
	private static void finishCamera(Minecraft mc, boolean now) {
		if (!cameraManaged || sleeping || (!now && !ZOOM.atZero())) {
			return;
		}
		cameraManaged = false;
		if (now) {
			LIE.to(0, 1);
			ZOOM.to(0, 1);
		}
		// Nur zurückstellen, wenn der Spieler die Ansicht nicht selbst gewechselt hat
		if (previousCamera != null && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
			mc.options.setCameraType(previousCamera);
		}
		previousCamera = null;
	}
}
