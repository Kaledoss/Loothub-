package de.loothub.client.cosmetics;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import de.loothub.Loothub;

/**
 * Kleine, gemalte gelbe Wolke (Ludo), auf der das erste schwebende Mini-Me sitzt. Sie ist ein Bild, das sich immer
 * zur Kamera dreht (wie ein Namensschild) – so sieht sie von jeder Seite weich und flauschig aus statt kantig. Sie
 * wippt und neigt sich ganz leicht. Aufruf im Modellraum des Mini-Mes (y nach unten, Hüfte im Sitzen bei 0.75).
 */
public final class Cloud {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/cloud.png");
	/** Mitte der Wolke im Modellraum (knapp unter der Hüfte, damit das Mini-Me etwas einsinkt). */
	private static final float CY = 0.98F;
	/** Breite in Modell-Einheiten; Höhe aus dem Seitenverhältnis des Bildes (256 × 169). */
	private static final float W = 1.9F, H = W * 169F / 256F;

	private Cloud() {
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, float t, int light, float alpha) {
		int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
		int argb = (a << 24) | 0xFFFFFF;
		Matrix4f m = poseStack.last().pose();
		Vector3f center = m.transformPosition(0.0F, CY + (float) Math.sin(t * 0.07F) * 0.03F, 0.0F, new Vector3f());
		float scale = m.getScale(new Vector3f()).y;
		Quaternionf cam = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();
		poseStack.pushPose();
		PoseStack.Pose last = poseStack.last();
		last.pose().identity().translate(center).rotate(cam);
		last.normal().identity().rotate(cam);
		poseStack.mulPose(Axis.ZP.rotationDegrees((float) Math.sin(t * 0.045F) * 3.0F));
		float breathe = 1.0F + (float) Math.sin(t * 0.09F) * 0.02F;
		poseStack.scale(scale * breathe, scale / breathe, scale);
		float hw = W / 2.0F, hh = H / 2.0F;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE), (pose, vc) -> {
			v(pose, vc, -hw, hh, 0, 1, light, argb);
			v(pose, vc, -hw, -hh, 0, 0, light, argb);
			v(pose, vc, hw, -hh, 1, 0, light, argb);
			v(pose, vc, hw, hh, 1, 1, light, argb);
		});
		poseStack.popPose();
	}

	/** Nach dem Drehen zur Kamera zeigt y nach oben, also unten (y = -hh) die untere Bildkante. */
	private static void v(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float u, float vTop, int light, int argb) {
		vc.addVertex(pose, x, y, 0.0F).setColor(argb).setUv(u, 1.0F - vTop).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, 0.0F, 0.0F, 1.0F);
	}
}
