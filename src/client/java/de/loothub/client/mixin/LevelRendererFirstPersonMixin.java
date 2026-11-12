package de.loothub.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * Ego-Perspektive mit Begleitern: den eigenen Spieler beim Einsammeln der sichtbaren Wesen nicht überspringen. Er wird
 * dann unsichtbar vorbereitet (siehe {@code FirstPerson}), sodass nur seine Haustiere und freien Mini-Mes erscheinen.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFirstPersonMixin {
	@ModifyExpressionValue(method = "extractVisibleEntities",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z"))
	private boolean loothub$renderSelf(boolean detached) {
		return detached || de.loothub.client.cosmetics.FirstPerson.active();
	}
}
