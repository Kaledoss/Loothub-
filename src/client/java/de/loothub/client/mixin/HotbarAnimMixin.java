package de.loothub.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.HotbarAnim;

/**
 * Hotbar-Animationen (nur Anzeige): der Auswahlrahmen (zweites Sprite der Hotbar) gleitet, Items in den Slots werden
 * je nach Ereignis verformt. Passt eine Stelle nicht zur Minecraft-Version, bleibt die Hotbar einfach wie sie ist.
 */
@Mixin(Gui.class)
public abstract class HotbarAnimMixin {
	private static final String BLIT = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V";

	@Inject(method = "renderItemHotbar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE", target = BLIT, ordinal = 1))
	private void loothub$selectionBefore(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		HotbarAnim.beforeSelection(g);
	}

	@Inject(method = "renderItemHotbar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE", target = BLIT, ordinal = 1, shift = At.Shift.AFTER))
	private void loothub$selectionAfter(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
		HotbarAnim.afterSelection(g);
	}

	@Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
			at = @At("HEAD"))
	private void loothub$slotBefore(GuiGraphics g, int x, int y, DeltaTracker delta, Player player, ItemStack stack, int seed,
			CallbackInfo ci) {
		HotbarAnim.beforeSlot(g, x, y, player, stack, seed);
	}

	@Inject(method = "renderSlot(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V",
			at = @At("RETURN"))
	private void loothub$slotAfter(GuiGraphics g, int x, int y, DeltaTracker delta, Player player, ItemStack stack, int seed,
			CallbackInfo ci) {
		HotbarAnim.afterSlot(g, seed);
	}
}
