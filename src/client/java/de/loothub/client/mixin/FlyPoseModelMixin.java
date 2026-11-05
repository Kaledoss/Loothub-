package de.loothub.client.mixin;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.cosmetics.Motion;

/**
 * Flug-Haltung: Kopf schaut nach vorn, ein Arm voraus, der andere am Körper, Beine gestreckt. Beim Abbauen,
 * Zuschlagen oder Benutzen bleiben die Arme wie gewohnt (nur Darstellung).
 */
@Mixin(PlayerModel.class)
public abstract class FlyPoseModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void loothub$flyPose(AvatarRenderState state, CallbackInfo ci) {
		float f = Motion.fly(state);
		if (f <= 0.0F) {
			return;
		}
		PlayerModel m = (PlayerModel) (Object) this;
		float t = state.ageInTicks;
		m.head.xRot = lerp(f, m.head.xRot, (float) Math.toRadians(-70.0));
		boolean busy = state.attackTime > 0.0F || state.isUsingItem;
		if (!busy) {
			// rechter Arm voraus (Faust nach vorn), linker angelegt; leichtes Flattern im Fahrtwind
			m.rightArm.xRot = lerp(f, m.rightArm.xRot, (float) Math.toRadians(-170.0) + (float) Math.sin(t * 0.3F) * 0.03F);
			m.rightArm.zRot = lerp(f, m.rightArm.zRot, 0.08F);
			m.leftArm.xRot = lerp(f, m.leftArm.xRot, 0.15F + (float) Math.sin(t * 0.35F) * 0.04F);
			m.leftArm.zRot = lerp(f, m.leftArm.zRot, -0.12F);
		}
		m.rightLeg.xRot = lerp(f, m.rightLeg.xRot, 0.12F + (float) Math.sin(t * 0.25F) * 0.05F);
		m.leftLeg.xRot = lerp(f, m.leftLeg.xRot, 0.02F - (float) Math.sin(t * 0.25F) * 0.05F);
		m.rightLeg.zRot = lerp(f, m.rightLeg.zRot, 0.03F);
		m.leftLeg.zRot = lerp(f, m.leftLeg.zRot, -0.03F);
	}

	private static float lerp(float f, float a, float b) {
		return a + (b - a) * f;
	}
}
