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
		loothub$risePose(state);
		loothub$wheelEmote(state);
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

	/** Emote-Rad: der Spieler macht die Geste mit (auch andere Loothub-Spieler) (nur Darstellung, nicht beim Zuschlagen oder Benutzen). */
	private void loothub$wheelEmote(AvatarRenderState state) {
		if (de.loothub.client.cosmetics.MiniMeLayer.mini(this) || Motion.fly(state) > 0.5F
				|| state.attackTime > 0.0F || state.isUsingItem) {
			return;
		}
		// eigene Geste oder die eines anderen Loothub-Spielers
		float[] e = de.loothub.client.cosmetics.Emotes.wheel(state);
		if (e != null) {
			de.loothub.client.cosmetics.MiniMeLayer.pose((PlayerModel) (Object) this, state, e);
		}
	}

	/**
	 * Hoch-/Runterfliegen (ohne Sprint): aufrecht, Beine geschlossen und gestreckt, Arme eng am Körper,
	 * Blick in Flugrichtung (nach oben beim Steigen, nach unten beim Sinken).
	 */
	private void loothub$risePose(AvatarRenderState state) {
		float rise = Motion.rise(state);
		float k = Math.min(1.0F, Math.abs(rise) * 1.4F);
		if (k <= 0.01F || Motion.fly(state) > 0.5F) {
			return;
		}
		PlayerModel m = (PlayerModel) (Object) this;
		float t = state.ageInTicks;
		m.rightLeg.xRot = lerp(k, m.rightLeg.xRot, 0.04F);
		m.leftLeg.xRot = lerp(k, m.leftLeg.xRot, 0.04F);
		m.rightLeg.zRot = lerp(k, m.rightLeg.zRot, 0.02F);
		m.leftLeg.zRot = lerp(k, m.leftLeg.zRot, -0.02F);
		m.rightLeg.yRot = lerp(k, m.rightLeg.yRot, 0.0F);
		m.leftLeg.yRot = lerp(k, m.leftLeg.yRot, 0.0F);
		if (!(state.attackTime > 0.0F || state.isUsingItem)) {
			float wind = (float) Math.sin(t * 0.4F) * 0.03F;
			m.rightArm.xRot = lerp(k, m.rightArm.xRot, 0.06F + wind);
			m.leftArm.xRot = lerp(k, m.leftArm.xRot, 0.06F - wind);
			m.rightArm.zRot = lerp(k, m.rightArm.zRot, 0.1F);
			m.leftArm.zRot = lerp(k, m.leftArm.zRot, -0.1F);
		}
		m.head.xRot = lerp(k * 0.6F, m.head.xRot, rise > 0 ? -0.45F : 0.5F);
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
