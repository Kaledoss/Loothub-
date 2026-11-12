package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import de.loothub.Loothub;

/**
 * Knochenschwingen: ein echtes Flügelgerüst wie bei einer Fledermaus bzw. einem Drachen – Oberarm, Unterarm und drei
 * Fingerknochen, dazwischen gespannte Flughaut mit nach innen gewölbten Rändern. Jedes Glied hat ein Gelenk: beim
 * Schlagen folgen Unterarm und Finger verzögert (die Spitzen schwingen nach), angelegt falten sich die Finger
 * zusammen. Knochen und Flughaut lassen sich getrennt einfärben.
 */
public final class DrakeWings {
	private static final Identifier TEX = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/drake_wing.png");
	/** Standardfarben: Knochen dunkel, Flughaut tiefrot. */
	private static final int BONE = 0xFF3A302A, SKIN = 0xFF7E2233;
	/** Längen in Pixeln (1/16 Block): Oberarm, Unterarm, Finger. */
	private static final float ARM = 8F, FORE = 9F;
	private static final float[] FINGER = {9F, 11.5F, 9.5F};
	private static final float[] FINGER_ANGLE = {-0.55F, 0.15F, 0.85F};
	private static final float BONE_W = 1.1F;

	private DrakeWings() {
	}

	/** Zeichnen im Modellraum des Spielers bzw. Mini-Mes (y nach unten, Hals bei 0, +z = Rücken). */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, Wings.Pose pose, int light, String inner,
			String outer) {
		int skin = inner != null && !inner.isBlank() ? 0xFF000000 | AuraTextures.parse(inner) : SKIN;
		int bone = outer != null && !outer.isBlank() ? 0xFF000000 | AuraTextures.parse(outer) : BONE;
		float open = Mth.clamp(pose.open(), 0F, 1.2F);
		float o = Math.min(open, 1F);
		float flap = (float) Math.sin(pose.phase()) * pose.amp();
		float lag1 = (float) Math.sin(pose.phase() - 0.7F) * pose.amp();
		float lag2 = (float) Math.sin(pose.phase() - 1.4F) * pose.amp();
		for (int side = -1; side <= 1; side += 2) {
			final float s = side;
			poseStack.pushPose();
			poseStack.translate(s * 2F / 16F, 3F / 16F, 2.6F / 16F);
			// angelegt nach hinten geschwenkt, offen seitlich ausgebreitet; in Kurven schließt sich ein Flügel etwas
			float back = 64F + (12F - 64F) * o + flap * 10F + s * Mth.clamp(pose.turn(), -40F, 40F) * 0.25F;
			poseStack.mulPose(Axis.YP.rotationDegrees(-s * back));
			// Gelenke in der Flügelebene (x nach außen, y nach unten), in Pixeln
			float a1 = -(0.65F + 0.25F * o + flap * 0.55F);
			float ex = (float) Math.cos(a1) * ARM, ey = (float) Math.sin(a1) * ARM;
			float a2 = a1 + 0.6F - 0.15F * o + lag1 * 0.4F + (1F - o) * 0.6F;
			float wx = ex + (float) Math.cos(a2) * FORE, wy = ey + (float) Math.sin(a2) * FORE;
			float[][] tips = new float[3][];
			for (int k = 0; k < 3; k++) {
				float spread = 0.35F + 0.65F * o; // angelegt liegen die Finger eng beieinander
				float a = a2 + FINGER_ANGLE[k] * spread + lag2 * (0.25F + 0.1F * k) + (1F - o) * 0.5F;
				tips[k] = new float[]{wx + (float) Math.cos(a) * FINGER[k], wy + (float) Math.sin(a) * FINGER[k]};
			}
			float bx = 0.5F, by = 11F; // unteres Ende der Flughaut am Rücken
			final float fex = ex, fey = ey, fwx = wx, fwy = wy;
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(TEX), (p, vc) -> {
				// Flughaut: zwischen Arm, Fingern und Rücken; Ränder zwischen den Spitzen nach innen gewölbt
				tri(p, vc, 0, 0, fex, fey, fwx, fwy, s, skin, light);
				tri(p, vc, 0, 0, fwx, fwy, bx, by, s, skin, light);
				float[] prev = null;
				float[][] edge = {tips[0], tips[1], tips[2], {bx, by}};
				for (int k = 0; k < edge.length; k++) {
					if (prev != null) {
						float[] m = inward(prev, edge[k], fwx, fwy, k == edge.length - 1 ? 0.38F : 0.28F);
						tri(p, vc, fwx, fwy, prev[0], prev[1], m[0], m[1], s, skin, light);
						tri(p, vc, fwx, fwy, m[0], m[1], edge[k][0], edge[k][1], s, skin, light);
					}
					prev = edge[k];
				}
				// Knochen
				bone(p, vc, 0, 0, fex, fey, BONE_W * 1.3F, s, bone, light);
				bone(p, vc, fex, fey, fwx, fwy, BONE_W * 1.15F, s, bone, light);
				for (float[] t : tips) {
					bone(p, vc, fwx, fwy, t[0], t[1], BONE_W * 0.8F, s, bone, light);
				}
			});
			poseStack.popPose();
		}
	}

	/** Mitte zwischen zwei Spitzen, um {@code k} zum Handgelenk hin gezogen (Wölbung der Flughaut). */
	private static float[] inward(float[] a, float[] b, float wx, float wy, float k) {
		float mx = (a[0] + b[0]) / 2F, my = (a[1] + b[1]) / 2F;
		return new float[]{mx + (wx - mx) * k, my + (wy - my) * k};
	}

	private static void tri(PoseStack.Pose p, VertexConsumer vc, float x0, float y0, float x1, float y1, float x2, float y2,
			float s, int argb, int light) {
		// ein Dreieck als Viereck mit doppelter Ecke; die Textur liegt eben auf der Flügelfläche
		v(p, vc, x0, y0, 0F, s, argb, light, 1F);
		v(p, vc, x1, y1, 0F, s, argb, light, 1F);
		v(p, vc, x2, y2, 0F, s, argb, light, 1F);
		v(p, vc, x2, y2, 0F, s, argb, light, 1F);
	}

	/** Knochen als flache Leiste vorn und hinten an der Flughaut plus eine quer dazu (wirkt rund). */
	private static void bone(PoseStack.Pose p, VertexConsumer vc, float x0, float y0, float x1, float y1, float w, float s,
			int argb, int light) {
		float dx = x1 - x0, dy = y1 - y0;
		float len = Math.max(0.001F, (float) Math.sqrt(dx * dx + dy * dy));
		float nx = -dy / len * w / 2F, ny = dx / len * w / 2F;
		for (float z = -0.35F; z <= 0.36F; z += 0.7F) {
			v(p, vc, x0 + nx, y0 + ny, z, s, argb, light, 1F);
			v(p, vc, x1 + nx, y1 + ny, z, s, argb, light, 1F);
			v(p, vc, x1 - nx, y1 - ny, z, s, argb, light, 1F);
			v(p, vc, x0 - nx, y0 - ny, z, s, argb, light, 1F);
		}
		// quer (in die Tiefe)
		v(p, vc, x0, y0, -w / 2F, s, argb, light, 0.8F);
		v(p, vc, x1, y1, -w / 2F, s, argb, light, 0.8F);
		v(p, vc, x1, y1, w / 2F, s, argb, light, 0.8F);
		v(p, vc, x0, y0, w / 2F, s, argb, light, 0.8F);
	}

	private static void v(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, float s, int argb, int light, float shade) {
		int c = shade >= 1F ? argb : (argb & 0xFF000000) | ((int) (((argb >> 16) & 255) * shade) << 16)
				| ((int) (((argb >> 8) & 255) * shade) << 8) | (int) ((argb & 255) * shade);
		// Textur planar über die Flügelfläche (gibt der Haut eine leichte Pixel-Struktur)
		vc.addVertex(p, s * x / 16F, y / 16F, z / 16F).setColor(c).setUv((x + 16F) / 48F, (y + 16F) / 48F).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light).setNormal(p, 0F, 0F, -1F);
	}
}
