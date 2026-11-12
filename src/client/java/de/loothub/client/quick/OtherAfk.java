package de.loothub.client.quick;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Andere Spieler, die sich 5 Minuten lang nicht von der Stelle bewegt haben (Umschauen zählt nicht), legen sich schlafend
 * dargestellt. Die Mod sieht dafür nur, was der Server ohnehin schickt – kein Austausch zwischen Mods, nur Anzeige.
 * Gezählt wird ab dem Moment, in dem der Spieler in Sichtweite ist.
 */
public final class OtherAfk {
	private static final class Track {
		double x, y, z;
		float yRot, xRot, headRot;
		long still;
		final Transition lie = new Transition();
	}

	private static final Map<UUID, Track> TRACKS = new HashMap<>();
	private static int ticks;

	private OtherAfk() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(OtherAfk::tick);
	}

	private static void tick(Minecraft mc) {
		if (++ticks % 20 != 0) {
			return;
		}
		if (mc.level == null || !WidgetConfig.get().afkView) {
			TRACKS.clear();
			return;
		}
		long now = System.currentTimeMillis();
		Set<UUID> seen = new HashSet<>();
		for (Player p : mc.level.players()) {
			if (p == mc.player) {
				continue;
			}
			seen.add(p.getUUID());
			Track t = TRACKS.get(p.getUUID());
			if (t == null) {
				t = new Track();
				t.still = now;
				TRACKS.put(p.getUUID(), t);
			} else if (Math.abs(p.getX() - t.x) > 0.01 || Math.abs(p.getY() - t.y) > 0.01 || Math.abs(p.getZ() - t.z) > 0.01
					|| AfkView.fishing(p)) {
				t.still = now; // nur Laufen/Springen zählt, Umschauen nicht
				t.lie.to(0, 900);
			} else if (now - t.still >= AfkView.AFK_AFTER_MS) {
				t.lie.to(1, 1_400);
			}
			t.x = p.getX();
			t.y = p.getY();
			t.z = p.getZ();
			t.yRot = p.getYRot();
			t.xRot = p.getXRot();
			t.headRot = p.getYHeadRot();
		}
		TRACKS.keySet().retainAll(seen);
	}

	/** Wie weit liegt dieser (andere) Spieler gerade (0 = steht, 1 = liegt; mit Hinlege-/Aufsteh-Animation)? */
	public static float lieProgress(LivingEntity e) {
		if (!(e instanceof Player) || !WidgetConfig.get().afkView) {
			return 0f;
		}
		Track t = TRACKS.get(e.getUUID());
		return t == null ? 0f : t.lie.eased();
	}

	/** Steht dieser (andere) Spieler seit 5 Minuten völlig still? */
	public static boolean isAfk(LivingEntity e) {
		if (!(e instanceof Player) || !WidgetConfig.get().afkView) {
			return false;
		}
		Track t = TRACKS.get(e.getUUID());
		return t != null && System.currentTimeMillis() - t.still >= AfkView.AFK_AFTER_MS;
	}
}
