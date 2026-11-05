package de.loothub.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Eigene Körperhaltungen (Hinlegen in der AFK-Ansicht, Flug-Haltung) – angewendet in {@code setupRotations} und für
 * Mini-Mes, die nicht mitkippen sollen, im Ebenen-Raum wieder aufgehoben.
 */
public final class BodyPose {
	/** Neigung der Flug-Haltung (Grad nach vorn). */
	public static final float FLY_PITCH = 80.0F;
	private static final float FLY_PIVOT = 0.9F;

	private BodyPose() {
	}

	/** In setupRotations (Welt-Ausrichtung, y nach oben, Füße bei 0). */
	public static void apply(PoseStack poseStack, float lie, float fly) {
		if (lie > 0.0F) {
			poseStack.translate(0.0F, 0.2F * lie, 0.0F);
			poseStack.mulPose(Axis.XP.rotationDegrees(90.0F * lie));
		}
		if (fly > 0.0F) {
			// um die Körpermitte nach vorn kippen, Kopf voraus
			poseStack.translate(0.0F, FLY_PIVOT, 0.0F);
			poseStack.mulPose(Axis.XP.rotationDegrees(-FLY_PITCH * fly));
			poseStack.translate(0.0F, -FLY_PIVOT, 0.0F);
		}
	}

	/** Hebt {@link #apply} im Ebenen-Raum (y nach unten, Hals bei 0, Füße bei 1,5) wieder auf. */
	public static void undo(PoseStack poseStack, float lie, float fly) {
		if (lie <= 0.0F && fly <= 0.0F) {
			return;
		}
		Matrix4f q = new Matrix4f().translate(0.0F, 1.501F, 0.0F).scale(-1.0F, -1.0F, 1.0F);
		if (fly > 0.0F) {
			q.translate(0.0F, FLY_PIVOT, 0.0F)
					.rotateX((float) Math.toRadians(FLY_PITCH * fly))
					.translate(0.0F, -FLY_PIVOT, 0.0F);
		}
		if (lie > 0.0F) {
			q.rotateX((float) Math.toRadians(-90.0F * lie)).translate(0.0F, -0.2F * lie, 0.0F);
		}
		q.scale(-1.0F, -1.0F, 1.0F).translate(0.0F, -1.501F, 0.0F);
		poseStack.last().pose().mul(q);
		poseStack.last().normal().mul(new Matrix3f(q));
	}
}
