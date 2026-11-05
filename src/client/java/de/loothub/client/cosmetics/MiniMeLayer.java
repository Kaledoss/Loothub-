package de.loothub.client.cosmetics;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;


import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.util.Mth;
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
	private static final float SCALE = 0.36F;
	private final PlayerModel wide;
	private final PlayerModel slim;
	private final CrawlModel crawlWide;

	/** Normales Mini-Me-Modell mit Gesten (winken, jubeln, strecken). */
	private static final class EmoteModel extends PlayerModel {
		EmoteModel(net.minecraft.client.model.geom.ModelPart root, boolean slim) {
			super(root, slim);
		}

		@Override
		public void setupAnim(AvatarRenderState s) {
			super.setupAnim(s);
			float[] e = Emotes.of(s);
			if (e == null) {
				return;
			}
			float p = e[1];
			float k = Emotes.envelope(p);
			float up = (float) Math.toRadians(-165.0);
			switch ((int) e[0]) {
				case Emotes.WAVE -> {
					rightArm.xRot = mix(rightArm.xRot, up, k);
					rightArm.zRot = mix(rightArm.zRot, -0.35F + (float) Math.sin(p * 28.0F) * 0.45F, k);
					head.zRot = mix(head.zRot, (float) Math.sin(p * 14.0F) * 0.08F, k);
				}
				case Emotes.CHEER -> {
					float pump = (float) Math.sin(p * 30.0F) * 0.25F;
					rightArm.xRot = mix(rightArm.xRot, up + pump, k);
					leftArm.xRot = mix(leftArm.xRot, up - pump, k);
					rightArm.zRot = mix(rightArm.zRot, -0.45F, k);
					leftArm.zRot = mix(leftArm.zRot, 0.45F, k);
					head.xRot = mix(head.xRot, -0.35F, k);
				}
				case Emotes.YAWN -> {
					// strecken: beide Arme langsam nach oben, Kopf in den Nacken
					rightArm.xRot = mix(rightArm.xRot, up, k);
					leftArm.xRot = mix(leftArm.xRot, up, k);
					rightArm.zRot = mix(rightArm.zRot, -0.2F, k);
					leftArm.zRot = mix(leftArm.zRot, 0.2F, k);
					head.xRot = mix(head.xRot, -0.5F, k);
				}
				default -> {
				}
			}
		}

		private static float mix(float a, float b, float k) {
			return a + (b - a) * k;
		}
	}
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
		this.wide = new EmoteModel(models.bakeLayer(ModelLayers.PLAYER), false);
		this.slim = new EmoteModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
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
			float tx, ty, tz, rx, rz = 0.0F, ry = 0.0F;
			// Nachziehen: beim Drehen schwenken sie hinterher, beim Laufen bleiben sie etwas zurück
			float lag = Motion.yawLag(state);
			float fwd = Mth.clamp(Motion.forward(state), -0.3F, 0.7F);
			float vy = Mth.clamp(Motion.vertical(state), -0.6F, 0.6F);
			float fly = Motion.fly(state);
			if (i < 2) {
				// sitzend auf der Schulter (Reit-Haltung: Beine nach vorn)
				tx = side * 0.44F;
				// Hüfte auf der Schulter: Halspunkt liegt eine Rumpflänge (12 px × SCALE) darüber
				ty = (state.isCrouching ? 0.075F : -0.105F) - 0.75F * SCALE + (float) Math.sin(t * 0.08F + i) * 0.01F;
				tz = 0.0F;
				rx = -fwd * 20.0F; // beim Loslaufen leicht nach hinten
				rz = Mth.clamp(lag * 0.3F, -12.0F, 12.0F); // wackelt beim Drehen mit
				ty -= Math.abs((float) Math.sin(state.walkAnimationPos * 0.6662F)) * 0.03F * state.walkAnimationSpeed;
			} else if (i < 4) {
				// fliegend: schräg hinter dem Spieler, weich schwebend und leicht nach vorn geneigt
				// weiter außen und höher als die Schulter-Mini-Mes, damit sie nicht dahinter verschwinden
				tx = side * (1.0F + (float) Math.sin(t * 0.045F + i) * 0.06F);
				ty = -0.75F + (float) Math.sin(t * 0.07F + i * 1.3F) * 0.08F + vy * 1.2F;
				tz = 0.75F + Math.max(0.0F, fwd) * 1.6F;
				float[] p = rotateY(tx, tz, lag * 0.7F);
				tx = p[0];
				tz = p[1];
				ry = -lag * 0.5F;
				rx = 15.0F + Math.max(0.0F, fwd) * 60.0F;
				rx = rx + (75.0F - rx) * fly; // fliegt der Spieler, liegen sie auch waagerecht in der Luft
				rz = side * (float) Math.sin(t * 0.05F + i) * 5.0F + Mth.clamp(lag * 0.4F, -20.0F, 20.0F);
			} else {
				// krabbelnd auf allen vieren: Rumpf waagerecht in Beinlänge über dem Boden, Kopf nach vorn
				tx = side * 0.72F;
				ty = 1.5F - 0.75F * SCALE;
				tz = -0.2F + (float) Math.sin(crawlPhase(state, i) * 2.0F) * 0.01F + Math.max(0.0F, fwd) * 0.6F;
				float[] p = rotateY(tx, tz, lag * 0.35F);
				tx = p[0];
				tz = p[1];
				ry = -lag * 0.4F;
				rx = 90.0F;
				if (fly > 0.0F) {
					// fliegt der Spieler, fliegen auch die Krabbler mit – unter den anderen, etwas weiter hinten
					tx += (side * 0.75F - tx) * fly;
					ty += (0.25F + (float) Math.sin(t * 0.08F + i) * 0.06F - ty) * fly;
					tz += (1.1F - tz) * fly;
					rx += (75.0F - rx) * fly;
				}
			}
			// AFK: liegt der Spieler, legen sich die Mini-Mes neben ihn und schlafen mit
			float lie = de.loothub.client.quick.AfkView.lying(state);
			if (lie > 0.001F) {
				float[] bed = SLEEP[i];
				tx = tx + (bed[0] - tx) * lie;
				ty = ty + (1.5F - 0.25F * SCALE - ty) * lie;
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
			boolean crawling = i >= 4 && !sleeping && fly < 0.5F;
			float[] emote = crawling ? null : Emotes.pick(state, i, sleeping);
			Emotes.attach(mini, emote);
			if (emote != null && emote[0] == Emotes.CHEER) {
				ty -= Math.abs((float) Math.sin(emote[1] * Math.PI * 4.0)) * 0.12F; // Freudensprünge
			}
			poseStack.pushPose();
			BodyPose.undo(poseStack, state, i >= 2);
			poseStack.translate(tx, ty, tz);
			if (ry != 0.0F) {
				poseStack.mulPose(Axis.YP.rotationDegrees(ry));
			}
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

	/** Dreht (x, z) um die Hochachse (Grad) – für das Hinterherschwenken. */
	private static float[] rotateY(float x, float z, float degrees) {
		double a = Math.toRadians(degrees);
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		return new float[]{x * c - z * s, x * s + z * c};
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
