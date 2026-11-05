package de.loothub.client.cosmetics;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Cosmetic „Mini-Me“: bis zu 6 kleine Ausgaben eines Spielers (eigener oder fremder Skin) mit eigenen Haltungen:
 * 1–2 sitzen auf den Schultern, 3–4 fliegen links und rechts hinterher, 5–6 krabbeln auf allen vieren links und rechts am Boden.
 * Jedes Mini-Me hat einen eigenen Render-Zustand, damit es nicht einfach die Laufbewegung des Spielers mitmacht.
 */
public final class MiniMeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final float SCALE = 0.5F;
	private final PlayerModel wide;
	private final PlayerModel slim;
	private final CrawlModel crawlWide;
	private final CrawlModel crawlSlim;

	/**
	 * Mini-Me auf allen vieren: Arme und Beine stehen senkrecht zum Körper (nach dem Vorkippen also zum Boden), der Kopf
	 * schaut nach vorn. Arme und Beine greifen über Kreuz abwechselnd vor (rechter Arm mit linkem Bein).
	 * Phase steckt in {@code walkAnimationPos}, Ausschlag (0..1) in {@code walkAnimationSpeed}.
	 */
	private static final class CrawlModel extends PlayerModel {
		CrawlModel(net.minecraft.client.model.geom.ModelPart root, boolean slim) {
			super(root, slim);
		}

		@Override
		public void setupAnim(AvatarRenderState s) {
			super.setupAnim(s);
			float p = s.walkAnimationPos;
			float amp = 0.18F + 0.4F * s.walkAnimationSpeed;
			float swing = (float) Math.sin(p) * amp;
			float down = (float) (-Math.PI / 2);
			rightArm.xRot = down + swing;
			leftLeg.xRot = down + swing * 0.8F;
			leftArm.xRot = down - swing;
			rightLeg.xRot = down - swing * 0.8F;
			rightArm.yRot = rightArm.zRot = leftArm.yRot = leftArm.zRot = 0.0F;
			rightLeg.yRot = rightLeg.zRot = leftLeg.yRot = leftLeg.zRot = 0.0F;
			// Kopf nach vorn, nickt beim Krabbeln leicht mit
			head.xRot = (float) Math.toRadians(-80.0) + (float) Math.sin(p * 2.0F) * 0.04F * (1.0F + s.walkAnimationSpeed);
			head.yRot = (float) Math.sin(p * 0.25F) * 0.15F;
			head.zRot = 0.0F;
			body.xRot = body.yRot = body.zRot = 0.0F;
		}
	}

	public MiniMeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityModelSet models) {
		super(parent);
		this.wide = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
		this.slim = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
		this.crawlWide = new CrawlModel(models.bakeLayer(ModelLayers.PLAYER), false);
		this.crawlSlim = new CrawlModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (state.isInvisible || !Cosmetics.has(state, Cosmetics.MINI_ME) || state.skin == null) {
			return;
		}
		List<String> skins = Cosmetics.miniMes(state);
		float t = state.ageInTicks;
		for (int i = 0; i < skins.size() && i < 6; i++) {
			String other = skins.get(i);
			if (other == null) {
				continue; // Platz frei
			}
			PlayerSkin skin = state.skin;
			if (!other.isEmpty()) {
				PlayerSkin s = SkinLookup.get(other);
				if (s != null) {
					skin = s; // fremder Skin (solange er lädt: eigener)
				}
			}
			float side = i % 2 == 0 ? 1.0F : -1.0F; // gerade = rechts, ungerade = links (Modellraum)
			AvatarRenderState mini = miniState(skin, t);
			// Platz-Haltung: Position (x, y, z) und Neigung (vorn/hinten, seitlich)
			float tx, ty, tz, rx, rz = 0.0F;
			if (i < 2) {
				// sitzend auf der Schulter (Reit-Haltung: Beine nach vorn)
				tx = side * 0.5F;
				ty = (state.isCrouching ? -0.3F : -0.48F) + (float) Math.sin(t * 0.08F + i) * 0.01F;
				tz = 0.0F;
				rx = 0.0F;
			} else if (i < 4) {
				// fliegend: schräg hinter dem Spieler, weich schwebend und leicht nach vorn geneigt
				tx = side * (0.8F + (float) Math.sin(t * 0.045F + i) * 0.04F);
				ty = -0.55F + (float) Math.sin(t * 0.07F + i * 1.3F) * 0.07F;
				tz = 0.6F;
				rx = 18.0F + Math.min(25.0F, state.walkAnimationSpeed * 30.0F);
				rz = side * (float) Math.sin(t * 0.05F + i) * 4.0F;
			} else {
				// krabbelnd auf allen vieren: Rumpf waagerecht in Beinlänge über dem Boden, Kopf nach vorn
				tx = side * 0.9F;
				ty = 1.5F - 0.75F * SCALE;
				tz = -0.2F + (float) Math.sin(crawlPhase(state, i) * 2.0F) * 0.01F;
				rx = 90.0F;
			}
			// AFK: liegt der Spieler, legen sich die Mini-Mes neben ihn und schlafen mit
			float lie = de.loothub.client.quick.AfkView.lying(state);
			if (lie > 0.001F) {
				float[] bed = SLEEP[i];
				tx = tx + (bed[0] - tx) * lie;
				ty = ty + (1.375F - ty) * lie;
				tz = tz + (bed[1] - tz) * lie;
				rx = rx + (-90.0F - rx) * lie; // auf dem Rücken
				rz = rz * (1.0F - lie);
			}
			boolean sleeping = lie > 0.5F;
			if (i < 2 && !sleeping) {
				mini.isPassenger = true;
			} else if (i >= 4 && !sleeping) {
				mini.walkAnimationPos = crawlPhase(state, i);
				mini.walkAnimationSpeed = Math.min(1.0F, state.walkAnimationSpeed * 1.5F);
			}
			boolean crawling = i >= 4 && !sleeping;
			poseStack.pushPose();
			if (lie > 0.001F) {
				undoLieDown(poseStack, lie);
			}
			poseStack.translate(tx, ty, tz);
			poseStack.mulPose(Axis.XP.rotationDegrees(rx));
			if (rz != 0.0F) {
				poseStack.mulPose(Axis.ZP.rotationDegrees(rz));
			}
			poseStack.scale(SCALE, SCALE, SCALE);
			boolean slimSkin = skin.model() == PlayerModelType.SLIM;
			PlayerModel model = crawling ? (slimSkin ? crawlSlim : crawlWide) : (slimSkin ? slim : wide);
			collector.submitModel(model, mini, poseStack, RenderTypes.entityTranslucent(skin.body().texturePath()), light,
					OverlayTexture.NO_OVERLAY, 0, null);
			String wings = Cosmetics.miniMeWing(state, i);
			if (wings != null) {
				Wings.submit(poseStack, collector, wings, t + i * 7.0F, light, sleeping);
			}
			poseStack.popPose();
		}
	}

	/** Krabbel-Takt: langsam im Stand, schneller wenn der Spieler läuft; beide Mini-Mes leicht versetzt. */
	private static float crawlPhase(AvatarRenderState state, int slot) {
		return state.ageInTicks * 0.12F + state.walkAnimationPos * 0.9F + slot * 1.7F;
	}

	/** Schlafplätze (x, z) neben dem Spieler, wenn er in der AFK-Ansicht liegt. */
	private static final float[][] SLEEP = {{0.75F, -0.1F}, {-0.75F, -0.1F}, {1.2F, 0.5F}, {-1.2F, 0.5F}, {0.9F, -0.8F},
			{-0.9F, -0.8F}};

	/**
	 * Hebt das Hinlegen des Spielers (AFK-Ansicht, {@code LieDownMixin}) für die Mini-Mes wieder auf, damit sie aufrecht
	 * neben ihm liegen statt mit seinem Körper wegzukippen.
	 */
	private static void undoLieDown(PoseStack poseStack, float lie) {
		Matrix4f q = new Matrix4f()
				.translate(0.0F, 1.501F, 0.0F)
				.scale(-1.0F, -1.0F, 1.0F)
				.rotateX((float) Math.toRadians(-90.0F * lie))
				.translate(0.0F, -0.2F * lie, 0.0F)
				.scale(-1.0F, -1.0F, 1.0F)
				.translate(0.0F, -1.501F, 0.0F);
		poseStack.last().pose().mul(q);
		poseStack.last().normal().mul(new Matrix3f(q));
	}

	/** Eigener Zustand für ein Mini-Me: ruhige Grundhaltung, Skin-Ebenen sichtbar; macht nicht mit (kein Abbauen). */
	private static AvatarRenderState miniState(PlayerSkin skin, float t) {
		AvatarRenderState mini = new AvatarRenderState();
		mini.skin = skin;
		mini.ageInTicks = t;
		mini.showHat = true;
		mini.showJacket = true;
		mini.showLeftSleeve = true;
		mini.showRightSleeve = true;
		mini.showLeftPants = true;
		mini.showRightPants = true;
		return mini;
	}
}
