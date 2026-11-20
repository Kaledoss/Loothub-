package de.loothub.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.DropProtection;

/** Wegwerfen im Inventar (Q über dem Slot oder Item aus dem Fenster ziehen): erst beim zweiten Mal. */
@Mixin(AbstractContainerScreen.class)
public abstract class DropProtectionScreenMixin {
	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void loothub$protect(@Nullable Slot slot, int slotId, int button, ClickType type, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (type == ClickType.PICKUP && slot == null && slotId == -999 && mc.player != null) {
			// getragenes Item aus dem Fenster ziehen: links alles, rechts eins
			ItemStack carried = mc.player.containerMenu.getCarried();
			de.loothub.client.stats.StatsTracker.onDropped(carried, button == 0 ? carried.getCount() : 1);
			return;
		}
		if (type != ClickType.THROW) {
			return;
		}
		ItemStack st;
		if (slot != null) {
			st = slot.getItem();
		} else {
			st = mc.player != null ? mc.player.containerMenu.getCarried() : ItemStack.EMPTY;
		}
		if (!DropProtection.allow(st)) {
			ci.cancel();
			return;
		}
		// Q über dem Slot: eins, Strg+Q: ganzer Stapel
		de.loothub.client.stats.StatsTracker.onDropped(st, slot != null && button == 1 ? st.getCount() : slot != null ? 1 : st.getCount());
	}
}
