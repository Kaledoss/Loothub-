package de.loothub.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.InventoryTitle;

/**
 * Spieler-Inventar: Werkbank-/Amboss-Knöpfe direkt nach der Inventar-Textur zeichnen (also unter Items
 * und Tooltips) und statt „Crafting/Handwerk“ die Überschrift „Loothub+“ schreiben.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@Inject(method = "renderBg", at = @At("TAIL"))
	private void loothub$afterBg(GuiGraphics g, float delta, int mouseX, int mouseY, CallbackInfo ci) {
		if (!de.loothub.client.jobs.WidgetConfig.get().inventoryDesign) {
			return;
		}
		InventoryTitle.drawOverlay((InventoryScreen) (Object) this, g, mouseX, mouseY);
	}

	@Inject(method = "renderLabels", at = @At("HEAD"), cancellable = true)
	private void loothub$labels(GuiGraphics g, int mouseX, int mouseY, CallbackInfo ci) {
		if (!de.loothub.client.jobs.WidgetConfig.get().inventoryDesign) {
			return;
		}
		InventoryTitle.drawLabel(g);
		ci.cancel();
	}
}
