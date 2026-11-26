package de.loothub.client.quick;

import de.loothub.client.jobs.WidgetConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Warnung, wenn ein Item direkt neben einem liegt, aber nicht aufgesammelt werden kann, weil das Inventar voll ist
 * (kein freier Platz und kein passender Stapel mit Platz). Höchstens alle paar Sekunden.
 */
public final class PickupWarning {
	private static final long COOLDOWN_MS = 6000;
	/** So lange muss das Item schon in Reichweite liegen (frisch gedroppte Items haben eine kurze Aufsammel-Sperre). */
	private static final int WAIT_TICKS = 15;
	private static long lastWarn;
	private static int stuckTicks;

	private PickupWarning() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PickupWarning::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || !WidgetConfig.get().pickupWarning || mc.player.isSpectator()) {
			stuckTicks = 0;
			return;
		}
		Inventory inv = mc.player.getInventory();
		if (inv.getFreeSlot() >= 0) {
			stuckTicks = 0; // noch Platz frei: alles passt
			return;
		}
		ItemStack blocked = null;
		for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(1.0, 0.5, 1.0))) {
			ItemStack s = e.getItem();
			if (!s.isEmpty() && !fits(inv, s)) {
				blocked = s;
				break;
			}
		}
		if (blocked == null) {
			stuckTicks = 0;
			return;
		}
		if (++stuckTicks < WAIT_TICKS) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastWarn < COOLDOWN_MS) {
			return;
		}
		lastWarn = now;
		mc.gui.setOverlayMessage(Component.translatable("loothub.pickup_warning", blocked.getHoverName())
				.withStyle(ChatFormatting.RED), false);
		de.loothub.client.Sounds.play(de.loothub.client.Sounds.Group.PROTECT, SoundEvents.NOTE_BLOCK_BASS.value(), 0.7F, 0.6F);
	}

	/** Passt das Item noch auf einen vorhandenen Stapel? */
	private static boolean fits(Inventory inv, ItemStack s) {
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack have = inv.getItem(i);
			if (ItemStack.isSameItemSameComponents(have, s) && have.getCount() < have.getMaxStackSize()) {
				return true;
			}
		}
		ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
		return ItemStack.isSameItemSameComponents(off, s) && off.getCount() < off.getMaxStackSize();
	}
}
