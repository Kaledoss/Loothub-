package de.loothub.client.cosmetics;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import de.loothub.Loothub;

/** Flügelpaar als flaches Bild für Menüs (Vorschau beim Einstellen von Stil und Farben). */
public final class WingPreview {
	private static final int TW = 528, TH = 560;

	private WingPreview() {
	}

	/**
	 * Zeichnet beide Flügel nebeneinander, Ansatz in der Mitte {@code cx}, oben bei {@code top}, {@code height} hoch.
	 * {@code inner}/{@code outer}: „#RRGGBB“ oder null (Originalfarben).
	 */
	public static void draw(GuiGraphics g, int cx, int top, int height, String style, String inner, String outer) {
		if (style == null || !Cosmetics.WING_STYLES.contains(style)) {
			return;
		}
		boolean in = inner != null && !inner.isBlank(), out = outer != null && !outer.isBlank();
		float k = height / (float) TH;
		for (int side = -1; side <= 1; side += 2) {
			g.pose().pushMatrix();
			g.pose().translate(cx + side * 1.0F, top);
			g.pose().scale(side * k, k);
			if (!in && !out) {
				blit(g, tex(style, ""), 0xFFFFFFFF);
			} else {
				blit(g, tex(style, in ? "_in_gray" : "_in"), in ? 0xFF000000 | AuraTextures.parse(inner) : 0xFFFFFFFF);
				blit(g, tex(style, out ? "_out_gray" : "_out"), out ? 0xFF000000 | AuraTextures.parse(outer) : 0xFFFFFFFF);
			}
			g.pose().popMatrix();
		}
	}

	private static void blit(GuiGraphics g, Identifier texture, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0F, 0.0F, TW, TH, TW, TH, TW, TH, color);
	}

	private static Identifier tex(String style, String part) {
		return Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/wing_" + style + part + ".png");
	}
}
