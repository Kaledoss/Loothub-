package de.loothub.client.mixin;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.cosmetics.Motion;

/**
 * Flug-Haltung: Kopf schaut nach vorn, beide Arme am Körper angelegt, Beine gestreckt. Beim Abbauen,
 * Zuschlagen oder Benutzen bleiben die Arme wie gewohnt (nur Darstellung).
 */
@Mixin(PlayerModel.class)
public abstract class FlyPoseModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void loothub$flyPose(AvatarRenderState state, CallbackInfo ci) {
		loothub$afkLimbs(state);
		float f = Motion.fly(state);
		if (f <= 0.0F) {
			return;
		}
		PlayerModel m = (PlayerModel) (Object) this;
		float t = state.ageInTicks;
		m.head.xRot = lerp(f, m.head.xRot, (float) Math.toRadians(-70.0));
		boolean busy = state.attackTime > 0.0F || state.isUsingItem;
		if (!busy) {
			// beide Arme gleich, am Körper angelegt (keiner nach vorn – sonst ragt er durch die Mini-Mes);
			// leichtes Flattern im Fahrtwind
			float wind = (float) Math.sin(t * 0.35F) * 0.04F;
			m.rightArm.xRot = lerp(f, m.rightArm.xRot, 0.15F + wind);
			m.leftArm.xRot = lerp(f, m.leftArm.xRot, 0.15F - wind);
			m.rightArm.zRot = lerp(f, m.rightArm.zRot, 0.12F);
			m.leftArm.zRot = lerp(f, m.leftArm.zRot, -0.12F);
		}
		m.rightLeg.xRot = lerp(f, m.rightLeg.xRot, 0.12F + (float) Math.sin(t * 0.25F) * 0.05F);
		m.leftLeg.xRot = lerp(f, m.leftLeg.xRot, 0.02F - (float) Math.sin(t * 0.25F) * 0.05F);
		m.rightLeg.zRot = lerp(f, m.rightLeg.zRot, 0.03F);
		m.leftLeg.zRot = lerp(f, m.leftLeg.zRot, -0.03F);
	}

	/** AFK-Anime-Haltung: strecken/gähnen, Hände hinter dem Kopf beim Liegen, Knie anziehen beim Kip-up. */
	private void loothub$afkLimbs(AvatarRenderState state) {
		float[] p = de.loothub.client.quick.AfkView.pose(state);
		if (p == null) {
			return;
		}
		PlayerModel m = (PlayerModel) (Object) this;
		float up = (float) Math.toRadians(-170.0);
		float stretch = p[de.loothub.client.quick.AfkView.STRETCH];
		if (stretch > 0.0F) {
			m.rightArm.xRot = lerp(stretch, m.rightArm.xRot, up);
			m.leftArm.xRot = lerp(stretch, m.leftArm.xRot, up);
			m.rightArm.zRot = lerp(stretch, m.rightArm.zRot, 0.25F);
			m.leftArm.zRot = lerp(stretch, m.leftArm.zRot, -0.25F);
			m.head.xRot = lerp(stretch, m.head.xRot, -0.6F); // Kopf in den Nacken, Gähnen
		}
		float hands = p[de.loothub.client.quick.AfkView.HANDS];
		if (hands > 0.0F) {
			// entspannt: Hände hinter dem Kopf, Ellbogen nach außen
			m.rightArm.xRot = lerp(hands, m.rightArm.xRot, (float) Math.toRadians(-155.0));
			m.leftArm.xRot = lerp(hands, m.leftArm.xRot, (float) Math.toRadians(-155.0));
			m.rightArm.zRot = lerp(hands, m.rightArm.zRot, 0.65F);
			m.leftArm.zRot = lerp(hands, m.leftArm.zRot, -0.65F);
			m.head.xRot = lerp(hands, m.head.xRot, 0.0F);
			m.head.yRot = lerp(hands, m.head.yRot, 0.0F);
		}
		float knees = p[de.loothub.client.quick.AfkView.KNEES];
		if (knees > 0.0F) {
			m.rightLeg.xRot = lerp(knees, m.rightLeg.xRot, -1.4F);
			m.leftLeg.xRot = lerp(knees, m.leftLeg.xRot, -1.4F);
			m.rightArm.xRot = lerp(knees, m.rightArm.xRot, -0.6F);
			m.leftArm.xRot = lerp(knees, m.leftArm.xRot, -0.6F);
		}
	}

	private static float lerp(float f, float a, float b) {
		return a + (b - a) * f;
	}
}
