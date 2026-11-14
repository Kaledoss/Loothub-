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
 * Cosmetic „Leuchtende Augen“: an den üblichen Augen-Pixeln des Gesichts (Zeile 4, Spalten 1–2 und 5–6) leuchtet
 * ein heller Kern mit weichem Schein in der Wahlfarbe – auch im Dunkeln. Folgt jeder Kopfbewegung.
 */
public final class EyesLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final Identifier GLOW = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/eye_glow.png");
	private static final float PX = 1.0F / 16.0F;

	public EyesLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (LayerGuard.off("Augen")) {
			return;
		}
		try {
			draw(poseStack, collector, light, state, yRot, xRot);
		} catch (RuntimeException | LinkageError e) {
			LayerGuard.fail("Augen", e); // nur diese Ebene abschalten, nicht das Spiel abstürzen lassen
		}
	}

	private void draw(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		String color = Cosmetics.raw(state, Cosmetics.EYES);
		if (color == null || state.isInvisible) {
			return;
		}
		int rgb = AuraTextures.parse(color);
		// sanftes Pulsieren
		float pulse = 0.85F + 0.15F * (float) Math.sin(state.ageInTicks * 0.15F);
		// Blinzeln: alle 3–6 Sekunden für einen Augenblick dunkel (je Spieler versetzt)
		if (blinking(state.ageInTicks, state.id)) {
			return;
		}
		// im Dunkeln stärker als bei Tageslicht (Block- und Himmelslicht an der Stelle des Spielers)
		final float glow = pulse * (0.55F + 0.95F * (1.0F - brightness(light)));
		poseStack.pushPose();
		getParentModel().head.translateAndRotate(poseStack);
		collector.submitCustomGeometry(poseStack, RenderTypes.energySwirl(GLOW, 0.0F, 0.0F), (pose, vc) -> {
			for (float cx : new float[]{-2.0F, 2.0F}) {
				// Schein (größer, schwächer) und Kern (Augenfläche, hell)
				quad(pose, vc, cx - 2.2F, -5.6F, cx + 2.2F, -2.4F, -4.06F, 0.0F, 1.0F, color(rgb, 0.7F * glow));
				quad(pose, vc, cx - 1.0F, -4.0F, cx + 1.0F, -3.0F, -4.08F, 0.4F, 0.6F, color(rgb, 1.4F * glow));
			}
		});
		poseStack.popPose();
	}

	private static boolean blinking(float ageInTicks, int id) {
		float sec = ageInTicks / 20.0F + (id % 13) * 0.77F;
		// Abstand zwischen zwei Blinzlern schwankt zwischen 3 und 6 s
		float cycle = 3.0F + 3.0F * (0.5F + 0.5F * (float) Math.sin(Math.floor(sec / 4.5F) * 12.9898F));
		return sec % cycle < 0.13F;
	}

	/** Helligkeit 0..1 aus dem gepackten Licht (Block- und Himmelslicht, Himmel nachts gedämpft). */
	private static float brightness(int light) {
		int block = (light >> 4) & 0xF, sky = (light >> 20) & 0xF;
		var level = net.minecraft.client.Minecraft.getInstance().level;
		float day = level == null ? 1.0F : 1.0F - level.getSkyDarken() / 11.0F;
		return Math.max(block, sky * day) / 15.0F;
	}

	private static int color(int rgb, float k) {
		int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * k));
		int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * k));
		int b = Math.min(255, (int) ((rgb & 0xFF) * k));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Rechteck auf der Gesichtsfläche (Pixel des Kopfs: x −4..4, y −8..0, vorn z = −4). */
	private static void quad(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float x1, float y1, float z, float u0,
			float u1, int color) {
		v(pose, vc, x0, y0, z, u0, u0, color);
		v(pose, vc, x0, y1, z, u0, u1, color);
		v(pose, vc, x1, y1, z, u1, u1, color);
		v(pose, vc, x1, y0, z, u1, u0, color);
	}

	private static void v(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float v, int color) {
		vc.addVertex(pose, x * PX, y * PX, z * PX).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(0xF000F0).setNormal(pose, 0.0F, 0.0F, -1.0F);
	}
}
