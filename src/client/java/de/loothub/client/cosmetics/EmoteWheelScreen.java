package de.loothub.client.cosmetics;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Emote-Rad (Taste B): ein echtes rundes Rad mit Segmenten. Das Segment unter der Maus leuchtet in der Akzentfarbe
 * und tritt etwas hervor, in der Mitte steht der Name der Geste. Klick spielt sie ab (Spieler und eigene Mini-Mes,
 * nur die eigene Darstellung). Beim Öffnen wächst das Rad kurz auf.
 */
public class EmoteWheelScreen extends Screen {
	private static final String[] ICONS = {"✋", "★", "♪", "↓", "✦", "♥", "☹", "☾"};
	private static final int R_IN = 34, R_OUT = 96, POP = 7, GAP = 1;
	private static final int SEG = 0xC8181420, SEG_ALT = 0xC81E1A28, SEG_ON = 0xE8C8323A, RIM = 0xFF3A3346, RIM_ON = 0xFFFF6A6A;
	private static final int CENTER = 0xE00E0B12;
	private final long opened = System.currentTimeMillis();

	public EmoteWheelScreen() {
		super(Component.translatable("loothub.emotes.title"));
	}

	private int count() {
		return Emotes.WHEEL.length;
	}

	/** Segment-Nummer zu einem Winkel (0 = oben, im Uhrzeigersinn). */
	private int segmentAt(double dx, double dy) {
		int n = count();
		double a = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / n;
		a = (a % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
		return (int) (a / (2 * Math.PI / n)) % n;
	}

	private int hovered(double mx, double my) {
		double dx = mx - width / 2.0, dy = my - height / 2.0;
		double d = Math.sqrt(dx * dx + dy * dy);
		return d < R_IN - 4 ? -1 : segmentAt(dx, dy); // auch außerhalb des Rings: Richtung zählt
	}

	/** 0..1 – kurzes Aufziehen beim Öffnen. */
	private float grow() {
		float k = Math.min(1.0F, (System.currentTimeMillis() - opened) / 140.0F);
		return 1.0F - (1.0F - k) * (1.0F - k) * (1.0F - k);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int cx = width / 2, cy = height / 2;
		int n = count();
		int sel = hovered(mouseX, mouseY);
		float k = grow();
		int rIn = Math.round(R_IN * k), rOut = Math.round(R_OUT * k);

		// Ring Zeile für Zeile: jede Zeile in Stücke je Segment teilen (ergibt runde Kanten ohne Textur)
		int max = rOut + POP + 1;
		for (int y = -max; y <= max; y++) {
			for (int x = -max; x <= max; ) {
				double d = Math.sqrt(x * x + (double) y * y);
				int seg = segmentAt(x + 0.5, y + 0.5);
				int outer = rOut + (seg == sel ? POP : 0);
				if (d < rIn || d > outer + 1 || nearBoundary(x + 0.5, y + 0.5, d)) {
					x++;
					continue;
				}
				// so weit wie möglich in einem Stück füllen
				int x2 = x + 1;
				while (x2 <= max) {
					double d2 = Math.sqrt(x2 * x2 + (double) y * y);
					if (segmentAt(x2 + 0.5, y + 0.5) != seg || d2 < rIn || d2 > outer + 1 || nearBoundary(x2 + 0.5, y + 0.5, d2)
							|| (d2 > outer) != (d > outer) || (d2 < rIn + 1) != (d < rIn + 1)) {
						break;
					}
					x2++;
				}
				boolean rim = d > outer || d < rIn + 1;
				int c = rim ? (seg == sel ? RIM_ON : RIM) : seg == sel ? SEG_ON : (seg % 2 == 0 ? SEG : SEG_ALT);
				g.fill(cx + x, cy + y, cx + x2, cy + y + 1, c);
				x = x2;
			}
		}
		// Mitte
		for (int y = -rIn + 3; y <= rIn - 3; y++) {
			int w = (int) Math.floor(Math.sqrt(Math.max(0, (rIn - 3) * (rIn - 3) - y * y)));
			g.fill(cx - w, cy + y, cx + w + 1, cy + y + 1, CENTER);
		}
		if (k < 1.0F) {
			return;
		}
		// Symbole und Namen
		for (int i = 0; i < n; i++) {
			double a = -Math.PI / 2 + i * 2 * Math.PI / n;
			boolean on = i == sel;
			double r = (R_IN + R_OUT) / 2.0 + (on ? POP / 2.0 : 0);
			int x = cx + (int) Math.round(Math.cos(a) * r);
			int y = cy + (int) Math.round(Math.sin(a) * r);
			float s = on ? 2.4F : 2.0F;
			g.pose().pushMatrix();
			g.pose().translate(x, y - (on ? 9 : 7));
			g.pose().scale(s, s);
			g.drawCenteredString(font, ICONS[i], 0, 0, on ? 0xFFFFFFFF : 0xFFD8CFE0);
			g.pose().popMatrix();
			Component name = Component.translatable("loothub.emotes." + Emotes.WHEEL[i]);
			g.drawCenteredString(font, name, x, y + 12, on ? 0xFFFFFFFF : 0xFF9C93A6);
		}
		if (sel >= 0) {
			Component name = Component.translatable("loothub.emotes." + Emotes.WHEEL[sel]);
			g.drawCenteredString(font, name, cx, cy - 9, 0xFFFFFFFF);
			g.drawCenteredString(font, Component.translatable("loothub.emotes.click"), cx, cy + 3, 0xFFFF8A8A);
		} else {
			g.drawCenteredString(font, title, cx, cy - 4, 0xFFE0E0E0);
		}
	}

	/** Schmale Fuge zwischen den Segmenten. */
	private boolean nearBoundary(double x, double y, double d) {
		int n = count();
		double a = Math.atan2(y, x) + Math.PI / 2 + Math.PI / n;
		double step = 2 * Math.PI / n;
		a = (a % step + step) % step;
		double off = Math.min(a, step - a) * d; // Abstand zur Fuge in Pixeln
		return off < GAP;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0x60000000);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		int i = hovered(e.x(), e.y());
		if (i >= 0) {
			Emotes.play(Emotes.WHEEL[i]);
		}
		onClose();
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
