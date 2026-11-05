package de.loothub.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.AfkView;

/** AFK-Ansicht: Spieler langsam auf den Rücken legen bzw. wieder aufrichten (nur Darstellung). */
@Mixin(LivingEntityRenderer.class)
public abstract class LieDownMixin {
	@Inject(method = "setupRotations", at = @At("TAIL"))
	private void loothub$lieDown(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float scale, CallbackInfo ci) {
		float p = AfkView.lying(state);
		if (p <= 0f) {
			return;
		}
		// etwas anheben, damit der Körper nicht im Boden liegt, dann um die Füße nach hinten kippen
		poseStack.translate(0.0F, 0.2F * p, 0.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(90.0F * p));
	}
}
