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
		// Aura-Stufen: beim Sprinten/Fliegen größer, heller und schneller; ab Stufe 2,5 zucken Blitze
		float lv = Cosmetics.has(state, Cosmetics.AURA_LEVELS) ? Motion.charge(state) : 0.0F;
		float w = 1.0F + 0.09F * lv, k = 1.0F + 0.22F * lv, sp = 1.0F + 0.35F * lv;
		collector.submitCustomGeometry(poseStack, RenderTypes.energySwirl(TEXTURE, 0.0F, 0.0F), (pose, vc) -> {
			ring(pose, vc, rgb, 0.60F * w, 0.48F * w, 1.15F * k, 3.0F, t * 0.05F * sp, 0.0F);
			ring(pose, vc, rgb, 0.92F * w, 0.74F * w, 0.95F * k, 4.0F, t * 0.036F * sp, 0.37F);
			ring(pose, vc, rgb, 1.25F * w, 1.00F * w, 0.6F * k, 5.0F, t * 0.028F * sp, 0.71F);
		});
		if (lv > 2.5F) {
			float a = (lv - 2.5F) * 2.0F;
			collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vc) -> bolts(pose, vc, rgb, t, a));
		}
	}

	/** Zwei bis drei zuckende Blitze um den Körper, die alle paar Ticks neu springen. */
	private static void bolts(PoseStack.Pose pose, VertexConsumer vc, int rgb, float t, float alpha) {
		long seed = (long) (t / 2.5F);
		java.util.Random r = new java.util.Random(seed * 341873128712L);
		int n = 2 + r.nextInt(2);
		float cr = 0.6F + 0.4F * ((rgb >> 16) & 0xFF) / 255F;
		float cg = 0.6F + 0.4F * ((rgb >> 8) & 0xFF) / 255F;
		float cb = 0.6F + 0.4F * (rgb & 0xFF) / 255F;
		for (int b = 0; b < n; b++) {
			double ang = r.nextDouble() * Math.PI * 2;
			float rad = 0.55F + r.nextFloat() * 0.45F;
			float px = (float) Math.cos(ang) * rad, pz = (float) Math.sin(ang) * rad * 0.8F, py = -1.5F;
			float tx = (float) -Math.sin(ang), tz = (float) Math.cos(ang);
			for (int seg = 0; seg < 7; seg++) {
				float nx = px + (r.nextFloat() - 0.5F) * 0.25F, nz = pz + (r.nextFloat() - 0.5F) * 0.25F;
				float ny = py + 0.42F;
				float w = 0.035F;
				for (int cross = 0; cross < 2; cross++) {
					float dx = cross == 0 ? tx * w : 0, dz = cross == 0 ? tz * w : 0, dy = cross == 0 ? 0 : w;
					bolt(pose, vc, px - dx, py - dy, pz - dz, cr, cg, cb, alpha);
					bolt(pose, vc, px + dx, py + dy, pz + dz, cr, cg, cb, alpha);
					bolt(pose, vc, nx + dx, ny + dy, nz + dz, cr, cg, cb, alpha);
					bolt(pose, vc, nx - dx, ny - dy, nz - dz, cr, cg, cb, alpha);
				}
				px = nx;
				py = ny;
				pz = nz;
			}
		}
	}

	private static void bolt(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float r, float g, float b, float a) {
		vc.addVertex(pose, x, y, z).setColor(r, g, b, Math.min(1.0F, a) * 0.8F);
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
