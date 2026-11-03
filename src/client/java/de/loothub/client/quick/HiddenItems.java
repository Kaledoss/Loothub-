package de.loothub.client.quick;

import java.util.Locale;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.WidgetConfig;

/** Welche eigenen Items nur optisch ausgeblendet werden (Wirkung bleibt). */
public final class HiddenItems {
	private HiddenItems() {
	}

	/** Talismanrucksack in der Zweithand ausblenden? */
	public static boolean hideOffhand(ItemStack stack) {
		if (!WidgetConfig.get().hideOffhandBackpack || stack.isEmpty()) {
			return false;
		}
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		String n = (name != null ? name.getString() : stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
		return n.contains("talismanrucksack");
	}
}
