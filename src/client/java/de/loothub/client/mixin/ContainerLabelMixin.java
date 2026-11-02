package de.loothub.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Überschriften (Menü-Titel, „Inventar“) schreibt Minecraft dunkelgrau. In den Menüs, die das
 * Loothub+-Ressourcenpaket dunkel einfärbt (Kisten/Server-Menüs, Werkbank, Amboss), werden sie hell.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerLabelMixin {
	private static final int LIGHT = 0xFFE8D8DA;

	@ModifyArg(method = "renderLabels",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
			index = 4)
	private int loothub$lightLabel(int color) {
		Object self = this;
		boolean dark = self instanceof ContainerScreen || self instanceof CraftingScreen || self instanceof AnvilScreen;
		return dark && (color & 0xFFFFFF) == 0x404040 ? LIGHT : color;
	}
}
