package de.loothub.client.mixin;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import de.loothub.client.quick.AfkView;

/** AFK-Ansicht: Kamera-Abstand in der Ansicht von hinten langsam vergrößern. */
@Mixin(Camera.class)
public abstract class CameraZoomMixin {
	@ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"), index = 0)
	private float loothub$afkZoom(float distance) {
		return distance * AfkView.zoom();
	}
}
