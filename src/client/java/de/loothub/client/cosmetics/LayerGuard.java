package de.loothub.client.cosmetics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import de.loothub.Loothub;

/**
 * Absturzschutz für die Cosmetics-Darstellung: Wirft eine Ebene (Mini-Mes, Flügel, Haustiere …) einen Fehler, wird
 * nur diese Ebene bis zum Neustart abgeschaltet – das Spiel läuft weiter. Der Fehler landet einmal im Log, dazu ein
 * kurzer Hinweis im Chat.
 */
public final class LayerGuard {
	private static final Set<String> OFF = ConcurrentHashMap.newKeySet();

	private LayerGuard() {
	}

	/** Ist diese Ebene wegen eines Fehlers abgeschaltet? */
	public static boolean off(String layer) {
		return OFF.contains(layer);
	}

	public static void fail(String layer, Throwable t) {
		if (!OFF.add(layer)) {
			return;
		}
		Loothub.LOGGER.error("Loothub: Darstellung „{}“ nach einem Fehler abgeschaltet", layer, t);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal("[Loothub+] ").withStyle(ChatFormatting.GOLD)
						.append(Component.translatable("loothub.guard.layer_off", layer).withStyle(ChatFormatting.GRAY)),
						false);
			}
		});
	}
}
