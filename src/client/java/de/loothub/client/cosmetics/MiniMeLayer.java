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
 * Cosmetic „Mini-Me“: bis zu 4 kleine Ausgaben des Spielers (eigener oder fremder Skin) – zwei stehen auf den
 * Schultern, zwei schweben daneben – und machen seine Bewegungen mit.
 */
public final class MiniMeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final float SCALE = 0.5F;
	/**
	 * Plätze der bis zu 4 Mini-Mes (Modellraum, y nach unten, 0 = Schulterhöhe): die ersten beiden stehen wie bei
	 * Schulter-Begleitern auf den Schultern, die anderen beiden schweben etwas tiefer seitlich daneben.
	 */
	private static final float[][] SLOTS = {
			{0.44F, -0.75F, 0.0F},
			{-0.44F, -0.75F, 0.0F},
			{0.95F, -0.35F, 0.05F},
			{-0.95F, -0.35F, 0.05F}};

	public MiniMeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (state.isInvisible || !Cosmetics.has(state, Cosmetics.MINI_ME) || state.skin == null) {
			return;
		}
		Identifier own = state.skin.body().texturePath();
		java.util.List<String> skins = Cosmetics.miniMes(state);
		for (int i = 0; i < skins.size(); i++) {
			Identifier texture = own;
			String other = skins.get(i);
			if (!other.isEmpty()) {
				net.minecraft.world.entity.player.PlayerSkin s = SkinLookup.get(other);
				if (s != null) {
					texture = s.body().texturePath(); // fremder Skin (solange er lädt: eigener)
				}
			}
			float[] slot = SLOTS[i];
			// jedes Mini-Me wippt etwas versetzt
			// auf den Schultern nur leicht wippen, die schwebenden etwas mehr
			float bob = (float) Math.sin(state.ageInTicks * 0.1F + i * 1.7F) * (i < 2 ? 0.015F : 0.06F);
			poseStack.pushPose();
			poseStack.translate(slot[0], slot[1] + bob, slot[2]);
			poseStack.scale(SCALE, SCALE, SCALE);
			collector.submitModel(getParentModel(), state, poseStack, RenderTypes.entityTranslucent(texture), light,
					OverlayTexture.NO_OVERLAY, 0, null);
			poseStack.popPose();
		}
	}
}
