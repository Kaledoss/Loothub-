package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** Cosmetic „Flügel“ am Spieler (Drache, Teufel, Engel). */
public final class WingsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	public WingsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		String style = Cosmetics.value(state, Cosmetics.WINGS);
		if (style == null || state.isInvisible) {
			return;
		}
		poseStack.pushPose();
		// Flug-Haltung: Flügel tiefer an den Rücken und nach hinten gelegt, damit sie nicht in den Kopf ragen
		float fly = Motion.fly(state);
		if (fly > 0.0F) {
			poseStack.translate(0.0F, 0.3F * fly, 0.05F * fly);
			poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-55.0F * fly));
		}
		if (state.isCrouching) {
			// beim Ducken: etwas tiefer und weiter nach hinten, kaum nach vorn kippen –
			// sonst schneiden die Flügel durch die Mini-Mes auf den Schultern
			poseStack.translate(0.0F, 0.22F, 0.14F);
			poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(8.0F));
		}
		Wings.submit(poseStack, collector, style, state.ageInTicks, light, false, Cosmetics.raw(state, Cosmetics.WING_COLOR));
		poseStack.popPose();
	}
}
