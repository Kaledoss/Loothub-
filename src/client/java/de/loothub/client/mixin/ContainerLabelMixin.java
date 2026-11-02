package de.loothub.client.mixin;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
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

	/**
	 * Server wie OPSUCHT malen beim Amboss/der Werkbank ein eigenes Hintergrundbild über Sonderzeichen in einer
	 * eigenen Schrift im Titel. Das würde unsere schwarz-rote Textur verdecken – deshalb bleibt dort nur der
	 * normale Text des Titels stehen (oder „Amboss“/„Werkbank“, wenn nichts übrig bleibt).
	 */
	@ModifyArg(method = "renderLabels",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
			index = 1)
	private Component loothub$plainTitle(Component text) {
		Object self = this;
		if (!(self instanceof AnvilScreen) && !(self instanceof CraftingScreen)) {
			return text;
		}
		StringBuilder plain = new StringBuilder();
		boolean[] special = {false};
		Object defaultFont = Style.EMPTY.getFont();
		text.visit((style, part) -> {
			if (!Objects.equals(style.getFont(), defaultFont)) {
				special[0] = true;
				return Optional.empty();
			}
			part.codePoints().forEach(cp -> {
				boolean privateUse = (cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000;
				if (privateUse) {
					special[0] = true;
				} else {
					plain.appendCodePoint(cp);
				}
			});
			return Optional.empty();
		}, Style.EMPTY);
		if (!special[0]) {
			return text;
		}
		String clean = plain.toString().trim();
		if (clean.isEmpty()) {
			clean = self instanceof AnvilScreen ? "Amboss" : "Werkbank";
		}
		return Component.literal(clean);
	}
}
