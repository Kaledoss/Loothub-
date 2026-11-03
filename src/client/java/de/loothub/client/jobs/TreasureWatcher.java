package de.loothub.client.jobs;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import de.loothub.client.MerchantRates;

/**
 * Erkennt, dass man einen Jobschatz bekommen hat, und setzt den Jobschatz-Zähler auf 0:
 * über eine Server-Nachricht mit „Jobschatz“ oder wenn ohne offenes Menü ein Schatz-Item
 * (Steinplatten, Holzbündel, Gräbergemisch, Jägerbeutel … – die benannten Items des Wertstoffhändlers) dazukommt.
 */
public final class TreasureWatcher {
	private static int lastCount = -1;
	private static long lastFound;

	private TreasureWatcher() {
	}

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay && message.getString().toLowerCase(Locale.ROOT).contains("jobschatz")) {
				found();
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null || mc.player.tickCount % 10 != 0) {
				return;
			}
			int count = treasureCount(mc.player.getInventory());
			// nur ohne offenes Menü zählen (sonst wäre Umlagern aus einer Kiste ein „Fund“)
			if (lastCount >= 0 && count > lastCount && mc.screen == null) {
				found();
			}
			lastCount = count;
		});
	}

	private static boolean isTreasure(ItemStack st) {
		Component custom = st.get(DataComponents.CUSTOM_NAME);
		if (custom == null) {
			return false;
		}
		String name = custom.getString().strip();
		for (MerchantRates.Rate r : MerchantRates.rates()) {
			if (r.name() != null && r.name().strip().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	private static int treasureCount(Inventory inv) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty() && isTreasure(st)) {
				n += st.getCount();
			}
		}
		return n;
	}

	private static void found() {
		long now = System.currentTimeMillis();
		if (now - lastFound < 5000) {
			return; // Nachricht und Item zählen zusammen nur einmal
		}
		lastFound = now;
		WidgetConfig c = WidgetConfig.get();
		c.treasureXp = 0;
		c.treasuresFound++;
		c.save();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.translatable("loothub.treasure.found", c.treasuresFound)
					.withStyle(net.minecraft.ChatFormatting.GOLD), false);
		}
	}
}
