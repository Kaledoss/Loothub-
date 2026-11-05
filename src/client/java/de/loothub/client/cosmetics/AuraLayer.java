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
 * Cosmetic „Aura“: ein Mantel aus leuchtenden Flammen, der den ganzen Körper umschließt – vom Boden bis über den
 * Kopf, unten kräftig, nach oben in Zungen auslaufend und ständig aufsteigend. Zwei Ringe (eng und weit), in jeder
 * Farbe (über die Vertex-Farbe eingefärbt). Eigenes Design.
 */
public final class AuraLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/aura_base.png");
	private static final int SEGMENTS = 24;
	/** Höhen (Modellraum, y nach unten: Füße bei 1,5, Kopf oben bei −0,5) und Helligkeit an diesen Höhen. */
	private static final float[] LEVELS = {1.6F, 1.0F, 0.2F, -0.5F, -1.2F, -1.9F};
	private static final float[] GLOW = {0.7F, 1.25F, 1.2F, 0.95F, 0.45F, 0.0F};

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
		int rgb = AuraTextures.parse(color);
		float t = state.ageInTicks;
		collector.submitCustomGeometry(poseStack, RenderTypes.energySwirl(TEXTURE, 0.0F, 0.0F), (pose, vc) -> {
			ring(pose, vc, rgb, 0.60F, 0.48F, 1.15F, 3.0F, t * 0.05F, 0.0F);
			ring(pose, vc, rgb, 0.92F, 0.74F, 0.95F, 4.0F, t * 0.036F, 0.37F);
			ring(pose, vc, rgb, 1.25F, 1.00F, 0.6F, 5.0F, t * 0.028F, 0.71F);
		});
	}

	/** Ein Flammenring: Ellipse aus senkrechten Streifen, Textur läuft nach oben, oben dunkel = unsichtbar. */
	private static void ring(PoseStack.Pose pose, VertexConsumer vc, int rgb, float rx, float rz, float strength,
			float uRepeat, float scroll, float uShift) {
		float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
		for (int s = 0; s < SEGMENTS; s++) {
			double a0 = 2 * Math.PI * s / SEGMENTS;
			double a1 = 2 * Math.PI * (s + 1) / SEGMENTS;
			float x0 = (float) Math.cos(a0) * rx, z0 = (float) Math.sin(a0) * rz;
			float x1 = (float) Math.cos(a1) * rx, z1 = (float) Math.sin(a1) * rz;
			float u0 = uShift + uRepeat * s / SEGMENTS, u1 = uShift + uRepeat * (s + 1) / SEGMENTS;
			float nx = (float) Math.cos((a0 + a1) / 2), nz = (float) Math.sin((a0 + a1) / 2);
			for (int l = 0; l < LEVELS.length - 1; l++) {
				float yA = LEVELS[l], yB = LEVELS[l + 1];
				// v wächst mit der Höhe; „+ scroll“ lässt die Flammen nach oben wandern
				float vA = -yA * 0.6F + scroll, vB = -yB * 0.6F + scroll;
				int cA = argb(r, g, b, GLOW[l] * strength), cB = argb(r, g, b, GLOW[l + 1] * strength);
				vertex(pose, vc, x0, yA, z0, u0, vA, cA, nx, nz);
				vertex(pose, vc, x1, yA, z1, u1, vA, cA, nx, nz);
				vertex(pose, vc, x1, yB, z1, u1, vB, cB, nx, nz);
				vertex(pose, vc, x0, yB, z0, u0, vB, cB, nx, nz);
			}
		}
	}

	private static int argb(float r, float g, float b, float k) {
		int ir = Math.min(255, (int) (r * k * 255)), ig = Math.min(255, (int) (g * k * 255)), ib = Math.min(255, (int) (b * k * 255));
		return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float v, int color,
			float nx, float nz) {
		vc.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0)
				.setNormal(pose, nx, 0.0F, nz);
	}
}
