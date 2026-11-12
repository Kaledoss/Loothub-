package de.loothub.client.cosmetics;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Begleiter in der Ego-Perspektive: Der eigene Spieler wird dann zwar mitgezeichnet, aber unsichtbar und ohne Rüstung,
 * Gegenstände, Umhang, Schatten und alle Cosmetics am Körper – übrig bleiben nur Haustiere und die freien Mini-Mes
 * (fliegend, krabbelnd). Die Mini-Mes auf den Schultern bleiben weg, die säßen direkt vor der Kamera.
 */
public final class FirstPerson {
	private static final Set<Object> MARKED = Collections.newSetFromMap(new WeakHashMap<>());

	private FirstPerson() {
	}

	/** Soll der eigene Spieler in der Ego-Perspektive für die Begleiter mitgezeichnet werden? */
	public static boolean active() {
		Minecraft mc = Minecraft.getInstance();
		WidgetConfig c = WidgetConfig.get();
		return c.cosmeticsFirstPerson && c.cosmetics && mc.player != null && !mc.player.isSpectator()
				&& !mc.player.isSleeping() && mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player;
	}

	/** Ist das der unsichtbare eigene Spieler in der Ego-Perspektive? */
	public static boolean is(Object state) {
		return state != null && MARKED.contains(state);
	}

	/** Beim Vorbereiten: alles am Körper ausblenden, nur die Begleiter-Layer zeichnen dann noch. */
	public static void strip(AvatarRenderState s) {
		MARKED.add(s);
		s.isInvisible = true;
		s.isInvisibleToPlayer = true;
		s.outlineColor = 0;
		s.shadowRadius = 0.0F;
		s.shadowPieces.clear();
		s.nameTag = null;
		s.scoreText = null;
		s.displayFireAnimation = false;
		s.headEquipment = ItemStack.EMPTY;
		s.chestEquipment = ItemStack.EMPTY;
		s.legsEquipment = ItemStack.EMPTY;
		s.feetEquipment = ItemStack.EMPTY;
		s.headItem.clear();
		s.leftHandItemState.clear();
		s.rightHandItemState.clear();
		s.showCape = false;
		s.parrotOnLeftShoulder = null;
		s.parrotOnRightShoulder = null;
		s.arrowCount = 0;
		s.stingerCount = 0;
		s.showExtraEars = false;
	}

	/** Nicht markierte Zustände (z. B. nach dem Umschalten) wieder freigeben. */
	public static void unmark(Object state) {
		MARKED.remove(state);
	}
}
