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
 * Flügel (Drache, Teufel, Engel) als zwei flache, beidseitig sichtbare Flächen am Rücken, die sanft schlagen. Die
 * Textur ist ein einzelner (rechter) Flügel mit der Wurzel am linken Bildrand; links wird er gespiegelt.
 * Wird für den Spieler und für Mini-Mes benutzt – jeweils im Modellraum (y nach unten, Hals bei 0, +z = Rücken).
 */
public final class Wings {
	/** Seitenverhältnis Breite/Höhe eines Flügels (passend zu den erzeugten Texturen). */
	private static final float ASPECT = 0.943F;
	private static final float HEIGHT = 1.4F;

	private Wings() {
	}

	/**
	 * Haltung der Flügel: {@code open} 0 = angelegt … 1 = weit offen (darf leicht überschwingen), {@code phase}/{@code amp}
	 * = Flügelschlag, {@code turn} = Kurve (Grad, ein Flügel schließt sich dann etwas mehr).
	 */
	public record Pose(float open, float phase, float amp, float turn) {
		/** Einfache Haltung ohne Bewegungsdaten (z. B. Mini-Mes): ruhig, gleichmäßiger Schlag. */
		public static Pose idle(float ageInTicks, boolean slow) {
			return new Pose(slow ? 0.35F : 0.75F, ageInTicks * (slow ? 0.08F : 0.16F), slow ? 0.3F : 0.6F, 0.0F);
		}
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, String style, float ageInTicks, int light,
			boolean slowFlap) {
		submit(poseStack, collector, style, Pose.idle(ageInTicks, slowFlap), light, null, null);
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, String style, float ageInTicks, int light,
			boolean slowFlap, String color) {
		submit(poseStack, collector, style, Pose.idle(ageInTicks, slowFlap), light, color, null);
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, String style, float ageInTicks, int light,
			boolean slowFlap, String inner, String outer) {
		submit(poseStack, collector, style, Pose.idle(ageInTicks, slowFlap), light, inner, outer);
	}

	/**
	 * Zwei Farben: {@code inner} für die Fläche (Flughaut bzw. Federn), {@code outer} für Knochen und Rand. Ist nur eine
	 * gesetzt, behält der andere Teil seine Originalfarben – so wird der Flügel nicht einfarbig „zugekleistert“.
	 */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, String style, Pose pose, int light,
			String inner, String outer) {
		boolean in = inner != null && !inner.isBlank(), out = outer != null && !outer.isBlank();
		if (!in && !out) {
			draw(poseStack, collector, tex(style, ""), 0xFFFFFFFF, pose, light);
			return;
		}
		draw(poseStack, collector, tex(style, in ? "_in_gray" : "_in"), in ? 0xFF000000 | AuraTextures.parse(inner) : 0xFFFFFFFF,
				pose, light);
		draw(poseStack, collector, tex(style, out ? "_out_gray" : "_out"), out ? 0xFF000000 | AuraTextures.parse(outer) : 0xFFFFFFFF,
				pose, light);
	}

	private static Identifier tex(String style, String part) {
		return Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/wing_" + style + part + ".png");
	}

	/** Breiten der drei Glieder (Oberarm, Unterarm, Handschwingen) als Anteil der Flügelbreite. */
	private static final float[] SEG = {0.34F, 0.33F, 0.33F};

	/**
	 * Der Flügel besteht aus drei Gliedern mit Gelenken dazwischen – wie ein echter Flügel. Beim Schlagen folgen die
	 * äußeren Glieder etwas später (die Spitzen „peitschen“ nach), angelegt knicken sie stärker ein. Dadurch wirkt er
	 * gewölbt und weich statt wie ein flaches Brett. Hinter den Schwungfedern liegt eine zweite, leicht versetzte
	 * Federlage für Tiefe.
	 */
	private static void draw(PoseStack poseStack, SubmitNodeCollector collector, Identifier texture, int argb, Pose wp, int light) {
		float open = Mth.clamp(wp.open(), 0.0F, 1.2F);
		int dark = shade(argb, 0.68F);
		int back = shade(argb, 0.82F);
		for (int side = -1; side <= 1; side += 2) {
			final float s = side;
			poseStack.pushPose();
			poseStack.translate(s * 0.1F, -0.02F, 0.26F);
			float flap0 = (float) Math.sin(wp.phase()) * wp.amp();
			float angle = 62.0F + (12.0F - 62.0F) * wp.open() + flap0 * 14.0F + s * Mth.clamp(wp.turn(), -40.0F, 40.0F) * 0.25F;
			poseStack.mulPose(Axis.YP.rotationDegrees(-s * angle));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-s * (4.0F + 8.0F * Mth.clamp(open, 0.0F, 1.0F))));
			float top = 0.2F - 0.7F * HEIGHT, bottom = top + HEIGHT;
			float u0 = 0.0F;
			for (int i = 0; i < SEG.length; i++) {
				float segW = SEG[i] * HEIGHT * ASPECT;
				float u1 = i == SEG.length - 1 ? 1.0F : u0 + SEG[i];
				if (i > 0) {
					// Gelenk: Knick nach hinten (angelegt stärker), Nachschwingen mit Verzögerung, leichtes Absenken
					float lagFlap = (float) Math.sin(wp.phase() - 0.75F * i) * wp.amp();
					float fold = (1.0F - Mth.clamp(open, 0.0F, 1.0F)) * (14.0F + 12.0F * i) + 4.0F * i;
					float bend = fold + lagFlap * (7.0F + 6.0F * i);
					poseStack.mulPose(Axis.YP.rotationDegrees(-s * bend));
					poseStack.mulPose(Axis.ZP.rotationDegrees(-s * (2.0F + lagFlap * 3.0F)));
				}
				final float fu0 = u0, fu1 = u1, w = segW * s;
				final boolean tip = i == SEG.length - 1;
				collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, vc) -> {
					quad(pose, vc, 0.0F, w, fu0, fu1, top, bottom, 0.0F, light, argb, 1.0F);
					quad(pose, vc, 0.0F, w, fu0, fu1, top, bottom, -0.012F, light, dark, -1.0F);
					if (tip) {
						// zweite Federlage: etwas tiefer und nach hinten versetzt, leicht gefächert
						quad(pose, vc, -0.02F * Math.signum(w), w * 1.04F, fu0, fu1, top + 0.05F, bottom + 0.04F, 0.035F, light, back, 1.0F);
					}
				});
				poseStack.translate(w, 0.0F, 0.0F);
				u0 = u1;
			}
			poseStack.popPose();
		}
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer vc, float x0, float x1, float u0, float u1, float top, float bottom,
			float z, int light, int argb, float nz) {
		vertex(pose, vc, x0, top, z, u0, 0, light, argb, nz);
		vertex(pose, vc, x1, top, z, u1, 0, light, argb, nz);
		vertex(pose, vc, x1, bottom, z, u1, 1, light, argb, nz);
		vertex(pose, vc, x0, bottom, z, u0, 1, light, argb, nz);
	}

	private static int shade(int argb, float k) {
		int r = (int) (((argb >> 16) & 0xFF) * k), g = (int) (((argb >> 8) & 0xFF) * k), b = (int) ((argb & 0xFF) * k);
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float v, int light,
			int argb, float nz) {
		vc.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, 0.0F, 0.0F, nz);
	}
}
