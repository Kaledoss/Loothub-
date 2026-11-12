package de.loothub.client.cosmetics;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;

/** Eigene kleine Haustier-Modelle (Loothub-Design): Pinguin und Mini-Enderdrache. */
final class PetModels {
	private PetModels() {
	}

	/**
	 * Watschelnder Pinguin im Minecraft-Stil (Würfel wie Vanilla-Tiere, 64×64-Pixeltextur): rundlicher Rumpf mit
	 * weißem Bauch, eigener Kopf mit Schnabel, Flossen, Füße und kleiner Schwanz.
	 */
	static final class Penguin extends EntityModel<LivingEntityRenderState> {
		private final ModelPart body, head, leftWing, rightWing, leftFoot, rightFoot;

		Penguin() {
			this(create().bakeRoot());
		}

		private Penguin(ModelPart root) {
			super(root);
			body = root.getChild("body");
			head = body.getChild("head");
			leftWing = body.getChild("left_wing");
			rightWing = body.getChild("right_wing");
			leftFoot = root.getChild("left_foot");
			rightFoot = root.getChild("right_foot");
		}

		static LayerDefinition create() {
			MeshDefinition mesh = new MeshDefinition();
			PartDefinition r = mesh.getRoot();
			// Rumpf: Drehpunkt unten, damit er beim Watscheln auf den Füßen kippt
			PartDefinition body = r.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0)
					.addBox(-4F, -9F, -3.5F, 8F, 9F, 7F), PartPose.offset(0F, 23F, 0F));
			PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create().texOffs(32, 0)
					.addBox(-3.5F, -6F, -3F, 7F, 6F, 6F), PartPose.offset(0F, -9F, -0.5F));
			head.addOrReplaceChild("beak", CubeListBuilder.create().texOffs(32, 16).addBox(-1.5F, -3F, -5F, 3F, 1F, 2F),
					PartPose.ZERO);
			body.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(0, 20).addBox(0F, 0F, -2F, 1F, 7F, 4F),
					PartPose.offset(4F, -8F, 0F));
			body.addOrReplaceChild("right_wing", CubeListBuilder.create().texOffs(12, 20).addBox(-1F, 0F, -2F, 1F, 7F, 4F),
					PartPose.offset(-4F, -8F, 0F));
			body.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(24, 26).addBox(-2F, -1F, 0F, 4F, 1F, 2F),
					PartPose.offsetAndRotation(0F, -0.5F, 3.5F, -0.35F, 0F, 0F));
			r.addOrReplaceChild("left_foot", CubeListBuilder.create().texOffs(24, 20).addBox(-1.5F, 0F, -3F, 3F, 1F, 3F),
					PartPose.offset(2F, 23F, -0.5F));
			r.addOrReplaceChild("right_foot", CubeListBuilder.create().texOffs(40, 20).addBox(-1.5F, 0F, -3F, 3F, 1F, 3F),
					PartPose.offset(-2F, 23F, -0.5F));
			return LayerDefinition.create(mesh, 64, 64);
		}

		@Override
		public void setupAnim(LivingEntityRenderState s) {
			super.setupAnim(s);
			float t = s.ageInTicks;
			float walk = Math.min(1F, s.walkAnimationSpeed);
			float ph = t * 0.45F;
			// Watscheln: auf dem jeweils belasteten Fuß zur Seite kippen, Füße heben sich abwechselnd
			float waddle = Mth.sin(ph) * (0.04F + 0.2F * walk);
			body.zRot = waddle;
			body.xRot = 0.08F * walk; // beim Laufen etwas nach vorn
			head.zRot = -waddle * 0.5F; // Kopf gleicht aus
			head.xRot = Mth.sin(t * 0.07F) * 0.06F - 0.08F * walk;
			head.yRot = Mth.sin(t * 0.031F) * 0.25F * (1F - walk); // schaut sich im Stehen um
			leftFoot.xRot = Math.max(0F, Mth.sin(ph)) * -0.7F * walk;
			rightFoot.xRot = Math.max(0F, -Mth.sin(ph)) * -0.7F * walk;
			leftFoot.y = 23F - Math.max(0F, Mth.sin(ph)) * 1.2F * walk;
			rightFoot.y = 23F - Math.max(0F, -Mth.sin(ph)) * 1.2F * walk;
			// Flossen balancieren mit; ab und zu fröhliches Flattern
			float flap = Mth.sin(t * 0.06F) > 0.85F ? Math.abs(Mth.sin(t * 1.4F)) * 0.7F : 0F;
			leftWing.zRot = -0.1F - flap - 0.25F * walk * Math.max(0F, Mth.sin(ph));
			rightWing.zRot = 0.1F + flap + 0.25F * walk * Math.max(0F, -Mth.sin(ph));
		}
	}

	/**
	 * Mini-Drache im Minecraft-Stil (Würfel, 128×64-Pixeltextur): großer Kopf mit Hörnern und leuchtenden Augen,
	 * zweiteiliger Hals, dreiteiliger Schwanz mit Stacheln, Flügel aus Knochen und Flughaut mit beweglicher Spitze.
	 */
	static final class Dragon extends EntityModel<LivingEntityRenderState> {
		private final ModelPart body, neck1, neck2, head, tail1, tail2, tail3, leftWing, rightWing, leftTip, rightTip;
		private final ModelPart[] legs = new ModelPart[4];

		Dragon() {
			this(create().bakeRoot());
		}

		private Dragon(ModelPart root) {
			super(root);
			body = root.getChild("body");
			neck1 = body.getChild("neck1");
			neck2 = neck1.getChild("neck2");
			head = neck2.getChild("head");
			tail1 = body.getChild("tail1");
			tail2 = tail1.getChild("tail2");
			tail3 = tail2.getChild("tail3");
			leftWing = body.getChild("left_wing");
			rightWing = body.getChild("right_wing");
			leftTip = leftWing.getChild("tip");
			rightTip = rightWing.getChild("tip");
			for (int i = 0; i < 4; i++) {
				legs[i] = body.getChild("leg" + i);
			}
		}

		static LayerDefinition create() {
			MeshDefinition mesh = new MeshDefinition();
			PartDefinition r = mesh.getRoot();
			PartDefinition body = r.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0)
					.addBox(-4F, -3F, -7F, 8F, 6F, 14F), PartPose.offset(0F, 15F, 0F));
			for (int i = 0; i < 3; i++) { // Rückenstacheln
				body.addOrReplaceChild("spine" + i, CubeListBuilder.create().texOffs(48, 20).addBox(-0.5F, -2F, -1F, 1F, 2F, 2F),
						PartPose.offset(0F, -3F, -4F + i * 4.5F));
			}
			PartDefinition neck1 = body.addOrReplaceChild("neck1", CubeListBuilder.create().texOffs(44, 0)
					.addBox(-2F, -2F, -4F, 4F, 4F, 4F), PartPose.offsetAndRotation(0F, -1.5F, -7F, -0.3F, 0F, 0F));
			PartDefinition neck2 = neck1.addOrReplaceChild("neck2", CubeListBuilder.create().texOffs(60, 0)
					.addBox(-1.5F, -1.5F, -3F, 3F, 3F, 3F), PartPose.offsetAndRotation(0F, 0F, -4F, -0.25F, 0F, 0F));
			PartDefinition head = neck2.addOrReplaceChild("head", CubeListBuilder.create().texOffs(72, 0)
					.addBox(-3.5F, -3.5F, -6F, 7F, 6F, 6F), PartPose.offsetAndRotation(0F, 0F, -2.5F, 0.55F, 0F, 0F));
			head.addOrReplaceChild("snout", CubeListBuilder.create().texOffs(98, 0).addBox(-2F, -1F, -4F, 4F, 3F, 4F),
					PartPose.offset(0F, 0F, -6F));
			head.addOrReplaceChild("horn_l", CubeListBuilder.create().texOffs(114, 0).addBox(-0.5F, -3F, -0.5F, 1F, 3F, 1F),
					PartPose.offsetAndRotation(2F, -3.5F, -1.5F, -0.6F, 0F, 0.2F));
			head.addOrReplaceChild("horn_r", CubeListBuilder.create().texOffs(114, 0).addBox(-0.5F, -3F, -0.5F, 1F, 3F, 1F),
					PartPose.offsetAndRotation(-2F, -3.5F, -1.5F, -0.6F, 0F, -0.2F));
			PartDefinition tail1 = body.addOrReplaceChild("tail1", CubeListBuilder.create().texOffs(0, 20)
					.addBox(-2F, -2F, 0F, 4F, 4F, 5F), PartPose.offsetAndRotation(0F, -0.5F, 7F, -0.12F, 0F, 0F));
			PartDefinition tail2 = tail1.addOrReplaceChild("tail2", CubeListBuilder.create().texOffs(18, 20)
					.addBox(-1.5F, -1.5F, 0F, 3F, 3F, 5F), PartPose.offset(0F, 0F, 5F));
			tail2.addOrReplaceChild("tail3", CubeListBuilder.create().texOffs(34, 20).addBox(-1F, -1F, 0F, 2F, 2F, 5F)
					.texOffs(48, 20).addBox(-0.5F, -2.5F, 1F, 1F, 2F, 2F), PartPose.offset(0F, 0F, 5F));
			// Flügel: Knochen vorn, Flughaut dahinter; die Spitze ist ein eigenes Gelenk
			PartDefinition lw = body.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(62, 20)
					.addBox(0F, -1F, -1F, 10F, 2F, 2F).texOffs(0, 30).addBox(0F, 0F, 1F, 10F, 0F, 9F),
					PartPose.offset(4F, -2F, -3F));
			lw.addOrReplaceChild("tip", CubeListBuilder.create().texOffs(62, 24).addBox(0F, -0.5F, -0.5F, 9F, 1F, 1F)
					.texOffs(40, 30).addBox(0F, 0F, 0.5F, 9F, 0F, 9F), PartPose.offset(10F, 0F, 0F));
			PartDefinition rw = body.addOrReplaceChild("right_wing", CubeListBuilder.create().mirror().texOffs(62, 20)
					.addBox(-10F, -1F, -1F, 10F, 2F, 2F).texOffs(0, 30).addBox(-10F, 0F, 1F, 10F, 0F, 9F),
					PartPose.offset(-4F, -2F, -3F));
			rw.addOrReplaceChild("tip", CubeListBuilder.create().mirror().texOffs(62, 24).addBox(-9F, -0.5F, -0.5F, 9F, 1F, 1F)
					.texOffs(40, 30).addBox(-9F, 0F, 0.5F, 9F, 0F, 9F), PartPose.offset(-10F, 0F, 0F));
			for (int i = 0; i < 4; i++) {
				float x = i % 2 == 0 ? 2.5F : -2.5F;
				float z = i < 2 ? -4.5F : 4.5F;
				body.addOrReplaceChild("leg" + i, CubeListBuilder.create().texOffs(54, 20).addBox(-1F, 0F, -1F, 2F, 4F, 2F),
						PartPose.offset(x, 2.5F, z));
			}
			return LayerDefinition.create(mesh, 128, 64);
		}

		@Override
		public void setupAnim(LivingEntityRenderState s) {
			super.setupAnim(s);
			float t = s.ageInTicks;
			float ph = t * 0.3F;
			float flap = Mth.sin(ph);
			float lag = Mth.sin(ph - 0.9F); // die Spitzen schwingen nach
			leftWing.zRot = -0.15F - flap * 0.6F;
			rightWing.zRot = 0.15F + flap * 0.6F;
			leftTip.zRot = -0.25F - lag * 0.45F;
			rightTip.zRot = 0.25F + lag * 0.45F;
			leftWing.yRot = -0.1F + flap * 0.08F;
			rightWing.yRot = 0.1F - flap * 0.08F;
			body.y = 15F + flap * 0.8F; // hebt sich mit jedem Abschlag
			body.xRot = Mth.sin(t * 0.05F) * 0.05F;
			// Schwanz wellt sich, jedes Glied etwas später
			tail1.yRot = Mth.sin(t * 0.12F) * 0.25F;
			tail2.yRot = Mth.sin(t * 0.12F - 0.7F) * 0.3F;
			tail3.yRot = Mth.sin(t * 0.12F - 1.4F) * 0.35F;
			tail1.xRot = -0.12F + Mth.sin(t * 0.09F) * 0.06F;
			// Hals und Kopf schauen sich um
			neck1.yRot = Mth.sin(t * 0.04F) * 0.2F;
			neck2.yRot = Mth.sin(t * 0.04F - 0.5F) * 0.15F;
			neck1.xRot = -0.3F + Mth.sin(t * 0.08F) * 0.06F;
			head.yRot = Mth.sin(t * 0.05F + 1.0F) * 0.2F;
			// Beine baumeln im Flug
			for (int i = 0; i < 4; i++) {
				legs[i].xRot = 0.5F + Mth.sin(t * 0.1F + i) * 0.12F;
			}
		}
	}

	/**
	 * Himmelsdrache (eigener Entwurf im Minecraft-Stil): langer, schlangenartiger Drache in Rot mit goldenem Bauch und
	 * goldener Mähne, kleinen Beinen und Barthaaren. Er „schwimmt“ durch die Luft – eine Welle läuft vom Kopf bis zur
	 * Schwanzflosse durch alle Glieder.
	 */
	static final class Serpent extends EntityModel<LivingEntityRenderState> {
		private static final int SEGMENTS = 8;
		private final ModelPart head;
		private final ModelPart[] seg = new ModelPart[SEGMENTS];
		private final ModelPart tail;
		private final ModelPart[] legs = new ModelPart[4];

		Serpent() {
			this(create().bakeRoot());
		}

		private Serpent(ModelPart root) {
			super(root);
			head = root.getChild("head");
			ModelPart p = root;
			for (int i = 0; i < SEGMENTS; i++) {
				p = p.getChild("seg" + i);
				seg[i] = p;
			}
			tail = p.getChild("tail");
			legs[0] = seg[1].getChild("leg_l");
			legs[1] = seg[1].getChild("leg_r");
			legs[2] = seg[5].getChild("leg_l");
			legs[3] = seg[5].getChild("leg_r");
		}

		static LayerDefinition create() {
			MeshDefinition mesh = new MeshDefinition();
			PartDefinition r = mesh.getRoot();
			PartDefinition head = r.addOrReplaceChild("head", CubeListBuilder.create().texOffs(16, 0)
					.addBox(-3F, -3F, -6F, 6F, 5F, 6F), PartPose.offset(0F, 14F, -14F));
			head.addOrReplaceChild("snout", CubeListBuilder.create().texOffs(40, 0).addBox(-2F, -1F, -4F, 4F, 3F, 4F),
					PartPose.offset(0F, 0F, -6F));
			head.addOrReplaceChild("horn_l", CubeListBuilder.create().texOffs(56, 0).addBox(-0.5F, -4F, -0.5F, 1F, 4F, 1F),
					PartPose.offsetAndRotation(1.8F, -3F, -1F, -0.8F, 0F, 0.25F));
			head.addOrReplaceChild("horn_r", CubeListBuilder.create().texOffs(56, 0).addBox(-0.5F, -4F, -0.5F, 1F, 4F, 1F),
					PartPose.offsetAndRotation(-1.8F, -3F, -1F, -0.8F, 0F, -0.25F));
			head.addOrReplaceChild("whisker_l", CubeListBuilder.create().texOffs(8, 10).addBox(0F, -0.5F, -0.5F, 1F, 1F, 5F),
					PartPose.offsetAndRotation(2F, 1F, -8F, 0.3F, 0.9F, 0F));
			head.addOrReplaceChild("whisker_r", CubeListBuilder.create().texOffs(8, 10).addBox(-1F, -0.5F, -0.5F, 1F, 1F, 5F),
					PartPose.offsetAndRotation(-2F, 1F, -8F, 0.3F, -0.9F, 0F));
			// Körper: Kette aus Gliedern, jedes hängt am vorigen – so läuft die Welle hindurch
			PartDefinition parent = r;
			for (int i = 0; i < SEGMENTS; i++) {
				PartPose pose = i == 0 ? PartPose.offset(0F, 14.5F, -14F) : PartPose.offset(0F, 0F, 4F);
				PartDefinition s = parent.addOrReplaceChild("seg" + i, CubeListBuilder.create().texOffs(0, 0)
						.addBox(-2F, -2F, 0F, 4F, 4F, 4F).texOffs(0, 10).addBox(-0.5F, -3.5F, 1F, 1F, 2F, 2F), pose);
				if (i == 1 || i == 5) {
					s.addOrReplaceChild("leg_l", CubeListBuilder.create().texOffs(32, 12).addBox(-1F, 0F, -1F, 2F, 3F, 2F),
							PartPose.offsetAndRotation(2F, 1.5F, 2F, 0.6F, 0F, -0.3F));
					s.addOrReplaceChild("leg_r", CubeListBuilder.create().texOffs(32, 12).addBox(-1F, 0F, -1F, 2F, 3F, 2F),
							PartPose.offsetAndRotation(-2F, 1.5F, 2F, 0.6F, 0F, 0.3F));
				}
				parent = s;
			}
			PartDefinition tail = parent.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(20, 12)
					.addBox(-1F, -1F, 0F, 2F, 2F, 4F), PartPose.offset(0F, 0F, 4F));
			tail.addOrReplaceChild("fin", CubeListBuilder.create().texOffs(40, 12).addBox(-2F, 0F, 0F, 4F, 0F, 3F),
					PartPose.offset(0F, 0F, 3.5F));
			return LayerDefinition.create(mesh, 64, 64);
		}

		@Override
		public void setupAnim(LivingEntityRenderState s) {
			super.setupAnim(s);
			float t = s.ageInTicks;
			// Welle vom Kopf nach hinten: seitlich schlängeln und leicht auf und ab
			for (int i = 0; i < SEGMENTS; i++) {
				seg[i].yRot = Mth.sin(t * 0.14F - i * 0.7F) * 0.32F;
				seg[i].xRot = Mth.sin(t * 0.1F - i * 0.55F) * 0.16F;
			}
			tail.yRot = Mth.sin(t * 0.14F - SEGMENTS * 0.7F) * 0.4F;
			// Kopf ruhig halten (gleicht die Welle aus) und sich umschauen
			head.yRot = Mth.sin(t * 0.05F) * 0.3F;
			head.xRot = Mth.sin(t * 0.07F) * 0.1F;
			head.y = 14F + Mth.sin(t * 0.1F + 0.5F) * 0.6F;
			for (int i = 0; i < 4; i++) {
				legs[i].xRot = 0.6F + Mth.sin(t * 0.2F + i * 1.3F) * 0.35F; // paddeln durch die Luft
			}
		}
	}
}
