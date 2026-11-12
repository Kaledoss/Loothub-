package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.animal.allay.AllayModel;
import net.minecraft.client.model.animal.bee.BeeModel;
import net.minecraft.client.model.animal.wolf.WolfModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.monster.slime.SlimeModel;
import net.minecraft.client.model.monster.vex.VexModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AllayRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.BeeRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.SlimeRenderState;
import net.minecraft.client.renderer.entity.state.VexRenderState;
import net.minecraft.client.renderer.entity.state.WolfRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Cosmetic „Haustiere“: bis zu zwei kleine Begleiter zusätzlich zu den Mini-Mes – ein hüpfender Schleim, ein
 * schwebender Allay, eine Biene oder ein kleiner Geist (Vex). Sie bleiben weiter außen als die Mini-Mes, ziehen
 * beim Drehen und Laufen nach und kommen mit, wenn der Spieler fliegt.
 */
public final class PetsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final Identifier SLIME_TEX = Identifier.withDefaultNamespace("textures/entity/slime/slime.png");
	private static final Identifier ALLAY_TEX = Identifier.withDefaultNamespace("textures/entity/allay/allay.png");
	private static final Identifier BEE_TEX = Identifier.withDefaultNamespace("textures/entity/bee/bee.png");
	private static final Identifier VEX_TEX = Identifier.withDefaultNamespace("textures/entity/illager/vex.png");
	private static final Identifier WOLF_TEX = Identifier.withDefaultNamespace("textures/entity/wolf/wolf_tame.png");
	private static final Identifier PENGUIN_TEX = Identifier.fromNamespaceAndPath("loothub", "textures/entity/penguin.png");
	private static final Identifier DRAGON_TEX = Identifier.fromNamespaceAndPath("loothub", "textures/entity/mini_dragon.png");

	private final SlimeModel slimeInner;
	private final SlimeModel slimeOuter;
	private final AllayModel allay;
	private final BeeModel bee;
	private final VexModel vex;
	private final WolfModel wolf;
	private final PetModels.Penguin penguin = new PetModels.Penguin();
	private final PetModels.Dragon dragon = new PetModels.Dragon();

	public PetsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityModelSet models) {
		super(parent);
		slimeInner = new SlimeModel(models.bakeLayer(ModelLayers.SLIME));
		slimeOuter = new SlimeModel(models.bakeLayer(ModelLayers.SLIME_OUTER));
		allay = new AllayModel(models.bakeLayer(ModelLayers.ALLAY));
		bee = new BeeModel(models.bakeLayer(ModelLayers.BEE));
		vex = new VexModel(models.bakeLayer(ModelLayers.VEX));
		wolf = new WolfModel(models.bakeLayer(ModelLayers.WOLF));
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (state.isInvisible || !Cosmetics.has(state, Cosmetics.PETS)) {
			return;
		}
		float t = state.ageInTicks;
		float lag = Motion.yawLag(state);
		float fwd = Mth.clamp(Motion.forward(state), -0.3F, 0.7F);
		float vy = Mth.clamp(Motion.vertical(state), -0.6F, 0.6F);
		float fly = Motion.fly(state);
		float lie = de.loothub.client.quick.AfkView.lying(state);
		for (int slot = 0; slot < Cosmetics.MAX_PETS; slot++) {
			String type = Cosmetics.pet(state, slot);
			if (type == null) {
				continue;
			}
			float side = slot == 0 ? 1.0F : -1.0F;
			boolean walker = type.equals("wolf") || type.equals("penguin");
			boolean ground = type.equals("slime") || walker;
			float scale = switch (type) {
				case "slime" -> 0.7F;
				case "bee" -> 0.6F;
				case "vex" -> 0.6F;
				case "wolf" -> 0.45F;
				case "penguin" -> 0.6F;
				case "dragon" -> 0.35F;
				default -> 0.65F;
			};
			float tx = side * 1.35F, tz = 0.35F;
			float ty;
			float squish = 0.0F;
			if (walker && fly < 0.5F) {
				ty = 0.0F; // läuft am Boden
			} else if (ground && fly < 0.5F) {
				// hüpft: schneller, wenn der Spieler läuft; beim Aufkommen kurz platt
				float phase = t * (0.16F + state.walkAnimationSpeed * 0.15F) + slot * 1.9F;
				float hop = Math.max(0.0F, (float) Math.sin(phase));
				ty = -hop * 0.35F;
				squish = (float) Math.cos(phase) * (hop > 0.02F ? 0.25F : -0.45F);
			} else {
				ty = -1.1F + (float) Math.sin(t * 0.09F + slot * 2.0F) * 0.1F;
			}
			ty += 1.5F - 1.5F * scale; // Füße des Modells auf Höhe des Bodens bzw. darüber
			if (fly > 0.0F) {
				tx += (side * 1.2F - tx) * fly;
				tz += (1.4F - tz) * fly;
			}
			// Neugier: ab und zu kurz zu einem Tier oder Spieler in der Nähe und wieder zurück
			float[] curious = lie < 0.5F && fly < 0.5F ? Curiosity.target(state, slot, ground) : null;
			if (curious != null) {
				tx = curious[0];
				tz = curious[2];
				if (!ground) {
					ty = curious[1];
				}
			}
			if (curious == null && lie < 0.5F) {
				// herumstreifen: alle paar Sekunden ein neuer Platz in der Nähe, dazwischen bleibt es stehen
				float[] r = Roam.offset(state.id, 8 + slot, t, side, ground ? 1.3F : 1.0F);
				tx += r[0];
				tz += r[1];
			}
			// eigenständig: gefedert hinterher, schwebt bzw. hüpft um seinen Platz, schaut in Laufrichtung
			boolean hopping = ground && !walker && fly < 0.5F;
			float hopY = hopping ? ty - (1.5F - 1.5F * scale) : 0.0F;
			float[] p = Followers.follow(state, 8 + slot, tx, ty - hopY, tz, ground ? 7.0F : 4.5F,
					curious != null ? 0.05F : ground ? 0.05F : 0.15F, hopping || walker ? 0.0F : 0.6F, (hopping || walker && fly < 0.5F) && lie < 0.5F);
			// Landestaub: beim Aufkommen ein paar Partikel des Bodens
			if (hopping) {
				Curiosity.landing(state, slot, hopY > -0.005F, p);
			}
			// nie im Boden verschwinden: Füße mindestens auf dem Boden (Fliegende etwas darüber)
			{
				double[] w = Followers.layerToWorld(state, p[0], p[1], p[2]);
				double feetY = state.y + 1.501 - (p[1] + hopY + 1.5F * scale);
				double gy = Followers.groundY(w[0], feetY + 0.6, w[2]);
				double min = gy + (ground ? 0.0 : 0.25);
				if (feetY < min) {
					p[1] -= (float) (min - feetY);
				}
			}
			poseStack.pushPose();
			BodyPose.undo(poseStack, state, true);
			shadow(poseStack, collector, state, p, scale, ground);
			poseStack.translate(p[0], p[1] + hopY, p[2]);
			poseStack.mulPose(Axis.YP.rotationDegrees(p[3]));
			poseStack.scale(scale, scale, scale);
			switch (type) {
				case "slime" -> {
					float sx = 1.0F + squish * 0.5F;
					poseStack.translate(0.0F, 1.5F * (1.0F - 1.0F / sx), 0.0F);
					poseStack.scale(sx, 1.0F / sx, sx);
					SlimeRenderState s = base(new SlimeRenderState(), t, 0.0F);
					s.size = 1;
					collector.submitModel(slimeInner, s, poseStack, RenderTypes.entityCutoutNoCull(SLIME_TEX), light,
							OverlayTexture.NO_OVERLAY, 0, null);
					collector.submitModel(slimeOuter, s, poseStack, RenderTypes.entityTranslucent(SLIME_TEX), light,
							OverlayTexture.NO_OVERLAY, 0, null);
				}
				case "allay" -> submit(collector, poseStack, allay, base(new AllayRenderState(), t, 0.4F), ALLAY_TEX);
				case "bee" -> {
					BeeRenderState s = base(new BeeRenderState(), t, 0.0F);
					s.isOnGround = false;
					submit(collector, poseStack, bee, s, BEE_TEX);
				}
				case "wolf" -> {
					WolfRenderState s = base(new WolfRenderState(), t, Math.min(1F, Math.max(state.walkAnimationSpeed * 1.5F, p[4] * 2.5F)));
					submit(collector, poseStack, wolf, s, WOLF_TEX);
				}
				case "penguin" -> {
					// Minecraft-Stil (Würfelmodell); das 3D-Modell wirkte zwischen den Blöcken zu künstlich
					submit(collector, poseStack, penguin,
							base(new LivingEntityRenderState(), t, Math.min(1F, Math.max(state.walkAnimationSpeed * 1.5F, p[4] * 2.5F))), PENGUIN_TEX);
				}
				case "dragon" -> {
					if (DRAGON_3D.ready()) {
						dragon3d(poseStack, collector, t, slot);
					} else {
						submit(collector, poseStack, dragon, base(new LivingEntityRenderState(), t, 0.0F), DRAGON_TEX);
					}
				}
				default -> submit(collector, poseStack, vex, base(new VexRenderState(), t, 0.3F), VEX_TEX);
			}
			poseStack.popPose();
		}
	}

	private static final Mesh3D DRAGON_3D = new Mesh3D("dragon");
	/** Spannweite des Drachens in Modell-Einheiten (vor dem Tier-Maßstab). */
	private static final float DRAGON_W = 2.6F;
	/** Flügelansatz des Drachens (Mesh-Einheiten); y wird beim ersten Mal aus dem Modell bestimmt. */
	private static final float WING_ROOT = 0.1F;
	private static float wingY = Float.NaN;

	/** Mini-Drache (3D): schlägt mit den Flügeln (Spitzen verzögert), schwebt sanft auf und ab. */
	private static void dragon3d(PoseStack poseStack, SubmitNodeCollector collector, float t, int slot) {
		if (Float.isNaN(wingY)) {
			float[] d = DRAGON_3D.data();
			double sum = 0;
			int n = 0;
			for (int i = 0; i < d.length; i += 8) {
				float ax = Math.abs(d[i]);
				if (ax > 0.12F && ax < 0.18F) {
					sum += d[i + 1];
					n++;
				}
			}
			wingY = n > 0 ? (float) (sum / n) : -0.35F;
		}
		poseStack.pushPose();
		poseStack.translate(0.0F, 1.5F, 0.0F);
		float ph = t * 0.32F + slot * 1.7F;
		float flap = (float) Math.sin(ph);
		poseStack.translate(0.0F, flap * 0.04F, 0.0F); // Auftrieb: beim Abschlag leicht hoch
		poseStack.mulPose(Axis.XP.rotationDegrees(6.0F + (float) Math.sin(t * 0.05F + slot) * 3.0F));
		poseStack.scale(DRAGON_W, DRAGON_W, DRAGON_W);
		final float wy = wingY;
		DRAGON_3D.submit(poseStack, collector, 0xF000F0, p -> {
			float ax = Math.abs(p[0]);
			if (ax <= WING_ROOT) {
				return;
			}
			float sgn = Math.signum(p[0]);
			float w = Mth.clamp((ax - WING_ROOT) / 0.1F, 0.0F, 1.0F);
			w = w * w * (3.0F - 2.0F * w);
			// außen etwas später als innen: die Spitzen schwingen nach
			float a = (float) Math.sin(ph - (ax - WING_ROOT) * 2.5F) * 0.6F * w;
			float th = -sgn * a;
			float c = (float) Math.cos(th), s = (float) Math.sin(th);
			float dx = p[0] - sgn * WING_ROOT, dy = p[1] - wy;
			p[0] = sgn * WING_ROOT + dx * c - dy * s;
			p[1] = wy + dx * s + dy * c;
			float nx = p[3], ny = p[4];
			p[3] = nx * c - ny * s;
			p[4] = nx * s + ny * c;
		});
		poseStack.popPose();
	}

	private static final Identifier SHADOW = Identifier.withDefaultNamespace("textures/misc/shadow.png");

	/** Weicher runder Schatten am Boden unter dem Haustier – blasser, je höher es schwebt. */
	private static void shadow(PoseStack poseStack, SubmitNodeCollector collector, AvatarRenderState s, float[] p, float scale,
			boolean ground) {
		double[] w = Followers.layerToWorld(s, p[0], p[1], p[2]);
		double feetY = s.y + 1.501 - (p[1] + 1.5F * scale); // Modell-Füße in Weltkoordinaten
		double gy = Followers.groundY(w[0], feetY, w[2]);
		double h = feetY - gy;
		if (h > 4.0) {
			return;
		}
		float alpha = (float) Math.max(0.0, 0.55 * (1.0 - h / 4.0));
		float r = 0.32F * scale / 0.65F;
		float ly = (float) (1.501 - (gy - s.y)) - 0.01F;
		float cx = p[0], cz = p[2];
		int col = ((int) (alpha * 255) << 24) | 0xFFFFFF;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityShadow(SHADOW), (pose, vc) -> {
			sv(pose, vc, cx - r, ly, cz - r, 0, 0, col);
			sv(pose, vc, cx - r, ly, cz + r, 0, 1, col);
			sv(pose, vc, cx + r, ly, cz + r, 1, 1, col);
			sv(pose, vc, cx + r, ly, cz - r, 1, 0, col);
		});
	}

	private static void sv(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer vc, float x, float y, float z, float u,
			float v, int color) {
		vc.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0)
				.setNormal(pose, 0.0F, -1.0F, 0.0F);
	}

	private static <S extends LivingEntityRenderState> void submit(SubmitNodeCollector collector, PoseStack poseStack,
			EntityModel<? super S> model, S state, Identifier texture) {
		collector.submitModel(model, state, poseStack, RenderTypes.entityTranslucent(texture), 0xF000F0,
				OverlayTexture.NO_OVERLAY, 0, null);
	}

	private static <S extends LivingEntityRenderState> S base(S s, float t, float walk) {
		s.ageInTicks = t;
		s.walkAnimationPos = t * 0.3F;
		s.walkAnimationSpeed = walk;
		return s;
	}

	private static float[] rotateY(float x, float z, float degrees) {
		double a = Math.toRadians(degrees);
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		return new float[]{x * c - z * s, x * s + z * c};
	}
}
