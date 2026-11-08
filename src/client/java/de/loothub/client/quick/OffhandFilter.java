package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Offhand-Filter: gewählte Items in der zweiten Hand werden per Rechtsklick nicht mehr benutzt (z. B. die Angel
 * wird nicht ausgeworfen, Blöcke nicht gesetzt). Nur das eigene Klicken wird zurückgehalten – nichts wird ausgelöst.
 * Modus „nur gewählte sperren“ oder „alles sperren außer gewählte“.
 */
public final class OffhandFilter {
	private static long lastHint;

	private OffhandFilter() {
	}

	public static List<String> items() {
		WidgetConfig c = WidgetConfig.get();
		if (c.offhandItems == null) {
			c.offhandItems = new ArrayList<>(List.of("minecraft:fishing_rod"));
		}
		return c.offhandItems;
	}

	public static String id(ItemStack st) {
		return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
	}

	/** Soll die Benutzung mit dieser Hand verhindert werden? */
	public static boolean blocks(InteractionHand hand) {
		WidgetConfig c = WidgetConfig.get();
		Minecraft mc = Minecraft.getInstance();
		if (!c.offhandFilter || hand != InteractionHand.OFF_HAND || mc.player == null) {
			return false;
		}
		ItemStack st = mc.player.getOffhandItem();
		if (st.isEmpty()) {
			return false;
		}
		boolean listed = items().contains(id(st));
		boolean block = c.offhandAllExcept ? !listed : listed;
		if (block) {
			long now = System.currentTimeMillis();
			if (now - lastHint > 3000) {
				lastHint = now;
				mc.gui.setOverlayMessage(Component.translatable("loothub.offhand.blocked", st.getHoverName())
						.withStyle(ChatFormatting.GRAY), false);
			}
		}
		return block;
	}
}
