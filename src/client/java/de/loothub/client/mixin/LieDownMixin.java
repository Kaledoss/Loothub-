package de.loothub.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.AfkView;

/** AFK-Ansicht (hinlegen/aufstehen) und Flug-Haltung (nur Darstellung). */
@Mixin(LivingEntityRenderer.class)
public abstract class LieDownMixin {
	@Inject(method = "setupRotations", at = @At("TAIL"))
	private void loothub$lieDown(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float scale, CallbackInfo ci) {
		// AFK: auf den Rücken legen; Flug: waagerecht nach vorn kippen
		de.loothub.client.cosmetics.BodyPose.apply(poseStack, AfkView.lying(state),
				de.loothub.client.cosmetics.Motion.fly(state));
	}
}
