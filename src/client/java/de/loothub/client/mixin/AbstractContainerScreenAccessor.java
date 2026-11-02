package de.loothub.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lesezugriff auf Position und Größe eines Container-Bildschirms (für die Befehls-Leiste unter dem Inventar). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int loothub$leftPos();

	@Accessor("topPos")
	int loothub$topPos();

	@Accessor("imageWidth")
	int loothub$imageWidth();

	@Accessor("imageHeight")
	int loothub$imageHeight();
}
