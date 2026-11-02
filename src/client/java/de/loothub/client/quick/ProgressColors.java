package de.loothub.client.quick;

import net.minecraft.client.gui.GuiGraphics;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Färbt Fortschrittsbalken nach Fortschritt: Rot → Orange → Gelb, die letzten 6 % Grün.
 * Wird über die gefüllte Fläche der Vanilla-Leiste (182×5 px) gelegt.
 */
public final class ProgressColors {
	private static final int BAR_W = 182;
	private static final int BAR_H = 5;
	/** Ab hier grün. */
	private static final float GREEN_FROM = 0.94f;

	private static final int[] RED = {0xE0, 0x30, 0x30};
	private static final int[] ORANGE = {0xFF, 0x8A, 0x2A};
	private static final int[] YELLOW = {0xFF, 0xE0, 0x40};
	private static final int[] GREEN = {0x50, 0xD8, 0x50};

	private ProgressColors() {
	}

	/** Grundfarbe (RGB) für einen Fortschritt von 0 bis 1. */
	public static int color(float progress) {
		float p = Math.max(0, Math.min(1, progress));
		if (p >= GREEN_FROM) {
			return rgb(GREEN);
		}
		float t = p / GREEN_FROM; // 0 … 1 bis zur grünen Zone
		return t < 0.5f ? mix(RED, ORANGE, t / 0.5f) : mix(ORANGE, YELLOW, (t - 0.5f) / 0.5f);
	}

	public static void drawBossBar(GuiGraphics g, int x, int y, float progress) {
		if (!WidgetConfig.get().colorProgressBars) {
			return;
		}
		int w = (int) (Math.max(0, Math.min(1, progress)) * (BAR_W + 1));
		w = Math.min(w, BAR_W);
		if (w <= 0) {
			return;
		}
		int base = color(progress);
		// wie die Vanilla-Leiste: oben heller, unten dunkler
		g.fill(x, y, x + w, y + 1, 0xFF000000 | shade(base, 1.25f));
		g.fill(x, y + 1, x + w, y + BAR_H - 1, 0xFF000000 | base);
		g.fill(x, y + BAR_H - 1, x + w, y + BAR_H, 0xFF000000 | shade(base, 0.6f));
	}

	private static int mix(int[] a, int[] b, float k) {
		int r = Math.round(a[0] + (b[0] - a[0]) * k);
		int gr = Math.round(a[1] + (b[1] - a[1]) * k);
		int bl = Math.round(a[2] + (b[2] - a[2]) * k);
		return (r << 16) | (gr << 8) | bl;
	}

	private static int rgb(int[] c) {
		return (c[0] << 16) | (c[1] << 8) | c[2];
	}

	private static int shade(int rgb, float f) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
		int gr = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
		int bl = Math.min(255, Math.round((rgb & 0xFF) * f));
		return (r << 16) | (gr << 8) | bl;
	}
}
