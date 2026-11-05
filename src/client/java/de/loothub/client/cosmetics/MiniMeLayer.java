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
 * Cosmetic „Mini-Me“: bis zu 6 kleine Ausgaben des Spielers (eigener oder fremder Skin) – zwei stehen auf den
 * Schultern, zwei schweben daneben, zwei krabbeln links und rechts am Boden – und machen seine Bewegungen mit.
 */
public final class MiniMeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final float SCALE = 0.5F;
	/**
	 * Plätze der bis zu 6 Mini-Mes (Modellraum, y nach unten, 0 = Schulterhöhe, Füße bei 1,5): 1–2 stehen auf den
	 * Schultern, 3–4 schweben seitlich daneben, 5–6 krabbeln links und rechts am Boden.
	 */
	private static final float[][] SLOTS = {
			{0.60F, -0.75F, 0.0F},
			{-0.60F, -0.75F, 0.0F},
			{1.10F, -0.35F, 0.05F},
			{-1.10F, -0.35F, 0.05F},
			{0.95F, 1.36F, -0.15F},
			{-0.95F, 1.36F, -0.15F}};
	private static final int FIRST_CRAWLER = 4;

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
		for (int i = 0; i < skins.size() && i < SLOTS.length; i++) {
			String other = skins.get(i);
			if (other == null) {
				continue; // Platz frei
			}
			Identifier texture = own;
			if (!other.isEmpty()) {
				net.minecraft.world.entity.player.PlayerSkin s = SkinLookup.get(other);
				if (s != null) {
					texture = s.body().texturePath(); // fremder Skin (solange er lädt: eigener)
				}
			}
			float[] slot = SLOTS[i];
			float t = state.ageInTicks;
			poseStack.pushPose();
			if (i >= FIRST_CRAWLER) {
				// krabbelnd: bäuchlings am Boden, schiebt sich leicht vor und zurück und wiegt hin und her
				float crawl = (float) Math.sin(t * 0.25F + i);
				poseStack.translate(slot[0], slot[1], slot[2] + crawl * 0.04F);
				poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0F));
				poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(crawl * 6.0F));
			} else {
				// auf den Schultern nur leicht wippen, die schwebenden etwas mehr
				float bob = (float) Math.sin(t * 0.1F + i * 1.7F) * (i < 2 ? 0.015F : 0.06F);
				poseStack.translate(slot[0], slot[1] + bob, slot[2]);
			}
			poseStack.scale(SCALE, SCALE, SCALE);
			collector.submitModel(getParentModel(), state, poseStack, RenderTypes.entityTranslucent(texture), light,
					OverlayTexture.NO_OVERLAY, 0, null);
			poseStack.popPose();
		}
	}
}
