package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

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
 * Cosmetic „Steigflug“: fliegt man (ohne Sprint) hoch, zieht unter den Füßen ein heller Energie-Kegel nach unten
 * weg; fliegt man runter, über dem Kopf nach oben. Streifen laufen entgegen der Flugrichtung, außen weich auslaufend.
 * Eigenes Design.
 */
public final class RiseTrailLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/aura_base.png");
	private static final int SEGMENTS = 20;

	public RiseTrailLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (state.isInvisible || !Cosmetics.has(state, Cosmetics.RISE_TRAIL)) {
			return;
		}
		float rise = Motion.rise(state);
		float k = Math.abs(rise);
		if (k < 0.03F) {
			return;
		}
		boolean up = rise > 0;
		float t = state.ageInTicks;
		poseStack.pushPose();
		BodyPose.undo(poseStack, state, true);
		collector.submitCustomGeometry(poseStack, RenderTypes.energySwirl(TEXTURE, 0.0F, 0.0F), (pose, vc) -> {
			// Ebenen-Raum: y nach unten, Füße bei 1,5, Kopf oben bei −0,5
			float start = up ? 1.35F : -0.35F;          // schmale Spitze am Körper
			float end = up ? 1.35F + 2.6F * k : -0.35F - 2.6F * k; // breites Ende weg vom Körper
			float scroll = t * 0.12F * (up ? -1 : 1);
			cone(pose, vc, start, end, 0.28F, 0.95F, 0.85F * k, scroll, 0.0F, 3.0F);
			cone(pose, vc, start, end * 0.9F + start * 0.1F, 0.18F, 0.6F, 0.6F * k, scroll * 1.3F, 0.4F, 2.0F);
		});
		poseStack.popPose();
	}

	/** Kegelmantel von {@code yA} (Radius rA, hell) nach {@code yB} (Radius rB, ausgeblendet). */
	private static void cone(PoseStack.Pose pose, VertexConsumer vc, float yA, float yB, float rA, float rB, float strength,
			float scroll, float uShift, float uRepeat) {
		int levels = 4;
		for (int s = 0; s < SEGMENTS; s++) {
			double a0 = 2 * Math.PI * s / SEGMENTS, a1 = 2 * Math.PI * (s + 1) / SEGMENTS;
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			float u0 = uShift + uRepeat * s / SEGMENTS, u1 = uShift + uRepeat * (s + 1) / SEGMENTS;
			for (int l = 0; l < levels; l++) {
				float f0 = l / (float) levels, f1 = (l + 1) / (float) levels;
				float y0 = yA + (yB - yA) * f0, y1 = yA + (yB - yA) * f1;
				float r0 = rA + (rB - rA) * f0, r1 = rA + (rB - rA) * f1;
				// hell am Körper, nach außen ausblendend (additiv: dunkel = unsichtbar)
				int col0 = gray(strength * (1.0F - f0) * (1.0F - f0)), col1 = gray(strength * (1.0F - f1) * (1.0F - f1));
				float v0 = f0 * 1.5F + scroll, v1 = f1 * 1.5F + scroll;
				v(pose, vc, c0 * r0, y0, s0 * r0, u0, v0, col0);
				v(pose, vc, c1 * r0, y0, s1 * r0, u1, v0, col0);
				v(pose, vc, c1 * r1, y1, s1 * r1, u1, v1, col1);
				v(pose, vc, c0 * r1, y1, s0 * r1, u0, v1, col1);
			}
		}
	}

	private static int gray(float k) {
		int w = Math.min(255, (int) (k * 255));
		int b = Math.min(255, (int) (k * 255 * 1.05F + 6));
		return 0xFF000000 | (w << 16) | (w << 8) | b; // leicht bläuliches Weiß
	}

	private static void v(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float v, int color) {
		vc.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0)
				.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
