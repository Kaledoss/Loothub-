package de.loothub.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.SellHighlight;

/** Kisten und Server-Menüs: nach der Menü-Textur verkaufbare Items grün hinterlegen (unter den Items). */
@Mixin(ContainerScreen.class)
public abstract class ContainerScreenMixin {
	@Inject(method = "renderBg", at = @At("TAIL"))
	private void loothub$afterBg(GuiGraphics g, float delta, int mouseX, int mouseY, CallbackInfo ci) {
		SellHighlight.draw((ContainerScreen) (Object) this, g);
	}
}
