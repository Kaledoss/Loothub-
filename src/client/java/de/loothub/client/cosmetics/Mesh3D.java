package de.loothub.client.cosmetics;

import java.io.DataInputStream;
import java.io.InputStream;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import de.loothub.Loothub;

/**
 * Echtes 3D-Modell (mit Ludo erzeugt, vorab in ein schlankes Format umgewandelt: Dreiecke mit Position, UV und
 * Normale). Modellraum wie bei Minecraft-Modellen: y nach unten, vorn = −z; die Unterkante liegt bei y = 0, die
 * Größe ist auf 1 genormt (Höhe bzw. Breite). Gezeichnet wird jedes Dreieck als Viereck mit doppelter letzter Ecke.
 */
public final class Mesh3D {
	/** Verformt einen Punkt vor dem Zeichnen (z. B. Flügelschlag). {@code p} = {x, y, z, nx, ny, nz} wird verändert. */
	public interface Deform {
		void apply(float[] p);
	}

	private final Identifier model;
	private final Identifier texture;
	private float[] data; // je Ecke 8 Werte
	private boolean failed;

	public Mesh3D(String name) {
		this.model = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "models/pet/" + name + ".bin");
		this.texture = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/" + name + "3d.png");
	}

	/** Ist das Modell geladen (sonst kann der alte Würfel-Ersatz gezeichnet werden)? */
	public boolean ready() {
		if (data == null && !failed) {
			load();
		}
		return data != null;
	}

	/** Rohdaten (je Ecke x, y, z, u, v, nx, ny, nz) – zum Auswerten, nicht verändern. */
	public float[] data() {
		return ready() ? data : new float[0];
	}

	private void load() {
		try {
			var res = Minecraft.getInstance().getResourceManager().getResource(model);
			if (res.isEmpty()) {
				failed = true;
				return;
			}
			try (InputStream in = res.get().open(); DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in))) {
				int tris = d.readInt();
				float[] f = new float[tris * 3 * 8];
				for (int i = 0; i < f.length; i++) {
					f[i] = d.readFloat();
				}
				data = f;
			}
		} catch (Exception e) {
			Loothub.LOGGER.warn("3D-Modell {} konnte nicht geladen werden", model, e);
			failed = true;
		}
	}

	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, Deform deform) {
		if (!ready()) {
			return;
		}
		final float[] d = data;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(texture), (pose, vc) -> {
			float[] p = new float[6];
			for (int t = 0; t < d.length; t += 24) {
				for (int c = 0; c < 4; c++) {
					int o = t + Math.min(c, 2) * 8;
					p[0] = d[o];
					p[1] = d[o + 1];
					p[2] = d[o + 2];
					p[3] = d[o + 5];
					p[4] = d[o + 6];
					p[5] = d[o + 7];
					if (deform != null) {
						deform.apply(p);
					}
					vertex(pose, vc, p, d[o + 3], d[o + 4], light);
				}
			}
		});
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer vc, float[] p, float u, float v, int light) {
		vc.addVertex(pose, p[0], p[1], p[2]).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light).setNormal(pose, p[3], p[4], p[5]);
	}
}
