package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Cosmetic „Steigflug“: beim Hoch-/Runterfliegen bricht hinter dem Spieler ein Energiestoß aus – spitze, flackernde
 * Lichtstrahlen, die kranzförmig nach hinten auseinanderlaufen, dazu Druckwellen-Ringe, die sich vom Körper lösen
 * und verblassen. Unter den Füßen beim Steigen, über dem Kopf beim Sinken. Eigenes Design.
 */
public final class RiseTrailLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final int RAYS = 16;
	private static final float RING_TICKS = 9.0F;

	public RiseTrailLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (LayerGuard.off("Aufstiegsspur")) {
			return;
		}
		try {
			draw(poseStack, collector, light, state, yRot, xRot);
		} catch (RuntimeException | LinkageError e) {
			LayerGuard.fail("Aufstiegsspur", e); // nur diese Ebene abschalten, nicht das Spiel abstürzen lassen
		}
	}

	private void draw(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (state.isInvisible || !Cosmetics.has(state, Cosmetics.RISE_TRAIL)) {
			return;
		}
		float rise = Motion.rise(state);
		float k = Math.abs(rise);
		if (k < 0.03F) {
			return;
		}
		// Ebenen-Raum: y nach unten. Steigen → Strahlen ab den Füßen nach unten (+y), Sinken → ab dem Kopf nach oben.
		float dir = rise > 0 ? 1.0F : -1.0F;
		float origin = rise > 0 ? 1.45F : -0.45F;
		float t = state.ageInTicks;
		int seed = state.id;
		poseStack.pushPose();
		BodyPose.undo(poseStack, state, true);
		collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vc) -> {
			// Lichtstrahlen
			for (int i = 0; i < RAYS; i++) {
				float h1 = hash(seed * 31 + i), h2 = hash(seed * 17 + i * 7);
				// jeder Strahl flackert in eigenem Takt in der Länge
				float flicker = 0.55F + 0.45F * (float) Math.sin(t * (0.9F + h1 * 0.8F) + h2 * 6.28F);
				float len = (0.9F + 1.6F * h2) * flicker * (0.4F + 0.6F * k);
				double ang = (i + h1 * 0.6F) / RAYS * Math.PI * 2 + t * 0.02F;
				float cx = (float) Math.cos(ang), cz = (float) Math.sin(ang);
				float r0 = 0.18F + 0.08F * h1;
				float r1 = r0 + len * (0.35F + 0.25F * h2); // nach außen gespreizt
				float y0 = origin, y1 = origin + dir * len;
				float w = (0.05F + 0.04F * h1) * (0.6F + 0.4F * k);
				float bright = (0.55F + 0.45F * flicker) * k;
				ray(pose, vc, cx * r0, y0, cz * r0, cx * r1, y1, cz * r1, w, bright);
			}
			// Druckwellen: lösen sich vom Körper, werden größer und blasser
			for (int n = 0; n < 2; n++) {
				float phase = ((t + n * RING_TICKS / 2) % RING_TICKS) / RING_TICKS;
				float ry = origin + dir * (0.1F + 1.6F * phase) * (0.5F + 0.5F * k);
				float rad = 0.3F + 1.1F * phase;
				ring(pose, vc, ry, rad, 0.07F + 0.05F * phase, (1.0F - phase) * (1.0F - phase) * 0.7F * k);
			}
		});
		poseStack.popPose();
	}

	/** Spitzer Strahl aus zwei gekreuzten Bändern: breit und hell am Körper, nach außen spitz und verblassend. */
	private static void ray(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1,
			float w, float a) {
		// Querrichtungen: einmal waagerecht um den Kreis, einmal nach außen
		float tx = -z0, tz = x0;
		float tl = (float) Math.sqrt(tx * tx + tz * tz);
		if (tl < 1.0e-4F) {
			tx = 1;
			tz = 0;
			tl = 1;
		}
		tx /= tl;
		tz /= tl;
		float ox = x0 / Math.max(1.0e-4F, tl), oz = z0 / Math.max(1.0e-4F, tl);
		for (int band = 0; band < 2; band++) {
			float dx = band == 0 ? tx * w : ox * w, dz = band == 0 ? tz * w : oz * w;
			// heller Kern (bläulich-weiß) zum Körper hin, zur Spitze hin durchsichtig
			q(pose, vc, x0 - dx, y0, z0 - dz, 0.85F, 0.93F, 1.0F, a);
			q(pose, vc, x0 + dx, y0, z0 + dz, 0.85F, 0.93F, 1.0F, a);
			q(pose, vc, x1, y1, z1, 0.6F, 0.8F, 1.0F, 0.0F);
			q(pose, vc, x1, y1, z1, 0.6F, 0.8F, 1.0F, 0.0F);
		}
	}

	/** Flacher Ring (Druckwelle) auf Höhe {@code y}. */
	private static void ring(PoseStack.Pose pose, VertexConsumer vc, float y, float r, float thick, float a) {
		if (a <= 0.01F) {
			return;
		}
		int seg = 28;
		for (int s = 0; s < seg; s++) {
			double a0 = Math.PI * 2 * s / seg, a1 = Math.PI * 2 * (s + 1) / seg;
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			float ri = r - thick, ro = r + thick;
			// innen hell, außen ausblendend
			q(pose, vc, c0 * ri, y, s0 * ri, 0.9F, 0.95F, 1.0F, a);
			q(pose, vc, c1 * ri, y, s1 * ri, 0.9F, 0.95F, 1.0F, a);
			q(pose, vc, c1 * ro, y, s1 * ro, 0.7F, 0.85F, 1.0F, 0.0F);
			q(pose, vc, c0 * ro, y, s0 * ro, 0.7F, 0.85F, 1.0F, 0.0F);
		}
	}

	private static float hash(int n) {
		n = (n << 13) ^ n;
		return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
	}

	private static void v(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float r, float g, float b, float a) {
		vc.addVertex(pose, x, y, z).setColor(r, g, b, Math.max(0.0F, Math.min(1.0F, a)));
	}

	/** Viereck von beiden Seiten sichtbar (die Licht-Darstellung blendet Rückseiten sonst aus). */
	private static final float[][] QUAD = new float[4][7];
	private static int quadN;

	private static void q(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float r, float g, float b, float a) {
		float[] d = QUAD[quadN++];
		d[0] = x; d[1] = y; d[2] = z; d[3] = r; d[4] = g; d[5] = b; d[6] = a;
		if (quadN == 4) {
			quadN = 0;
			for (int i = 0; i < 4; i++) {
				v(pose, vc, QUAD[i][0], QUAD[i][1], QUAD[i][2], QUAD[i][3], QUAD[i][4], QUAD[i][5], QUAD[i][6]);
			}
			for (int i = 3; i >= 0; i--) {
				v(pose, vc, QUAD[i][0], QUAD[i][1], QUAD[i][2], QUAD[i][3], QUAD[i][4], QUAD[i][5], QUAD[i][6]);
			}
		}
	}
}
