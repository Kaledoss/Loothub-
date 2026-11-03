package de.loothub.client.mixin;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.HiddenItems;

/** Ich-Ansicht: Talismanrucksack in der Zweithand nicht zeichnen (nur Anzeige). */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	@Shadow
	private ItemStack offHandItem;

	@Inject(method = "tick", at = @At("TAIL"))
	private void loothub$hideOffhand(CallbackInfo ci) {
		if (HiddenItems.hideOffhand(offHandItem)) {
			offHandItem = ItemStack.EMPTY;
		}
	}
}
