package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import de.loothub.Loothub;

/**
 * Kleine, weiche Wolke, auf der schwebende Mini-Mes sitzen. Aus mehreren unterschiedlich großen Quadern gebaut, die
 * sich leicht gegeneinander bewegen („wabern“). Modellraum des Mini-Mes (y nach unten, Hüfte im Sitzen bei 0.75).
 */
public final class Cloud {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/cloud.png");
	/** Puffs: x, y, z (Mitte), halbe Breite, halbe Höhe, halbe Tiefe, Phase. */
	private static final float[][] PUFFS = {
			{0.0F, 0.95F, -0.1F, 0.55F, 0.22F, 0.5F, 0.0F},
			{-0.45F, 1.0F, 0.0F, 0.35F, 0.18F, 0.35F, 1.3F},
			{0.45F, 1.0F, -0.05F, 0.38F, 0.18F, 0.36F, 2.1F},
			{0.1F, 0.82F, -0.3F, 0.32F, 0.16F, 0.3F, 3.4F},
			{-0.2F, 0.86F, 0.25F, 0.3F, 0.14F, 0.28F, 4.2F},
			{0.25F, 1.08F, 0.3F, 0.26F, 0.12F, 0.25F, 5.0F},
	};

	private Cloud() {
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, float t, int light, float alpha) {
		int a = Math.max(0, Math.min(255, (int) (alpha * 235)));
		int top = (a << 24) | 0xFFFFFF;
		int side = (a << 24) | 0xE6ECF5;
		int bottom = (a << 24) | 0xC9D3E3;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE), (pose, vc) -> {
			for (float[] p : PUFFS) {
				float w = (float) Math.sin(t * 0.06F + p[6]) * 0.03F;
				box(pose, vc, p[0], p[1] + w, p[2], p[3] + w, p[4], p[5] - w, light, top, side, bottom);
			}
		});
	}

	private static void box(PoseStack.Pose pose, VertexConsumer vc, float cx, float cy, float cz, float hx, float hy, float hz,
			int light, int top, int side, int bottom) {
		float x0 = cx - hx, x1 = cx + hx, y0 = cy - hy, y1 = cy + hy, z0 = cz - hz, z1 = cz + hz;
		// oben (y klein = oben im Modellraum)
		face(pose, vc, light, top, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		face(pose, vc, light, bottom, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
		face(pose, vc, light, side, 0, 0, -1, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0);
		face(pose, vc, light, side, 0, 0, 1, x1, y1, z1, x0, y1, z1, x0, y0, z1, x1, y0, z1);
		face(pose, vc, light, side, -1, 0, 0, x0, y1, z1, x0, y1, z0, x0, y0, z0, x0, y0, z1);
		face(pose, vc, light, side, 1, 0, 0, x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0);
	}

	private static void face(PoseStack.Pose pose, VertexConsumer vc, int light, int argb, float nx, float ny, float nz,
			float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
		v(pose, vc, ax, ay, az, 0, 1, light, argb, nx, ny, nz);
		v(pose, vc, bx, by, bz, 1, 1, light, argb, nx, ny, nz);
		v(pose, vc, cx, cy, cz, 1, 0, light, argb, nx, ny, nz);
		v(pose, vc, dx, dy, dz, 0, 0, light, argb, nx, ny, nz);
	}

	private static void v(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float w, int light, int argb,
			float nx, float ny, float nz) {
		vc.addVertex(pose, x, y, z).setColor(argb).setUv(u, w).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, nx, ny, nz);
	}
}
