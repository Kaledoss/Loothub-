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

	private static float[] pose(Object state) {
		float[] p = de.loothub.client.quick.AfkView.pose(state);
		return p == null ? new float[5] : p;
	}

	/** Anheben aus der AFK-Haltung: beim Liegen etwas hoch, damit der Körper nicht im Boden steckt, plus Sprung. */
	private static float lift(float[] p) {
		float lie = Math.max(0.0F, Math.min(1.0F, p[0] / 90.0F));
		return 0.2F * lie + p[1];
	}

	/** In setupRotations (Welt-Ausrichtung, y nach oben, Füße bei 0). */
	public static void apply(PoseStack poseStack, Object state) {
		float[] p = pose(state);
		float fly = Motion.fly(state);
		if (p[0] != 0.0F || p[1] != 0.0F) {
			poseStack.translate(0.0F, lift(p), 0.0F);
			poseStack.mulPose(Axis.XP.rotationDegrees(p[0]));
		}
		if (fly > 0.0F) {
			// um die Körpermitte nach vorn kippen, Kopf voraus
			poseStack.translate(0.0F, FLY_PIVOT, 0.0F);
			poseStack.mulPose(Axis.XP.rotationDegrees(-FLY_PITCH * fly));
			poseStack.translate(0.0F, -FLY_PIVOT, 0.0F);
		}
	}

	/** Hebt {@link #apply} im Ebenen-Raum (y nach unten, Hals bei 0, Füße bei 1,5) wieder auf. */
	public static void undo(PoseStack poseStack, Object state, boolean withFly) {
		float[] p = pose(state);
		float fly = withFly ? Motion.fly(state) : 0.0F;
		boolean lie = p[0] != 0.0F || p[1] != 0.0F;
		if (!lie && fly <= 0.0F) {
			return;
		}
		Matrix4f q = new Matrix4f().translate(0.0F, 1.501F, 0.0F).scale(-1.0F, -1.0F, 1.0F);
		if (fly > 0.0F) {
			q.translate(0.0F, FLY_PIVOT, 0.0F)
					.rotateX((float) Math.toRadians(FLY_PITCH * fly))
					.translate(0.0F, -FLY_PIVOT, 0.0F);
		}
		if (lie) {
			q.rotateX((float) Math.toRadians(-p[0])).translate(0.0F, -lift(p), 0.0F);
		}
		q.scale(-1.0F, -1.0F, 1.0F).translate(0.0F, -1.501F, 0.0F);
		poseStack.last().pose().mul(q);
		poseStack.last().normal().mul(new Matrix3f(q));
	}
}
