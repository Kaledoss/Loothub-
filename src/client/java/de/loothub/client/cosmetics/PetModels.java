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

	/** Watschelnder Pinguin mit Flossen. */
	static final class Penguin extends EntityModel<LivingEntityRenderState> {
		private final ModelPart body, head, leftWing, rightWing, leftFoot, rightFoot;

		Penguin() {
			this(create().bakeRoot());
		}

		private Penguin(ModelPart root) {
			super(root);
			body = root.getChild("body");
			head = root.getChild("head");
			leftWing = root.getChild("left_wing");
			rightWing = root.getChild("right_wing");
			leftFoot = root.getChild("left_foot");
			rightFoot = root.getChild("right_foot");
		}

		static LayerDefinition create() {
			MeshDefinition mesh = new MeshDefinition();
			PartDefinition r = mesh.getRoot();
			r.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-3F, -8F, -2.5F, 6F, 8F, 5F),
					PartPose.offset(0F, 23F, 0F));
			PartDefinition head = r.addOrReplaceChild("head", CubeListBuilder.create().texOffs(24, 0)
					.addBox(-2.5F, -4F, -2F, 5F, 4F, 4F), PartPose.offset(0F, 15F, 0F));
			head.addOrReplaceChild("beak", CubeListBuilder.create().texOffs(44, 0).addBox(-1F, -2F, -4F, 2F, 1F, 2F),
					PartPose.ZERO);
			r.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(0, 16).addBox(0F, 0F, -1.5F, 1F, 5F, 3F),
					PartPose.offset(3F, 16F, 0F));
			r.addOrReplaceChild("right_wing", CubeListBuilder.create().texOffs(10, 16).addBox(-1F, 0F, -1.5F, 1F, 5F, 3F),
					PartPose.offset(-3F, 16F, 0F));
			r.addOrReplaceChild("left_foot", CubeListBuilder.create().texOffs(20, 16).addBox(-1F, 0F, -2F, 2F, 1F, 2F),
					PartPose.offset(1.5F, 23F, -1F));
			r.addOrReplaceChild("right_foot", CubeListBuilder.create().texOffs(30, 16).addBox(-1F, 0F, -2F, 2F, 1F, 2F),
					PartPose.offset(-1.5F, 23F, -1F));
			return LayerDefinition.create(mesh, 64, 32);
		}

		@Override
		public void setupAnim(LivingEntityRenderState s) {
			super.setupAnim(s);
			float t = s.ageInTicks;
			float walk = Math.min(1F, s.walkAnimationSpeed);
			// Watscheln: von Seite zu Seite kippen, Füße abwechselnd
			float waddle = Mth.sin(t * 0.45F) * (0.06F + 0.18F * walk);
			body.zRot = waddle;
			head.zRot = waddle * 0.6F;
			head.xRot = Mth.sin(t * 0.07F) * 0.08F;
			leftFoot.xRot = Mth.sin(t * 0.45F) * 0.6F * walk;
			rightFoot.xRot = -Mth.sin(t * 0.45F) * 0.6F * walk;
			// ab und zu mit den Flossen schlagen
			float flap = Mth.sin(t * 0.06F) > 0.85F ? Mth.sin(t * 1.4F) * 0.6F : 0F;
			leftWing.zRot = -0.12F - Math.abs(flap) - waddle;
			rightWing.zRot = 0.12F + Math.abs(flap) - waddle;
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
