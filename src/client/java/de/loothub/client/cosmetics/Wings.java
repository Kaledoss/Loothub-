package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import de.loothub.Loothub;

/**
 * Flügel (Drache, Teufel, Engel) als zwei flache, beidseitig sichtbare Flächen am Rücken, die sanft schlagen. Die
 * Textur ist ein einzelner (rechter) Flügel mit der Wurzel am linken Bildrand; links wird er gespiegelt.
 * Wird für den Spieler und für Mini-Mes benutzt – jeweils im Modellraum (y nach unten, Hals bei 0, +z = Rücken).
 */
public final class Wings {
	/** Seitenverhältnis Breite/Höhe eines Flügels (passend zu den erzeugten Texturen). */
	private static final float ASPECT = 0.52F;
	private static final float HEIGHT = 2.0F;

	private Wings() {
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, String style, float ageInTicks, int light,
			boolean slowFlap) {
		submit(poseStack, collector, style, ageInTicks, light, slowFlap, null);
	}

	/**
	 * Mit Farbe ({@code color} = „#RRGGBB“ oder null für die Originalfarben): dann wird eine helle Graustufen-Fassung
	 * des Flügels eingefärbt, damit jede Farbe kräftig wirkt.
	 */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, String style, float ageInTicks, int light,
			boolean slowFlap, String color) {
		boolean tinted = color != null && !color.isBlank();
		Identifier texture = Identifier.fromNamespaceAndPath(Loothub.MOD_ID,
				"textures/entity/wing_" + style + (tinted ? "_gray" : "") + ".png");
		int argb = tinted ? 0xFF000000 | AuraTextures.parse(color) : 0xFFFFFFFF;
		float speed = slowFlap ? 0.08F : 0.16F;
		float flap = (float) Math.sin(ageInTicks * speed);
		for (int side = -1; side <= 1; side += 2) {
			final float s = side;
			poseStack.pushPose();
			// Wurzel zwischen den Schulterblättern, knapp hinter dem Rücken
			// Wurzel auf dem Rücken, etwas Abstand für Rüstung/Jacke
			poseStack.translate(s * 0.1F, -0.02F, 0.26F);
			// nach hinten angewinkelt (Spitzen weg vom Körper), Spitzen etwas nach oben; schlägt sanft auf und zu.
			// Vorzeichen: YP dreht +x Richtung −z (nach vorn) – daher −s, damit die Flügel nach hinten zeigen
			poseStack.mulPose(Axis.YP.rotationDegrees(-s * (18.0F + flap * 15.0F)));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-s * 10.0F));
			// Cutout statt durchscheinend: die Flügel schreiben sauber in die Tiefe, so liegt die Aura (leuchtend,
			// durchscheinend) korrekt davor bzw. dahinter statt sie zu überdecken oder zu verschlucken
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, vc) -> {
				float w = HEIGHT * ASPECT * s;
				float top = -0.8F, bottom = top + HEIGHT; // Ansatz über der Schulter
				// u 0 = Wurzel, 1 = Spitze; links gespiegelt durch die negative Breite
				vertex(pose, vc, 0, top, 0, 0, 0, light, argb);
				vertex(pose, vc, w, top, 0, 1, 0, light, argb);
				vertex(pose, vc, w, bottom, 0, 1, 1, light, argb);
				vertex(pose, vc, 0, bottom, 0, 0, 1, light, argb);
			});
			poseStack.popPose();
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float v, int light,
			int argb) {
		vc.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, 0.0F, 0.0F, 1.0F);
	}
}
