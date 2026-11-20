package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.animal.allay.AllayModel;
import net.minecraft.client.model.animal.bee.BeeModel;
import net.minecraft.client.model.animal.wolf.WolfModel;
import net.minecraft.client.model.animal.panda.PandaModel;
import net.minecraft.client.model.monster.zombie.ZombieModel;
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
import net.minecraft.client.renderer.entity.state.PandaRenderState;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
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
	private static final Identifier DONKEY_TEX = Identifier.withDefaultNamespace("textures/entity/horse/donkey.png");
	private static final Identifier PANDA_TEX = Identifier.withDefaultNamespace("textures/entity/panda/panda.png");
	private static final Identifier ZOMBIE_TEX = Identifier.withDefaultNamespace("textures/entity/zombie/zombie.png");
	private static final Identifier RED_PANDA_TEX = Identifier.fromNamespaceAndPath("loothub", "textures/entity/red_panda.png");
	private static final Identifier TURTLE_TEX = Identifier.fromNamespaceAndPath("loothub", "textures/entity/turtle.png");
	private static final Identifier HUMMINGBIRD_TEX = Identifier.fromNamespaceAndPath("loothub", "textures/entity/hummingbird.png");
	private static final Identifier SERPENT_TEX = Identifier.fromNamespaceAndPath("loothub", "textures/entity/sky_serpent.png");

	private final SlimeModel slimeInner;
	private final SlimeModel slimeOuter;
	private final AllayModel allay;
	private final BeeModel bee;
	private final VexModel vex;
	private final WolfModel wolf;
	private final PetModels.Penguin penguin = new PetModels.Penguin();
	private final PetModels.Dragon dragon = new PetModels.Dragon();
	private final PetModels.Serpent serpent = new PetModels.Serpent();
	private final PetModels.RedPanda redPanda = new PetModels.RedPanda();
	private final PetModels.Turtle turtle = new PetModels.Turtle();
	private final PetModels.Hummingbird hummingbird = new PetModels.Hummingbird();
	private final PandaModel panda;
	private final net.minecraft.client.model.animal.equine.DonkeyModel donkey;
	@SuppressWarnings("rawtypes")
	private final ZombieModel zombie;

	public PetsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityModelSet models) {
		super(parent);
		slimeInner = new SlimeModel(models.bakeLayer(ModelLayers.SLIME));
		slimeOuter = new SlimeModel(models.bakeLayer(ModelLayers.SLIME_OUTER));
		allay = new AllayModel(models.bakeLayer(ModelLayers.ALLAY));
		bee = new BeeModel(models.bakeLayer(ModelLayers.BEE));
		vex = new VexModel(models.bakeLayer(ModelLayers.VEX));
		wolf = new WolfModel(models.bakeLayer(ModelLayers.WOLF));
		panda = new PandaModel(models.bakeLayer(ModelLayers.PANDA_BABY));
		donkey = new net.minecraft.client.model.animal.equine.DonkeyModel(models.bakeLayer(ModelLayers.DONKEY_BABY));
		zombie = new ZombieModel(models.bakeLayer(ModelLayers.ZOMBIE_BABY));
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (LayerGuard.off("Haustiere")) {
			return;
		}
		try {
			draw(poseStack, collector, light, state, yRot, xRot);
		} catch (RuntimeException | LinkageError e) {
			LayerGuard.fail("Haustiere", e); // nur diese Ebene abschalten, nicht das Spiel abstürzen lassen
		}
	}

	private void draw(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (state.isInvisible && !FirstPerson.is(state)) {
			return;
		}
		// Kopf-Plätze (eigene Plätze, unabhängig von den folgenden Haustieren)
		for (int slot = 0; slot < Cosmetics.MAX_HEAD; slot++) {
			String type = Cosmetics.headPet(state, slot);
			if (type != null && !Cosmetics.HEAD_MINI.equals(type)) {
				drawOnHead(poseStack, collector, light, state, type, slot, state.ageInTicks);
			}
		}
		if (!Cosmetics.has(state, Cosmetics.PETS)) {
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
			boolean walker = Play.walker(type);
			boolean ground = type.equals("slime") || walker;
			float scale = switch (type) {
				case "slime" -> 0.7F;
				case "bee" -> 0.6F;
				case "vex" -> 0.6F;
				case "wolf" -> 0.45F;
				case "penguin" -> 0.6F;
				case "dragon" -> 0.35F;
				case "serpent" -> 0.35F;
				case "panda" -> 0.55F;
				case "red_panda" -> 0.6F;
				case "donkey" -> 0.6F;
				case "zombie" -> 0.6F;
				case "turtle" -> 0.7F;
				case "hummingbird" -> 0.75F;
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
			float[] curious = lie < 0.5F && fly < 0.5F && !CosmeticsPreviewScreen.is(state) ? Curiosity.target(state, slot, ground) : null;
			if (curious != null) {
				tx = curious[0];
				tz = curious[2];
				if (!ground) {
					ty = curious[1];
				}
			}
			// Spielen: dem rennenden Mini-Me auf dem Kreis hinterherjagen (etwas dahinter)
			float play = walker ? Play.angle(state) : Float.NaN;
			boolean playing = !Float.isNaN(play);
			if (playing) {
				float[] c = Play.at(play - 0.75F - slot * 0.45F);
				tx = c[0];
				tz = c[1];
				curious = null;
			}
			boolean preview = CosmeticsPreviewScreen.is(state);
			if (curious == null && lie < 0.5F && !playing && !preview) {
				// herumstreifen: alle paar Sekunden ein neuer Platz in der Nähe, dazwischen bleibt es stehen
				float[] r = Roam.offset(state.id, 8 + slot, t, side, ground ? 1.3F : 1.0F);
				tx += r[0];
				tz += r[1];
			}
			// eigenständig: gefedert hinterher, schwebt bzw. hüpft um seinen Platz, schaut in Laufrichtung
			boolean hopping = ground && !walker && fly < 0.5F;
			float hopY = hopping ? ty - (1.5F - 1.5F * scale) : 0.0F;
			// in der Vorschau fest am Platz (der Drehteller dreht den Spieler, nicht die Welt)
			float[] p = preview ? new float[]{tx, ty - hopY, tz, 0.0F, 0.0F}
					: Followers.follow(state, 8 + slot, tx, ty - hopY, tz, playing ? 12.0F : ground ? 7.0F : 4.5F,
					curious != null || playing ? 0.05F : ground ? 0.05F : 0.15F, hopping || walker ? 0.0F : 0.6F,
					(hopping || walker && fly < 0.5F) && lie < 0.5F);
			// Landestaub: beim Aufkommen ein paar Partikel des Bodens
			if (hopping) {
				Curiosity.landing(state, slot, hopY > -0.005F, p);
			}
			// nie im Boden verschwinden: Füße mindestens auf dem Boden (Fliegende etwas darüber)
			if (!preview) {
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
					// Minecraft-Stil (Würfelmodell); beim Spielen rutscht er auf dem Bauch hinterher
					if (playing) {
						poseStack.translate(0.0F, 1.5F - 0.28F, 0.0F);
						poseStack.mulPose(Axis.XP.rotationDegrees(80.0F)); // Kopf nach vorn, Bauch nach unten
						poseStack.translate(0.0F, -1.5F, 0.0F);
					}
					submit(collector, poseStack, penguin,
							base(new LivingEntityRenderState(), t, Math.min(1F, Math.max(state.walkAnimationSpeed * 1.5F, p[4] * 2.5F))), PENGUIN_TEX);
				}
				case "panda" -> {
					PandaRenderState s = base(new PandaRenderState(), t, Math.min(1F, Math.max(state.walkAnimationSpeed * 1.5F, p[4] * 2.5F)));
					s.variant = net.minecraft.world.entity.animal.panda.Panda.Gene.NORMAL;
					s.isBaby = true;
					if (playing) {
						s.rollAmount = 1.0F; // purzelt beim Spielen
						s.rollTime = t;
					}
					submit(collector, poseStack, panda, s, PANDA_TEX);
				}
				case "donkey" -> {
					// Eselfohlen (Minecraft-Modell): steigt beim Spielen auf die Hinterbeine, grast beim AFK
					net.minecraft.client.renderer.entity.state.DonkeyRenderState s = base(
							new net.minecraft.client.renderer.entity.state.DonkeyRenderState(), t,
							Math.min(1F, Math.max(state.walkAnimationSpeed * 1.5F, p[4] * 2.5F)));
					s.isBaby = true;
					s.animateTail = true;
					s.standAnimation = playing ? 1F : 0F;
					s.eatAnimation = Mth.clamp((lie - 0.3F) / 0.4F, 0F, 1F);
					submit(collector, poseStack, donkey, s, DONKEY_TEX);
				}
				case "red_panda" -> {
					// Minecraft-Stil (Würfelmodell); beim Spielen richtet er sich auf, beim AFK rollt er sich zusammen
					PetModels.RedPandaState s = base(new PetModels.RedPandaState(), t,
							Math.min(1F, Math.max(state.walkAnimationSpeed * 1.5F, p[4] * 2.5F)));
					s.stand = playing ? 1F : 0F;
					s.curl = Mth.clamp((lie - 0.3F) / 0.4F, 0F, 1F);
					submit(collector, poseStack, redPanda, s, RED_PANDA_TEX);
				}
				case "zombie" -> {
					ZombieRenderState s = base(new ZombieRenderState(), t, Math.min(1F, Math.max(state.walkAnimationSpeed * 1.5F, p[4] * 2.5F)));
					s.isBaby = true;
					s.isAggressive = playing; // jagt mit hochgerissenen Armen
					submit(collector, poseStack, zombie, s, ZOMBIE_TEX);
				}
				case "turtle" -> {
					PetModels.TurtleState s = base(new PetModels.TurtleState(), t,
							Math.min(1F, Math.max(state.walkAnimationSpeed * 1.5F, p[4] * 2.5F)));
					s.hide = Mth.clamp((lie - 0.3F) / 0.4F, 0F, 1F); // AFK: Kopf und Beine einziehen
					submit(collector, poseStack, turtle, s, TURTLE_TEX);
				}
				case "hummingbird" -> submit(collector, poseStack, hummingbird,
						base(new LivingEntityRenderState(), t + slot * 11.0F, 0.0F), HUMMINGBIRD_TEX);
				case "serpent" -> submit(collector, poseStack, serpent, base(new LivingEntityRenderState(), t + slot * 17.0F, 0.0F),
						SERPENT_TEX);
				case "dragon" -> {
					// Minecraft-Stil (Würfelmodell) – passend zum Pinguin
					submit(collector, poseStack, dragon, base(new LivingEntityRenderState(), t + slot * 13.0F, 0.0F), DRAGON_TEX);
				}
				default -> submit(collector, poseStack, vex, base(new VexRenderState(), t, 0.3F), VEX_TEX);
			}
			poseStack.popPose();
		}
	}

	/**
	 * Haustier auf dem Kopf: klein, fest am Kopf (dreht und nickt mit). Wolf sitzt, Roter Panda rollt sich ein, Biene,
	 * Allay, Vex und Himmelsschlange schweben knapp darüber; sind beide Plätze auf dem Kopf, sitzen sie nebeneinander.
	 */
	private void drawOnHead(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
			String type, int slot, float t) {
		if (state.isInvisible && !FirstPerson.is(state)) {
			return;
		}
		if (FirstPerson.is(state)) {
			return; // in der Ego-Perspektive säße es direkt vor der Kamera
		}
		float scale = switch (type) {
			// gut sichtbar, aber nicht breiter als der Kopf
			case "dragon", "serpent" -> 0.34F;
			case "donkey" -> 0.38F;
			case "wolf", "red_panda" -> 0.46F;
			case "turtle" -> 0.5F;
			case "hummingbird" -> 0.6F;
			case "bee", "allay", "vex" -> 0.5F;
			default -> 0.44F;
		};
		boolean hover = type.equals("bee") || type.equals("allay") || type.equals("vex") || type.equals("serpent")
				|| type.equals("dragon") || type.equals("hummingbird");
		boolean both = Cosmetics.headFull(state);
		float lie = de.loothub.client.quick.AfkView.lying(state);
		poseStack.pushPose();
		boolean sit = true;
		if (lie > 0.5F) {
			// Spieler legt sich hin (AFK): das Haustier springt herunter und legt sich neben ihn
			BodyPose.undo(poseStack, state, true);
			float s = scale * 1.3F;
			float side = slot == 0 ? -0.9F : 0.9F; // Kopf links → liegt links, Kopf rechts → rechts
			poseStack.translate(side, 1.5F - 1.5F * s + (hover ? -0.4F : 0.0F), 0.4F);
			poseStack.mulPose(Axis.YP.rotationDegrees(slot == 0 ? -30.0F : 30.0F));
			poseStack.scale(s, s, s);
		} else {
			float dx = both ? (slot == 0 ? -0.19F : 0.19F) : 0.0F;
			float bob = hover ? -0.12F - (float) Math.sin(t * 0.12F + slot * 2.0F) * 0.04F : 0.02F;
			// zu zweit auf dem Kopf: alle ~20 s spielen sie kurz miteinander – drehen sich zueinander und hüpfen
			float turn = 0.0F;
			if (both) {
				float cycle = (t + state.id % 97) % 400.0F;
				if (cycle < 50.0F) {
					float k = Mth.sin(cycle / 50.0F * Mth.PI);
					turn = (slot == 0 ? -70.0F : 70.0F) * k;
					bob -= Math.abs(Mth.sin(cycle * 0.5F + slot * 1.6F)) * 0.08F * k;
					sit = false;
				}
			}
			getParentModel().head.translateAndRotate(poseStack);
			// Kopfoberseite liegt 8 Pixel über dem Drehpunkt; Füße des Modells genau darauf
			poseStack.translate(dx, -0.5F - 1.5F * scale + bob, 0.0F);
			poseStack.mulPose(Axis.YP.rotationDegrees(turn));
			poseStack.scale(scale, scale, scale);
		}
		switch (type) {
			case "slime" -> {
				SlimeRenderState s = base(new SlimeRenderState(), t, 0.0F);
				s.size = 1;
				collector.submitModel(slimeInner, s, poseStack, RenderTypes.entityCutoutNoCull(SLIME_TEX), light,
						OverlayTexture.NO_OVERLAY, 0, null);
				collector.submitModel(slimeOuter, s, poseStack, RenderTypes.entityTranslucent(SLIME_TEX), light,
						OverlayTexture.NO_OVERLAY, 0, null);
			}
			case "allay" -> submit(collector, poseStack, allay, base(new AllayRenderState(), t, 0.0F), ALLAY_TEX);
			case "bee" -> {
				BeeRenderState s = base(new BeeRenderState(), t, 0.0F);
				s.isOnGround = false;
				submit(collector, poseStack, bee, s, BEE_TEX);
			}
			case "wolf" -> {
				WolfRenderState s = base(new WolfRenderState(), t, 0.0F);
				s.isSitting = sit;
				submit(collector, poseStack, wolf, s, WOLF_TEX);
			}
			case "penguin" -> submit(collector, poseStack, penguin, base(new LivingEntityRenderState(), t, 0.0F), PENGUIN_TEX);
			case "panda" -> {
				PandaRenderState s = base(new PandaRenderState(), t, 0.0F);
				s.variant = net.minecraft.world.entity.animal.panda.Panda.Gene.NORMAL;
				s.isBaby = true;
				submit(collector, poseStack, panda, s, PANDA_TEX);
			}
			case "donkey" -> {
				net.minecraft.client.renderer.entity.state.DonkeyRenderState s = base(
						new net.minecraft.client.renderer.entity.state.DonkeyRenderState(), t, 0.0F);
				s.isBaby = true;
				s.animateTail = true;
				s.eatAnimation = lie > 0.5F ? 1.0F : 0.0F; // neben dem schlafenden Spieler: grasen
				submit(collector, poseStack, donkey, s, DONKEY_TEX);
			}
			case "red_panda" -> {
				PetModels.RedPandaState s = base(new PetModels.RedPandaState(), t, 0.0F);
				s.curl = sit ? 1.0F : 0.0F; // eingerollt auf dem Kopf
				submit(collector, poseStack, redPanda, s, RED_PANDA_TEX);
			}
			case "zombie" -> {
				ZombieRenderState s = base(new ZombieRenderState(), t, 0.0F);
				s.isBaby = true;
				submit(collector, poseStack, zombie, s, ZOMBIE_TEX);
			}
			case "turtle" -> {
				PetModels.TurtleState s = base(new PetModels.TurtleState(), t, 0.0F);
				s.hide = lie > 0.5F ? 1.0F : 0.0F;
				submit(collector, poseStack, turtle, s, TURTLE_TEX);
			}
			case "hummingbird" -> submit(collector, poseStack, hummingbird, base(new LivingEntityRenderState(), t + slot * 11.0F, 0.0F),
					HUMMINGBIRD_TEX);
			case "serpent" -> submit(collector, poseStack, serpent, base(new LivingEntityRenderState(), t + slot * 17.0F, 0.0F),
					SERPENT_TEX);
			case "dragon" -> submit(collector, poseStack, dragon, base(new LivingEntityRenderState(), t + slot * 13.0F, 0.0F),
					DRAGON_TEX);
			default -> submit(collector, poseStack, vex, base(new VexRenderState(), t, 0.0F), VEX_TEX);
		}
		poseStack.popPose();
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
