package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Cosmetic „Mini-Me“: eine kleine Ausgabe des Spielers (mit seinem eigenen Skin) schwebt neben seiner Schulter,
 * wippt leicht auf und ab und macht seine Bewegungen mit.
 */
public final class MiniMeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final float SCALE = 0.35F;

	public MiniMeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (state.isInvisible || !Cosmetics.has(state, Cosmetics.MINI_ME) || state.skin == null) {
			return;
		}
		Identifier texture = state.skin.body().texturePath();
		String other = Cosmetics.miniMeSkin(state);
		if (other != null) {
			net.minecraft.world.entity.player.PlayerSkin s = SkinLookup.get(other);
			if (s != null) {
				texture = s.body().texturePath(); // Skin eines anderen Spielers (solange er lädt: eigener)
			}
		}
		float bob = (float) Math.sin(state.ageInTicks * 0.1F) * 0.06F;
		poseStack.pushPose();
		// neben der Schulter, etwas nach hinten; im Modellraum zeigt y nach unten
		poseStack.translate(0.62F, 0.05F + bob, 0.15F);
		poseStack.scale(SCALE, SCALE, SCALE);
		collector.submitModel(getParentModel(), state, poseStack, RenderTypes.entityTranslucent(texture), light,
				OverlayTexture.NO_OVERLAY, state.outlineColor, null);
		poseStack.popPose();
	}
}
