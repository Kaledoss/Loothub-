package de.loothub.client.cosmetics;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

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
 * 1–2 sitzen auf den Schultern, 3–4 fliegen links und rechts hinterher, 5–6 krabbeln links und rechts am Boden.
 * Jedes Mini-Me hat einen eigenen Render-Zustand, damit es nicht einfach die Laufbewegung des Spielers mitmacht.
 */
public final class MiniMeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final float SCALE = 0.5F;
	private final PlayerModel wide;
	private final PlayerModel slim;

	public MiniMeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityModelSet models) {
		super(parent);
		this.wide = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
		this.slim = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
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
			AvatarRenderState mini = miniState(state, skin, t);
			poseStack.pushPose();
			if (i < 2) {
				// sitzend auf der Schulter (Reit-Haltung: Beine nach vorn), nur ein Hauch Bewegung
				mini.isPassenger = true;
				float sway = (float) Math.sin(t * 0.08F + i) * 0.01F;
				poseStack.translate(side * 0.5F, (state.isCrouching ? -0.3F : -0.48F) + sway, 0.0F);
			} else if (i < 4) {
				// fliegend: schräg hinter dem Spieler, weich schwebend und leicht nach vorn geneigt
				float bob = (float) Math.sin(t * 0.07F + i * 1.3F) * 0.07F;
				float drift = (float) Math.sin(t * 0.045F + i) * 0.04F;
				float lean = 18.0F + Math.min(25.0F, state.walkAnimationSpeed * 30.0F); // beim Laufen mehr Neigung
				poseStack.translate(side * (0.8F + drift), -0.55F + bob, 0.6F);
				poseStack.mulPose(Axis.XP.rotationDegrees(lean));
				poseStack.mulPose(Axis.ZP.rotationDegrees(side * (float) Math.sin(t * 0.05F + i) * 4.0F));
			} else {
				// krabbelnd: bäuchlings am Boden, Arme und Beine im Krabbel-Takt, schiebt sich leicht vor und zurück
				mini.swimAmount = 1.0F;
				mini.isCrouching = false;
				mini.walkAnimationPos = t * 0.45F;
				mini.walkAnimationSpeed = 0.7F;
				float push = (float) Math.sin(t * 0.45F * 0.6662F) * 0.025F;
				poseStack.translate(side * 0.9F, 1.375F, -0.2F + push);
				poseStack.mulPose(Axis.XP.rotationDegrees(90.0F)); // Gesicht nach unten, Kopf nach vorn
			}
			poseStack.scale(SCALE, SCALE, SCALE);
			PlayerModel model = skin.model() == PlayerModelType.SLIM ? slim : wide;
			collector.submitModel(model, mini, poseStack, RenderTypes.entityTranslucent(skin.body().texturePath()), light,
					OverlayTexture.NO_OVERLAY, 0, null);
			poseStack.popPose();
		}
	}

	/**
	 * Eigener Zustand für ein Mini-Me: macht nach, was der Spieler tut – schaut in dieselbe Richtung, schlägt mit,
	 * duckt sich mit –, läuft aber nicht mit (die Haltung kommt vom Platz: sitzen, fliegen, krabbeln).
	 */
	private static AvatarRenderState miniState(AvatarRenderState owner, PlayerSkin skin, float t) {
		AvatarRenderState mini = new AvatarRenderState();
		mini.skin = skin;
		mini.ageInTicks = t;
		mini.yRot = owner.yRot;
		mini.xRot = owner.xRot;
		mini.attackTime = owner.attackTime;
		mini.attackArm = owner.attackArm;
		mini.mainArm = owner.mainArm;
		mini.isCrouching = owner.isCrouching;
		mini.showHat = true;
		mini.showJacket = true;
		mini.showLeftSleeve = true;
		mini.showRightSleeve = true;
		mini.showLeftPants = true;
		mini.showRightPants = true;
		return mini;
	}
}
