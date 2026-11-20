package de.loothub.client.mixin;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import de.loothub.client.quick.DropProtection;

/** Q in der Welt: wertvolle Items erst beim zweiten Druck fallen lassen. */
@Mixin(LocalPlayer.class)
public abstract class DropProtectionMixin {
	@Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
	private void loothub$protect(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		var held = self.getInventory().getSelectedItem();
		if (!DropProtection.allow(held)) {
			cir.setReturnValue(false);
			return;
		}
		de.loothub.client.stats.StatsTracker.onDropped(held, fullStack ? held.getCount() : 1);
	}
}
