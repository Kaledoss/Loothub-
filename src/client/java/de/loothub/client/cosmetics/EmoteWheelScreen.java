package de.loothub.client.cosmetics;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Emote-Rad (Taste B): Gesten im Kreis, Maus darauf zeigen und klicken – alle eigenen Mini-Mes machen die Geste ein
 * paar Sekunden lang. Nur die eigene Darstellung.
 */
public class EmoteWheelScreen extends Screen {
	private static final String[] ICONS = {"✋", "★", "♪", "↓", "✦", "♥", "☹", "☾"};
	private static final int R_IN = 30, R_OUT = 92;

	public EmoteWheelScreen() {
		super(Component.translatable("loothub.emotes.title"));
	}

	private int hovered(double mx, double my) {
		double dx = mx - width / 2.0, dy = my - height / 2.0;
		double d = Math.sqrt(dx * dx + dy * dy);
		if (d < R_IN || d > R_OUT + 20) {
			return -1;
		}
		int n = Emotes.WHEEL.length;
		double a = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / n; // 0 = oben
		a = (a % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
		return (int) (a / (2 * Math.PI / n)) % n;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int cx = width / 2, cy = height / 2;
		int n = Emotes.WHEEL.length;
		int sel = hovered(mouseX, mouseY);
		for (int i = 0; i < n; i++) {
			double a = -Math.PI / 2 + i * 2 * Math.PI / n;
			int x = cx + (int) Math.round(Math.cos(a) * (R_IN + R_OUT) / 2.0);
			int y = cy + (int) Math.round(Math.sin(a) * (R_IN + R_OUT) / 2.0);
			boolean on = i == sel;
			int r = on ? 24 : 20;
			g.fill(x - r, y - r, x + r, y + r, on ? 0xD0E23B3B : 0xB0140B0D);
			g.fill(x - r, y - r, x + r, y - r + 1, 0xFFE23B3B);
			g.pose().pushMatrix();
			g.pose().translate(x, y - 8);
			g.pose().scale(2f, 2f);
			g.drawCenteredString(font, ICONS[i], 0, 0, 0xFFFFFFFF);
			g.pose().popMatrix();
			Component name = Component.translatable("loothub.emotes." + Emotes.WHEEL[i]);
			g.drawCenteredString(font, name, x, y + 10, on ? 0xFFFFFFFF : 0xFFB9A9AB);
		}
		g.drawCenteredString(font, title, cx, cy - 4, 0xFFE0E0E0);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0x50000000);
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
