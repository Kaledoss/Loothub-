package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.animal.allay.AllayModel;
import net.minecraft.client.model.animal.bee.BeeModel;
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

	private final SlimeModel slimeInner;
	private final SlimeModel slimeOuter;
	private final AllayModel allay;
	private final BeeModel bee;
	private final VexModel vex;

	public PetsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityModelSet models) {
		super(parent);
		slimeInner = new SlimeModel(models.bakeLayer(ModelLayers.SLIME));
		slimeOuter = new SlimeModel(models.bakeLayer(ModelLayers.SLIME_OUTER));
		allay = new AllayModel(models.bakeLayer(ModelLayers.ALLAY));
		bee = new BeeModel(models.bakeLayer(ModelLayers.BEE));
		vex = new VexModel(models.bakeLayer(ModelLayers.VEX));
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
			boolean ground = type.equals("slime");
			float scale = switch (type) {
				case "slime" -> 0.7F;
				case "bee" -> 0.6F;
				case "vex" -> 0.6F;
				default -> 0.65F;
			};
			float tx = side * 1.35F, tz = 0.35F;
			float ty;
			float squish = 0.0F;
			if (ground && fly < 0.5F) {
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
			// eigenständig: gefedert hinterher, schwebt bzw. hüpft um seinen Platz, schaut in Laufrichtung
			float hopY = ground && fly < 0.5F ? ty - (1.5F - 1.5F * scale) : 0.0F;
			float[] p = Followers.follow(state, 8 + slot, tx, ty - hopY, tz, ground ? 7.0F : 4.5F, ground ? 0.45F : 0.6F,
					ground && fly < 0.5F ? 0.0F : 0.6F);
			poseStack.pushPose();
			BodyPose.undo(poseStack, state, true);
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
				default -> submit(collector, poseStack, vex, base(new VexRenderState(), t, 0.3F), VEX_TEX);
			}
			poseStack.popPose();
		}
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
