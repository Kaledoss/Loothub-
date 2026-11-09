package de.loothub.client.cosmetics;

import net.minecraft.client.model.geom.ModelPart;

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
				applyLife(s, false);
				return;
			}
			float p = e[1];
			float k = Emotes.steady((int) e[0]) ? 1.0F : Emotes.envelope(p);
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
				case Emotes.SCRATCH -> {
					// am Kopf kratzen
					rightArm.xRot = mix(rightArm.xRot, (float) Math.toRadians(-150.0) + (float) Math.sin(p * 45.0F) * 0.12F, k);
					rightArm.zRot = mix(rightArm.zRot, -0.55F, k);
					head.zRot = mix(head.zRot, 0.12F, k);
				}
				case Emotes.HOLD -> {
					// beide Hände nach innen oben – halten sich am Kopf des Spielers fest
					rightArm.xRot = mix(rightArm.xRot, (float) Math.toRadians(-140.0), k);
					leftArm.xRot = mix(leftArm.xRot, (float) Math.toRadians(-140.0), k);
					rightArm.zRot = mix(rightArm.zRot, -0.6F, k);
					leftArm.zRot = mix(leftArm.zRot, 0.6F, k);
				}
				case Emotes.RAIN -> {
					// Hände über dem Kopf als Regenschutz
					rightArm.xRot = mix(rightArm.xRot, (float) Math.toRadians(-170.0), k);
					leftArm.xRot = mix(leftArm.xRot, (float) Math.toRadians(-170.0), k);
					rightArm.zRot = mix(rightArm.zRot, -0.32F, k);
					leftArm.zRot = mix(leftArm.zRot, 0.32F, k);
					head.xRot = mix(head.xRot, 0.2F, k);
				}
				case Emotes.SHIVER -> {
					// Arme vor der Brust verschränkt, zittert
					float j = (float) Math.sin(p * 40.0F) * 0.05F;
					rightArm.xRot = mix(rightArm.xRot, -0.95F + j, k);
					leftArm.xRot = mix(leftArm.xRot, -0.95F - j, k);
					rightArm.yRot = mix(rightArm.yRot, -0.65F, k);
					leftArm.yRot = mix(leftArm.yRot, 0.65F, k);
					head.zRot = mix(head.zRot, j, k);
				}
				case Emotes.FAN -> {
					// sich Luft zufächeln
					rightArm.xRot = mix(rightArm.xRot, -1.7F + (float) Math.sin(p * 60.0F) * 0.35F, k);
					rightArm.zRot = mix(rightArm.zRot, -0.35F, k);
					head.xRot = mix(head.xRot, -0.15F, k);
				}
				case Emotes.NOD -> {
					// Gespräch: abwechselnd erzählen (nicken, mit der Hand gestikulieren) und zuhören (Kopf schief)
					Life.Pose lp = Life.pose(s);
					float sd = lp == null ? 1F : lp.side;
					boolean talking = (p < 0.5F) == (sd > 0);
					if (talking) {
						head.xRot += (float) Math.sin(p * 40.0F) * 0.14F * k;
						ModelPart arm = sd > 0 ? rightArm : leftArm;
						arm.xRot = mix(arm.xRot, -0.9F + (float) Math.sin(p * 25.0F) * 0.25F, k);
					} else {
						head.zRot = mix(head.zRot, sd * 0.18F, k);
					}
				}
				case Emotes.HIGHFIVE -> {
					// innerer Arm hoch und zur Mitte – in der Mitte des Verlaufs „klatscht“ es
					Life.Pose lp = Life.pose(s);
					float sd = lp == null ? 1F : lp.side;
					ModelPart arm = sd > 0 ? rightArm : leftArm;
					float in = 0.3F + 0.5F * (float) Math.sin(p * Math.PI);
					arm.xRot = mix(arm.xRot, (float) Math.toRadians(-170.0), k);
					arm.zRot = mix(arm.zRot, sd > 0 ? -in : in, k);
				}
				case Emotes.TIRED -> {
					head.xRot = mix(head.xRot, 0.55F, k);
					head.zRot = mix(head.zRot, (float) Math.sin(p * 0.6F) * 0.08F, k);
					rightArm.zRot = mix(rightArm.zRot, 0.02F, k);
					leftArm.zRot = mix(leftArm.zRot, -0.02F, k);
				}
				case Emotes.POKE -> {
					// Krabbler spielen: abwechselnd anstupsen
					Life.Pose lp = Life.pose(s);
					float sd = lp == null ? 1F : lp.side;
					boolean mine = ((int) (p * 6)) % 2 == (sd > 0 ? 0 : 1);
					ModelPart arm = sd > 0 ? rightArm : leftArm;
					arm.xRot = mix(arm.xRot, mine ? -1.5F + (float) Math.sin(p * 60.0F) * 0.3F : -0.3F, k);
					arm.yRot = mix(arm.yRot, sd > 0 ? 0.6F : -0.6F, k);
					head.zRot = mix(head.zRot, (float) Math.sin(p * 30.0F) * 0.12F, k);
				}
				case Emotes.DANGLE -> {
					// Beine baumeln abwechselnd
					float swing = (float) Math.sin(p * 4.5F) * 0.35F;
					rightLeg.xRot += swing;
					leftLeg.xRot -= swing;
				}
				default -> {
				}
			}
			applyLife(s, (int) e[0] == Emotes.DANGLE || (int) e[0] == Emotes.HOLD);
		}

		/** Lebendigkeit: Atmen, baumelnde Beine, nachziehende Arme/Beine, Arme schwingen beim Landen, zucken. */
		private void applyLife(AvatarRenderState s, boolean legsBusy) {
			Life.Pose lp = Life.pose(s);
			if (lp == null) {
				return;
			}
			float br = (lp.breath + 1F) * 0.5F;
			rightArm.zRot += 0.04F * br + lp.armSpread + 0.45F * lp.armSwing;
			leftArm.zRot -= 0.04F * br + lp.armSpread + 0.45F * lp.armSwing;
			head.xRot += 0.025F * lp.breath;
			if (!legsBusy) {
				rightLeg.xRot += lp.legR;
				leftLeg.xRot += lp.legL;
			}
			// Flug: Arme und Beine ziehen nach hinten
			rightArm.xRot += 0.6F * lp.armDrag;
			leftArm.xRot += 0.6F * lp.armDrag;
			rightLeg.xRot += 0.45F * lp.armDrag;
			leftLeg.xRot += 0.4F * lp.armDrag;
			// Schreck: Arme vors Gesicht
			if (lp.flinch > 0.01F) {
				float f = lp.flinch;
				rightArm.xRot = mix(rightArm.xRot, -1.9F, f);
				leftArm.xRot = mix(leftArm.xRot, -1.9F, f);
				rightArm.yRot = mix(rightArm.yRot, -0.5F, f);
				leftArm.yRot = mix(leftArm.yRot, 0.5F, f);
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
				if (s == null && !SkinLookup.gaveUp(other)) {
					continue; // erst zeigen, wenn der richtige Skin da ist – nicht zwischendurch mit falschem Skin
				}
				if (s != null) {
					skin = s; // fremder Skin (nicht gefunden: eigener)
				}
			}
			float side = i % 2 == 0 ? 1.0F : -1.0F; // gerade = rechts, ungerade = links (Modellraum)
			AvatarRenderState mini = miniState(state.id, i, skin, t);
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
				ty += vy * 0.35F; // Trägheit: beim Absprung kurz eingedrückt, beim Fallen kurz angehoben
			} else if (i < 4) {
				// fliegend: schräg hinter dem Spieler, weich schwebend und leicht nach vorn geneigt
				// weiter außen und höher als die Schulter-Mini-Mes, damit sie nicht dahinter verschwinden
				tx = side * (1.0F + (float) Math.sin(t * 0.045F + i) * 0.06F);
				ty = -0.75F + (float) Math.sin(t * 0.07F + i * 1.3F) * 0.08F;
				tz = 0.75F;
				rx = 15.0F + Math.max(0.0F, fwd) * 60.0F;
				rx = rx + (75.0F - rx) * fly; // fliegt der Spieler, liegen sie auch waagerecht in der Luft
				rz = side * (float) Math.sin(t * 0.05F + i) * 5.0F + Mth.clamp(lag * 0.4F, -20.0F, 20.0F);
			} else {
				// krabbelnd auf allen vieren: Rumpf waagerecht in Beinlänge über dem Boden, Kopf nach vorn
				tx = side * 1.0F; // etwas Abstand zum Spieler
				ty = 1.5F - 0.75F * SCALE;
				tz = -0.2F;
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
			int env = Motion.env(state);
			boolean idleLong = Motion.idle(state) > 5.0F;
			boolean swimming = i >= 4 && (env & Motion.ENV_WATER) != 0 && fly < 0.5F && lie < 0.5F;
			// Krabbler setzen sich hin, wenn der Spieler eine Weile stillsteht
			boolean sitDown = i >= 4 && idleLong && !swimming && fly < 0.5F && lie < 0.5F;
			if (sitDown) {
				rx = 0.0F;
			}
			if (i >= 2) {
				// eigenständig: folgt seinem Platz gefedert, schwebt/krabbelt etwas herum, schaut in Laufrichtung;
				// am Boden klettern sie Stufen hoch statt durch Blöcke zu laufen
				boolean air = i < 4 || fly > 0.5F;
				float[] f = Followers.follow(state, i, tx, ty, tz, air ? 6.0F : 9.0F, air ? 0.35F : (sitDown ? 0.0F : 0.2F),
						air ? 0.5F : 0.0F, !air && lie < 0.5F && !swimming);
				tx = f[0];
				ty = f[1];
				tz = f[2];
				ry = f[3];
			}
			boolean sleeping = lie > 0.5F;
			if ((i < 2 || sitDown) && !sleeping) {
				mini.isPassenger = true;
			} else if (swimming) {
				mini.swimAmount = 1.0F;
				mini.walkAnimationPos = t * 0.35F + i;
				mini.walkAnimationSpeed = 0.8F;
			} else if (i >= 4 && !sleeping) {
				mini.walkAnimationPos = crawlPhase(state, i);
				mini.walkAnimationSpeed = Math.min(1.0F, state.walkAnimationSpeed * 1.5F);
			}
			boolean crawling = i >= 4 && !sleeping && fly < 0.5F && !sitDown && !swimming;
			if (!sleeping && !crawling && !swimming) {
				look(mini, state, i, side, t, sitDown);
			}
			// Lebendigkeit: natürlicher Blick, Federn beim Landen, Wippen im Flug, Reaktionen
			String trait = Cosmetics.miniMeTrait(state, i);
			int mate = i ^ 1;
			boolean partner = mate < skins.size() && skins.get(mate) != null;
			Life.Frame life = Life.step(state, mini, i, i < 2 ? 0 : i < 4 ? 1 : 2, t, sleeping, mini.yRot, mini.xRot, side, trait,
					tx, ty, tz);
			if (!sleeping && !crawling && !swimming && life.look()) {
				mini.yRot = life.lookYaw();
				mini.xRot = life.lookPitch();
			}
			ty += life.dy();
			rz += life.rz();
			float[] emote = crawling || swimming ? null : Emotes.pick(state, i, sleeping, i < 2 || sitDown, partner, trait);
			if (emote != null && (emote[0] == Emotes.NOD || emote[0] == Emotes.POKE || emote[0] == Emotes.HIGHFIVE)) {
				// zum Partner drehen (der sitzt/fliegt/krabbelt auf der anderen Seite)
				float k = Emotes.envelope(emote[1]);
				k = Math.min(1F, k * 2F);
				mini.yRot += (side * 70F - mini.yRot) * k;
				mini.xRot += ((emote[0] == Emotes.HIGHFIVE ? -20F : 5F) - mini.xRot) * k;
			}
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
			if (life.sx() != 1F || life.sy() != 1F) {
				// um die Füße (bzw. beim Sitzen um die Hüfte) stauchen/strecken
				float pivot = mini.isPassenger ? 0.75F : 1.5F;
				poseStack.translate(0F, pivot, 0F);
				poseStack.scale(life.sx(), life.sy(), life.sx());
				poseStack.translate(0F, -pivot, 0F);
			}
			boolean slimSkin = skin.model() == PlayerModelType.SLIM;
			PlayerModel model = crawling ? (slimSkin ? crawlSlim : crawlWide) : (slimSkin ? slim : wide);
			collector.submitModel(model, mini, poseStack, RenderTypes.entityTranslucent(skin.body().texturePath()), light,
					OverlayTexture.NO_OVERLAY, 0, null);
			String wings = Cosmetics.miniMeWing(state, i);
			if (wings != null) {
				// liegt der Körper waagerecht (krabbelnd oder in Flug-Haltung), Flügel tiefer an den Rücken und
				// nach hinten legen – sonst ragen sie vorn durch den Kopf
				float flat = crawling ? 1.0F : sleeping ? 0.0F : Mth.clamp((rx - 30.0F) / 45.0F, 0.0F, 1.0F);
				if (flat > 0.0F) {
					poseStack.translate(0.0F, 0.3F * flat, 0.05F * flat);
					poseStack.mulPose(Axis.XP.rotationDegrees(-55.0F * flat));
				}
				// Flügel je Haltung: fliegend offen und lebhaft, sitzend halb offen, krabbelnd/schlafend angelegt
				Wings.Pose wp = sleeping ? new Wings.Pose(0.1F, t * 0.05F + i, 0.1F, 0.0F)
						: (i == 2 || i == 3 || fly > 0.5F) ? new Wings.Pose(0.9F, t * 0.25F + i * 1.3F, 0.8F, -ry)
						: crawling ? new Wings.Pose(0.35F, t * 0.08F + i, 0.2F, 0.0F)
						: new Wings.Pose(0.5F, t * 0.1F + i, 0.35F, 0.0F);
				Wings.submit(poseStack, collector, wings, wp, light, Cosmetics.miniMeWingColor(state, i),
						Cosmetics.miniMeWingColorOuter(state, i));
			}
			// Privatnachricht: kurz ein Briefumschlag über dem ersten Mini-Me
			float mail = Emotes.envelope();
			if (mail >= 0 && Motion.local(state) && i == firstSlot(skins) && Life.amount() > 0) {
				float rise = mail * 0.5F;
				float fade = mail < 0.15F ? mail / 0.15F : mail > 0.8F ? (1F - mail) / 0.2F : 1F;
				poseStack.pushPose();
				poseStack.translate(0F, -1.1F - rise, 0F);
				poseStack.mulPose(Axis.YP.rotationDegrees(t * 6F));
				int a = Math.max(0, Math.min(255, (int) (fade * 255)));
				int argb = (a << 24) | 0xFFFFFF;
				collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(ENVELOPE), (pose, vc) -> {
					float[][] v = {{-0.35F, -0.35F, 0F, 0F}, {0.35F, -0.35F, 1F, 0F}, {0.35F, 0.35F, 1F, 1F}, {-0.35F, 0.35F, 0F, 1F}};
					for (int q = 0; q < 4; q++) { // Vorderseite
						vc.addVertex(pose, v[q][0], v[q][1], 0F).setColor(argb).setUv(v[q][2], v[q][3])
								.setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0F, 0F, 1F);
					}
					for (int q = 3; q >= 0; q--) { // Rückseite
						vc.addVertex(pose, v[q][0], v[q][1], 0F).setColor(argb).setUv(v[q][2], v[q][3])
								.setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(pose, 0F, 0F, -1F);
					}
				});
				poseStack.popPose();
			}
			poseStack.popPose();
		}
	}

	/**
	 * Blickkontakt: im eigenen Takt schaut jedes Mini-Me mal zum Spieler hoch, mal dorthin, wohin der Spieler schaut,
	 * mal neugierig umher – mit weichen Übergängen.
	 */
	private static void look(AvatarRenderState mini, AvatarRenderState player, int i, float side, float t, boolean grounded) {
		float sec = t / 20.0F + i * 1.7F;
		float cyc = sec % 10.0F;
		// Blick zum Spieler (Kopf): Richtung Mitte und je nach Platz nach oben/unten
		float toYaw = side * (i < 2 ? 50.0F : 35.0F);
		float toPitch = i < 2 ? -25.0F : i < 4 ? 12.0F : -40.0F;
		float[][] targets = {
				{toYaw, toPitch},
				{Mth.clamp(player.yRot, -60.0F, 60.0F), Mth.clamp(player.xRot, -40.0F, 40.0F)},
				{(float) Math.sin(sec * 0.7F) * 60.0F, (float) Math.sin(sec * 0.5F + i) * 15.0F}};
		int a = cyc < 3.5F ? 0 : cyc < 6.5F ? 1 : 2;
		int b = (a + 1) % 3;
		float edge = a == 0 ? 3.5F : a == 1 ? 6.5F : 10.0F;
		float blend = Mth.clamp((cyc - (edge - 0.6F)) / 0.6F, 0.0F, 1.0F);
		blend = blend * blend * (3.0F - 2.0F * blend);
		mini.yRot = targets[a][0] + (targets[b][0] - targets[a][0]) * blend;
		mini.xRot = targets[a][1] + (targets[b][1] - targets[a][1]) * blend;
	}

	private static final net.minecraft.resources.Identifier ENVELOPE =
			net.minecraft.resources.Identifier.withDefaultNamespace("textures/item/paper.png");

	private static int firstSlot(List<String> skins) {
		for (int k = 0; k < skins.size(); k++) {
			if (skins.get(k) != null) {
				return k;
			}
		}
		return -1;
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
	/** Zustände je Mini-Me wiederverwenden statt jedes Bild neu anzulegen (weniger Speicher-Müll, flüssiger). */
	private static final java.util.Map<Long, AvatarRenderState> MINI_STATES = new java.util.HashMap<>();

	private static AvatarRenderState miniState(int playerId, int slot, PlayerSkin skin, float t) {
		long key = ((long) playerId << 4) | slot;
		AvatarRenderState mini = MINI_STATES.get(key);
		if (mini == null) {
			if (MINI_STATES.size() > 600) {
				MINI_STATES.clear();
			}
			mini = new AvatarRenderState();
			MINI_STATES.put(key, mini);
		}
		mini.isPassenger = false;
		mini.swimAmount = 0.0F;
		mini.walkAnimationPos = 0.0F;
		mini.walkAnimationSpeed = 0.0F;
		mini.yRot = 0.0F;
		mini.xRot = 0.0F;
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
