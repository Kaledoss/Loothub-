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

	private static void draw(PoseStack poseStack, SubmitNodeCollector collector, Identifier texture, int argb, Pose wp, int light) {
		float flap = (float) Math.sin(wp.phase()) * wp.amp();
		// Unterseite (zum Körper hin) etwas dunkler – wie echtes Licht von oben/hinten
		int dark = shade(argb, 0.68F);
		for (int side = -1; side <= 1; side += 2) {
			final float s = side;
			poseStack.pushPose();
			// Wurzel auf dem Rücken, etwas Abstand für Rüstung/Jacke
			poseStack.translate(s * 0.1F, -0.02F, 0.26F);
			// angelegt ≈ 62° nach hinten, offen ≈ 12°; in Kurven schließt sich der innere Flügel etwas.
			// Vorzeichen: YP dreht +x Richtung −z (nach vorn) – daher −s, damit die Flügel nach hinten zeigen
			float angle = 62.0F + (12.0F - 62.0F) * wp.open() + flap * 16.0F + s * Mth.clamp(wp.turn(), -40.0F, 40.0F) * 0.25F;
			poseStack.mulPose(Axis.YP.rotationDegrees(-s * angle));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-s * (4.0F + 8.0F * Mth.clamp(wp.open(), 0.0F, 1.0F))));
			// Cutout: sauber in der Tiefe; zwei Flächen knapp hintereinander – außen hell, innen dunkler
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, vc) -> {
				float w = HEIGHT * ASPECT * s;
				// Der Ansatz (linker Bildrand, untere Hälfte) liegt so auf Höhe der Schulterblätter; oben ragen Arm und Kralle
				// etwas über den Kopf hinaus
				float top = 0.2F - 0.7F * HEIGHT, bottom = top + HEIGHT;
				// u 0 = Wurzel, 1 = Spitze; links gespiegelt durch die negative Breite
				quad(pose, vc, w, top, bottom, 0.0F, light, argb, 1.0F);
				quad(pose, vc, w, top, bottom, -0.012F, light, dark, -1.0F);
			});
			poseStack.popPose();
		}
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer vc, float w, float top, float bottom, float z, int light, int argb,
			float nz) {
		vertex(pose, vc, 0, top, z, 0, 0, light, argb, nz);
		vertex(pose, vc, w, top, z, 1, 0, light, argb, nz);
		vertex(pose, vc, w, bottom, z, 1, 1, light, argb, nz);
		vertex(pose, vc, 0, bottom, z, 0, 1, light, argb, nz);
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
