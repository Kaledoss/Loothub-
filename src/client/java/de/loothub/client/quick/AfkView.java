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
	/** Anime-Hinlegen: strecken und gähnen, nach hinten plumpsen, kurz nachfedern, Hände hinter den Kopf. */
	private static final long LIE_DOWN_MS = 2_600;
	/** Anime-Aufstehen (Kip-up): Knie anziehen, aus dem Liegen hochfedern, sicher landen. */
	private static final long STAND_UP_MS = 1_000;
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

	/** Haltung je Render-Zustand (eigener Spieler und andere AFK-Spieler), nur für diesen Frame. */
	private static final Map<Object, float[]> LYING = new WeakHashMap<>();
	/** Indizes in der Haltung: Kippwinkel nach hinten (Grad), Anheben (Blöcke), Strecken, Hände hinter Kopf, Knie. */
	public static final int PITCH = 0, LIFT = 1, STRETCH = 2, HANDS = 3, KNEES = 4;
	private static long downStart, upStart;
	private static float upFromPitch, upFromHands;

	private AfkView() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(AfkView::tick);
	}

	/** Wie weit der eigene Charakter liegt (0 = steht, 1 = liegt). */
	public static float lieProgress() {
		return Math.max(0f, Math.min(1f, ownPose()[PITCH] / 90f));
	}

	/** Einfache Liege-Haltung (andere AFK-Spieler): gleichmäßig auf den Rücken. */
	public static void markLying(Object renderState, float progress) {
		if (progress > 0.001f) {
			LYING.put(renderState, new float[]{90f * progress, 0f, 0f, progress, 0f});
		} else {
			LYING.remove(renderState);
		}
	}

	/** Eigene Anime-Haltung an den Render-Zustand hängen. */
	public static void markOwn(Object renderState) {
		float[] p = ownPose();
		if (p[PITCH] != 0f || p[LIFT] != 0f || p[STRETCH] > 0f || p[KNEES] > 0f) {
			LYING.put(renderState, p);
		} else {
			LYING.remove(renderState);
		}
	}

	/** Wie weit dieser Spieler liegt (0..1). */
	public static float lying(Object renderState) {
		float[] p = LYING.get(renderState);
		return p == null ? 0f : Math.max(0f, Math.min(1f, p[PITCH] / 90f));
	}

	/** Volle Haltung (oder null): {Kippwinkel, Anheben, Strecken, Hände hinter Kopf, Knie}. */
	public static float[] pose(Object renderState) {
		return LYING.get(renderState);
	}

	private static float smooth(float x) {
		x = Math.max(0f, Math.min(1f, x));
		return x * x * (3f - 2f * x);
	}

	private static float easeOut(float x) {
		x = Math.max(0f, Math.min(1f, x));
		return 1f - (1f - x) * (1f - x);
	}

	/** Haltung des eigenen Charakters jetzt. */
	private static float[] ownPose() {
		long now = System.currentTimeMillis();
		float[] p = new float[5];
		if (sleeping) {
			float t = Math.min(1f, (now - downStart) / (float) LIE_DOWN_MS);
			// 1) strecken und gähnen, leicht nach hinten lehnen
			p[STRETCH] = t < 0.4f ? (float) Math.sin(Math.PI * t / 0.4f) : 0f;
			if (t < 0.35f) {
				p[PITCH] = 8f * smooth(t / 0.35f);
			} else if (t < 0.75f) {
				// 2) nach hinten plumpsen (wird schneller, wie fallen)
				float u = (t - 0.35f) / 0.4f;
				p[PITCH] = 8f + 82f * u * u;
			} else {
				// 3) aufkommen, kurz nachfedern, Hände hinter den Kopf
				float v = (t - 0.75f) / 0.25f;
				p[PITCH] = 90f;
				p[LIFT] = (float) Math.sin(Math.PI * v) * 0.12f * (1f - v);
				p[HANDS] = smooth(v);
			}
			if (t >= 1f) {
				// liegt: ruhiges Atmen
				p[LIFT] = (float) Math.sin(now / 900.0) * 0.01f;
			}
			return p;
		}
		float t = (now - upStart) / (float) STAND_UP_MS;
		if (upStart == 0 || t >= 1f) {
			return p; // steht
		}
		float p0 = upFromPitch;
		if (p0 < 30f) {
			// lag kaum: einfach zurück aufrichten
			p[PITCH] = p0 * (1f - smooth(t));
			p[HANDS] = upFromHands * (1f - smooth(t));
			return p;
		}
		if (t < 0.25f) {
			// Knie anziehen, nach hinten Schwung holen
			float u = t / 0.25f;
			p[KNEES] = smooth(u);
			p[HANDS] = upFromHands * (1f - smooth(u));
			p[PITCH] = p0 + 15f * smooth(u);
		} else if (t < 0.7f) {
			// hochfedern: in einem Bogen auf die Füße, leicht über die Senkrechte hinaus
			float u = (t - 0.25f) / 0.45f;
			p[PITCH] = (p0 + 15f) + (-12f - (p0 + 15f)) * easeOut(u);
			p[LIFT] = (float) Math.sin(Math.PI * u) * 0.7f;
			p[KNEES] = 1f - smooth(u);
		} else {
			// landen: kurz einfedern
			float u = (t - 0.7f) / 0.3f;
			p[PITCH] = -12f * (1f - easeOut(u));
			p[LIFT] = -(float) Math.sin(Math.PI * u) * 0.08f;
		}
		return p;
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
		if (moved(mc) || fishing(mc.player)) {
			lastMove = now;
			wake();
		} else if (!sleeping && now - lastMove >= AFK_AFTER_MS) {
			sleeping = true;
			if (!cameraManaged) {
				previousCamera = mc.options.getCameraType();
				cameraManaged = true;
			}
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			downStart = now;
			upStart = 0;
			LIE.to(1, LIE_DOWN_MS);
			ZOOM.to(1, ZOOM_OUT_MS);
		}
		finishCamera(mc, false);
	}

	/** Wer eine Angel in der Hand hält, angelt – nicht AFK. */
	public static boolean fishing(net.minecraft.world.entity.LivingEntity p) {
		return p != null && (p.getMainHandItem().is(net.minecraft.world.item.Items.FISHING_ROD)
				|| p.getOffhandItem().is(net.minecraft.world.item.Items.FISHING_ROD));
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
		float[] cur = ownPose();
		upFromPitch = cur[PITCH];
		upFromHands = cur[HANDS];
		sleeping = false;
		upStart = System.currentTimeMillis();
		LIE.to(0, STAND_UP_MS);
		ZOOM.to(0, ZOOM_IN_MS);
		de.loothub.client.cosmetics.Emotes.welcomeBack(STAND_UP_MS + 300L);
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
			upStart = 0;
		}
		// Nur zurückstellen, wenn der Spieler die Ansicht nicht selbst gewechselt hat
		if (previousCamera != null && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
			mc.options.setCameraType(previousCamera);
		}
		previousCamera = null;
	}
}
