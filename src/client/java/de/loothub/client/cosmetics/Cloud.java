package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import de.loothub.Loothub;

/**
 * Kleine, weiche gelbe Wolke, auf der das erste schwebende Mini-Me sitzt. Aus mehreren ineinander liegenden, weich schattierten Kugeln gebaut, die
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

	/** Auflösung der Kugeln (Segmente rundum, Ringe von oben nach unten). */
	private static final int SEGS = 14, RINGS = 9;
	private static final int TOP = 0xFFF2A0, MID = 0xFFD84A, LOW = 0xD99A1E;

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, float t, int light, float alpha) {
		int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(TEXTURE), (pose, vc) -> {
			for (float[] p : PUFFS) {
				// jede Kugel atmet leicht für sich – dadurch wabert die Wolke weich
				float w = (float) Math.sin(t * 0.06F + p[6]) * 0.025F;
				ellipsoid(pose, vc, p[0], p[1] + w, p[2], p[3] * 1.15F + w, p[4] * 1.5F + w, p[5] * 1.15F + w, light, a);
			}
		});
	}

	/** Runde, weich schattierte Kugel: oben hell, unten goldener – ohne Kanten wie bei Würfeln. */
	private static void ellipsoid(PoseStack.Pose pose, VertexConsumer vc, float cx, float cy, float cz, float rx, float ry, float rz,
			int light, int a) {
		for (int r = 0; r < RINGS; r++) {
			double t0 = Math.PI * r / RINGS, t1 = Math.PI * (r + 1) / RINGS;
			for (int sgm = 0; sgm < SEGS; sgm++) {
				double p0 = 2 * Math.PI * sgm / SEGS, p1 = 2 * Math.PI * (sgm + 1) / SEGS;
				point(pose, vc, cx, cy, cz, rx, ry, rz, t0, p0, light, a);
				point(pose, vc, cx, cy, cz, rx, ry, rz, t1, p0, light, a);
				point(pose, vc, cx, cy, cz, rx, ry, rz, t1, p1, light, a);
				point(pose, vc, cx, cy, cz, rx, ry, rz, t0, p1, light, a);
			}
		}
	}

	private static void point(PoseStack.Pose pose, VertexConsumer vc, float cx, float cy, float cz, float rx, float ry, float rz,
			double theta, double phi, int light, int a) {
		float nx = (float) (Math.sin(theta) * Math.cos(phi));
		float ny = (float) -Math.cos(theta); // theta 0 = oben (y klein im Modellraum)
		float nz = (float) (Math.sin(theta) * Math.sin(phi));
		float up = -ny; // 1 oben … -1 unten
		int rgb = up > 0 ? lerp(MID, TOP, up) : lerp(MID, LOW, -up);
		v(pose, vc, cx + nx * rx, cy + ny * ry, cz + nz * rz, (float) (phi / (2 * Math.PI)), (float) (theta / Math.PI), light,
				(a << 24) | rgb, nx, ny, nz);
	}

	private static int lerp(int c0, int c1, float k) {
		int r = (int) (((c0 >> 16) & 255) + ((((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * k));
		int g = (int) (((c0 >> 8) & 255) + ((((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * k));
		int b = (int) ((c0 & 255) + (((c1 & 255) - (c0 & 255)) * k));
		return (r << 16) | (g << 8) | b;
	}

	private static void v(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float w, int light, int argb,
			float nx, float ny, float nz) {
		vc.addVertex(pose, x, y, z).setColor(argb).setUv(u, w).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, nx, ny, nz);
	}
}
