package de.loothub.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.InvAnim;

/**
 * Lebendige Inventare (nur Anzeige): Slots und das getragene Item werden beim Zeichnen leicht verformt. Je nach
 * Minecraft-Version heißt die Slot-Methode etwas anders – es greift nur die passende, sonst bleibt alles wie es ist.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InvAnimMixin {
	@Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;)V", at = @At("HEAD"))
	private void loothub$slotBefore(GuiGraphics g, Slot slot, CallbackInfo ci) {
		InvAnim.beforeSlot(g, slot);
	}

	@Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;)V", at = @At("RETURN"))
	private void loothub$slotAfter(GuiGraphics g, Slot slot, CallbackInfo ci) {
		InvAnim.afterSlot(g, slot);
	}

	@Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;II)V", at = @At("HEAD"))
	private void loothub$slotBefore2(GuiGraphics g, Slot slot, int mx, int my, CallbackInfo ci) {
		InvAnim.beforeSlot(g, slot);
	}

	@Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/inventory/Slot;II)V", at = @At("RETURN"))
	private void loothub$slotAfter2(GuiGraphics g, Slot slot, int mx, int my, CallbackInfo ci) {
		InvAnim.afterSlot(g, slot);
	}

	@Inject(method = "renderFloatingItem(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
			at = @At("HEAD"))
	private void loothub$carriedBefore(GuiGraphics g, ItemStack stack, int x, int y, String text, CallbackInfo ci) {
		InvAnim.beforeCarried(g, x, y);
	}

	@Inject(method = "renderFloatingItem(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
			at = @At("RETURN"))
	private void loothub$carriedAfter(GuiGraphics g, ItemStack stack, int x, int y, String text, CallbackInfo ci) {
		InvAnim.afterCarried(g);
	}
}
