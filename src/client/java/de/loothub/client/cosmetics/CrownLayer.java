package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

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
 * Cosmetic „Krone“: eine kleine goldene Zackenkrone, die über dem Kopf schwebt, sich langsam dreht und leicht auf
 * und ab wippt. Eigenes Design (Ring mit acht Zacken).
 */
public final class CrownLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown.png");
	/** Stile aus dem Loothub-Shop: Blütenkranz, Eiskrone, Netherkrone, Heiligenschein. */
	private static final java.util.Map<String, Identifier> STYLES = java.util.Map.of(
			"flower", Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown_flower.png"),
			"ice", Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown_ice.png"),
			"nether", Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown_nether.png"),
			"halo", Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown_halo.png"),
			"holly", Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown_holly.png"),
			"easter", Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown_easter.png"),
			"shell", Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown_shell.png"),
			"pumpkin", Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/crown_pumpkin.png"));
	/** Kränze: ganze Verzierung (Blüte, Ei, Kürbis …) statt Zacke. */
	private static final java.util.Set<String> WREATHS = java.util.Set.of("flower", "holly", "easter", "shell", "pumpkin");
	private static final int SEGMENTS = 8;
	private static final float PX = 1.0F / 16.0F;
	private static final float RADIUS = 3.0F, BAND = 1.6F, SPIKE = 1.9F;

	public CrownLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		long t0 = de.loothub.client.util.Perf.start();
		submitInner(poseStack, collector, light, state, yRot, xRot);
		de.loothub.client.util.Perf.end("Kosmetik: Krone", t0);
	}

	private void submitInner(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (LayerGuard.off("Krone")) {
			return;
		}
		try {
			draw(poseStack, collector, light, state, yRot, xRot);
		} catch (RuntimeException | LinkageError e) {
			LayerGuard.fail("Krone", e); // nur diese Ebene abschalten, nicht das Spiel abstürzen lassen
		}
	}

	private void draw(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (!Cosmetics.has(state, Cosmetics.CROWN) || state.isInvisible || Cosmetics.anyPetOnHead(state)) {
			return;
		}
		float t = state.ageInTicks;
		String style = Cosmetics.crownStyle(state);
		Identifier tex = STYLES.getOrDefault(style, TEXTURE);
		boolean halo = style.equals("halo"); // flacher, leuchtender Ring ohne Zacken, etwas höher
		boolean wreath = WREATHS.contains(style);
		final int lit = halo ? 0xF000F0 : light;
		final float spike = halo ? 0.0F : wreath ? 2.3F : SPIKE; // Kranz: Blüte so hoch wie breit
		final float band = halo ? 0.7F : BAND;
		final float radius = halo ? 3.6F : RADIUS;
		poseStack.pushPose();
		getParentModel().head.translateAndRotate(poseStack);
		// sitzt knapp über dem Kopf (über der Hut-Ebene), damit sie nicht ins Namensschild ragt
		poseStack.translate(0.0F, ((halo ? -10.5F : -8.8F) - (float) Math.sin(t * 0.08F) * 0.3F) * PX, 0.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(t * 1.5F));
		poseStack.mulPose(Axis.XP.rotationDegrees(4.0F)); // leicht schräg – wirkt lässiger
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(tex), (pose, vc) -> {
			for (int s = 0; s < SEGMENTS; s++) {
				double a0 = 2 * Math.PI * s / SEGMENTS, a1 = 2 * Math.PI * (s + 1) / SEGMENTS;
				float x0 = (float) Math.cos(a0) * radius, z0 = (float) Math.sin(a0) * radius;
				float x1 = (float) Math.cos(a1) * radius, z1 = (float) Math.sin(a1) * radius;
				float nx = (float) Math.cos((a0 + a1) / 2), nz = (float) Math.sin((a0 + a1) / 2);
				// Band (untere Texturhälfte)
				v(pose, vc, x0, 0, z0, 0, 1.0F, lit, nx, nz);
				v(pose, vc, x1, 0, z1, 1, 1.0F, lit, nx, nz);
				v(pose, vc, x1, -band, z1, 1, 0.5F, lit, nx, nz);
				v(pose, vc, x0, -band, z0, 0, 0.5F, lit, nx, nz);
				// Zacke (obere Texturhälfte, Spitze mittig)
				float xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
				float top = -band - spike;
				v(pose, vc, x0, -band, z0, 0, 0.5F, lit, nx, nz);
				v(pose, vc, x1, -band, z1, 1, 0.5F, lit, nx, nz);
				if (wreath) {
					// Blüte: ganzes Rechteck statt Zacke, damit die runde Blüte nicht abgeschnitten wird
					v(pose, vc, x1, top, z1, 1, 0.0F, lit, nx, nz);
					v(pose, vc, x0, top, z0, 0, 0.0F, lit, nx, nz);
				} else {
					v(pose, vc, xm, top, zm, 0.5F, 0.0F, lit, nx, nz);
					v(pose, vc, xm, top, zm, 0.5F, 0.0F, lit, nx, nz);
				}
			}
		});
		poseStack.popPose();
	}

	private static void v(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float v, int light, float nx,
			float nz) {
		vc.addVertex(pose, x * PX, y * PX, z * PX).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light).setNormal(pose, nx, 0.0F, nz);
	}
}
