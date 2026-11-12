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
				case "penguin" -> 0.7F;
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
			// eigenständig: gefedert hinterher, schwebt bzw. hüpft um seinen Platz, schaut in Laufrichtung
			boolean hopping = ground && !walker && fly < 0.5F;
			float hopY = hopping ? ty - (1.5F - 1.5F * scale) : 0.0F;
			float[] p = Followers.follow(state, 8 + slot, tx, ty - hopY, tz, ground ? 7.0F : 4.5F,
					curious != null ? 0.1F : ground ? 0.45F : 0.6F, hopping ? 0.0F : 0.6F, hopping && lie < 0.5F);
			// Landestaub: beim Aufkommen ein paar Partikel des Bodens
			if (hopping) {
				Curiosity.landing(state, slot, hopY > -0.005F, p);
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
					WolfRenderState s = base(new WolfRenderState(), t, Math.min(1F, state.walkAnimationSpeed * 1.5F));
					submit(collector, poseStack, wolf, s, WOLF_TEX);
				}
				case "penguin" -> submit(collector, poseStack, penguin,
						base(new LivingEntityRenderState(), t, Math.min(1F, state.walkAnimationSpeed * 1.5F)), PENGUIN_TEX);
				case "dragon" -> submit(collector, poseStack, dragon, base(new LivingEntityRenderState(), t, 0.0F), DRAGON_TEX);
				default -> submit(collector, poseStack, vex, base(new VexRenderState(), t, 0.3F), VEX_TEX);
			}
			poseStack.popPose();
		}
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
