package de.loothub.client.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.jobs.JobTracker;

/**
 * Jede Actionbar-Nachricht landet in {@link Gui#setOverlayMessage} – egal, über welches Paket der Server sie schickt.
 * Wir lesen sie nur mit; die Anzeige selbst bleibt unverändert.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "setOverlayMessage", at = @At("HEAD"))
	private void loothub$onOverlayMessage(Component message, boolean animateColor, CallbackInfo ci) {
		if (message != null) {
			JobTracker.onActionbar(message.getString());
		}
	}
}
