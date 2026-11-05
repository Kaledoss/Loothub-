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
		if (state.isCrouching) {
			// beim Ducken neigt sich der Oberkörper nach vorn – Flügel mitnehmen
			poseStack.translate(0.0F, 0.2F, 0.0F);
			poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(28.6F));
		}
		Wings.submit(poseStack, collector, style, state.ageInTicks, light, false);
		poseStack.popPose();
	}
}
