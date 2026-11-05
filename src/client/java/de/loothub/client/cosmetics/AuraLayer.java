package de.loothub.client.cosmetics;

import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import de.loothub.Loothub;

/**
 * Cosmetic „Aura“: zwei leuchtende, nach oben züngelnde Energie-Hüllen um den ganzen Körper (eigenes Design),
 * in wählbarer Farbe. Leuchtet auch im Dunkeln.
 */
public final class AuraLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	public static final Set<String> COLORS = Set.of("gold", "blue", "red", "green", "purple", "white");

	public AuraLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		String color = Cosmetics.auraColor(state);
		if (color == null || state.isInvisible) {
			return;
		}
		Identifier texture = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/aura_" + color + ".png");
		float t = state.ageInTicks;
		shell(poseStack, collector, state, texture, 1.10F, 0.013F * t, -0.045F * t);
		shell(poseStack, collector, state, texture, 1.24F, -0.009F * t, -0.030F * t);
	}

	/** Eine Hülle: um die Körpermitte vergrößert, Textur läuft nach oben. */
	private void shell(PoseStack poseStack, SubmitNodeCollector collector, AvatarRenderState state, Identifier texture,
			float scale, float u, float v) {
		poseStack.pushPose();
		// Modellraum: y zeigt nach unten, Kopf oben bei −0,5, Füße bei 1,5 → Mitte bei 0,5
		poseStack.translate(0.0F, 0.5F, 0.0F);
		poseStack.scale(scale, scale, scale);
		poseStack.translate(0.0F, -0.5F, 0.0F);
		collector.submitModel(getParentModel(), state, poseStack,
				RenderTypes.energySwirl(texture, u % 1.0F, v % 1.0F), 0xF000F0, OverlayTexture.NO_OVERLAY,
				state.outlineColor, null);
		poseStack.popPose();
	}
}
