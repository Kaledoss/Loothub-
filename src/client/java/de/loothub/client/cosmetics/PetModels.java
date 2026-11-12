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

	/** Kleiner Drache mit Flügelschlag und schwingendem Schwanz. */
	static final class Dragon extends EntityModel<LivingEntityRenderState> {
		private final ModelPart body, neck, head, tail, leftWing, rightWing;

		Dragon() {
			this(create().bakeRoot());
		}

		private Dragon(ModelPart root) {
			super(root);
			body = root.getChild("body");
			neck = body.getChild("neck");
			head = neck.getChild("head");
			tail = body.getChild("tail");
			leftWing = body.getChild("left_wing");
			rightWing = body.getChild("right_wing");
		}

		static LayerDefinition create() {
			MeshDefinition mesh = new MeshDefinition();
			PartDefinition r = mesh.getRoot();
			PartDefinition body = r.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0)
					.addBox(-3F, -2F, -6F, 6F, 4F, 12F), PartPose.offset(0F, 16F, 0F));
			PartDefinition neck = body.addOrReplaceChild("neck", CubeListBuilder.create().texOffs(0, 16)
					.addBox(-1.5F, -1.5F, -5F, 3F, 3F, 5F), PartPose.offsetAndRotation(0F, -1F, -6F, -0.35F, 0F, 0F));
			PartDefinition head = neck.addOrReplaceChild("head", CubeListBuilder.create().texOffs(20, 16)
					.addBox(-2.5F, -2F, -5F, 5F, 4F, 5F), PartPose.offsetAndRotation(0F, 0F, -5F, 0.35F, 0F, 0F));
			head.addOrReplaceChild("snout", CubeListBuilder.create().texOffs(40, 16).addBox(-1.5F, 0F, -3F, 3F, 2F, 3F),
					PartPose.offset(0F, -0.5F, -5F));
			body.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(0, 28).addBox(-1F, -1F, 0F, 2F, 2F, 8F),
					PartPose.offsetAndRotation(0F, 0F, 6F, 0.2F, 0F, 0F));
			body.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(0, 40).addBox(0F, -0.5F, -4F, 12F, 1F, 8F),
					PartPose.offset(3F, -1.5F, -1F));
			body.addOrReplaceChild("right_wing", CubeListBuilder.create().texOffs(0, 52).mirror()
					.addBox(-12F, -0.5F, -4F, 12F, 1F, 8F), PartPose.offset(-3F, -1.5F, -1F));
			for (int i = 0; i < 4; i++) {
				float x = i % 2 == 0 ? 2F : -3F;
				float z = i < 2 ? -4F : 3F;
				body.addOrReplaceChild("leg" + i, CubeListBuilder.create().texOffs(48, 0).addBox(0F, 0F, 0F, 1F, 3F, 1F),
						PartPose.offset(x, 2F, z));
			}
			return LayerDefinition.create(mesh, 64, 64);
		}

		@Override
		public void setupAnim(LivingEntityRenderState s) {
			super.setupAnim(s);
			float t = s.ageInTicks;
			float flap = Mth.sin(t * 0.32F);
			leftWing.zRot = -0.25F + flap * 0.7F;
			rightWing.zRot = 0.25F - flap * 0.7F;
			body.y = 16F + flap * 0.6F; // hebt sich mit jedem Flügelschlag
			tail.yRot = Mth.sin(t * 0.12F) * 0.35F;
			tail.xRot = 0.2F + Mth.sin(t * 0.09F) * 0.1F;
			head.yRot = Mth.sin(t * 0.05F) * 0.3F;
			neck.xRot = -0.35F + Mth.sin(t * 0.08F) * 0.08F;
		}
	}
}
