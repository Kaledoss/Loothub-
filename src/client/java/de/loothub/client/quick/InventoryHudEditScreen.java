package de.loothub.client.quick;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import de.loothub.client.jobs.WidgetConfig;

/** Inventar-Anzeige über der Hotbar verschieben; „Standard“ setzt sie wieder über die Hotbar. */
public class InventoryHudEditScreen extends Screen {
	private final Screen parent;
	private boolean dragging;
	private double grabX, grabY;

	public InventoryHudEditScreen(Screen parent) {
		super(Component.translatable("loothub.inv_hud.editor.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("loothub.inv_hud.editor.reset"), b -> {
			WidgetConfig.get().invHudX = -1;
			WidgetConfig.get().invHudY = -1;
		}).bounds(width / 2 - 124, height - 28, 120, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(width / 2 + 4, height - 28, 120, 20).build());
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		g.drawString(font, getTitle(), (width - font.width(getTitle())) / 2, 12, 0xFFFFFFFF, true);
		Component hint = Component.translatable("loothub.job.editor.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, 24, 0xFFAAAAAA, true);
		int[] p = InventoryHud.position(width, height);
		InventoryHud.draw(g, p[0], p[1]);
		int c = dragging || over(mx, my) ? 0xFFFFFFFF : 0xFFE23B3B;
		g.fill(p[0] - 1, p[1] - 1, p[0] + InventoryHud.W + 1, p[1], c);
		g.fill(p[0] - 1, p[1] + InventoryHud.H, p[0] + InventoryHud.W + 1, p[1] + InventoryHud.H + 1, c);
		g.fill(p[0] - 1, p[1], p[0], p[1] + InventoryHud.H, c);
		g.fill(p[0] + InventoryHud.W, p[1], p[0] + InventoryHud.W + 1, p[1] + InventoryHud.H, c);
	}

	private boolean over(double mx, double my) {
		int[] p = InventoryHud.position(width, height);
		return mx >= p[0] && mx < p[0] + InventoryHud.W && my >= p[1] && my < p[1] + InventoryHud.H;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		if (e.button() == 0 && over(e.x(), e.y())) {
			int[] p = InventoryHud.position(width, height);
			dragging = true;
			grabX = e.x() - p[0];
			grabY = e.y() - p[1];
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging) {
			WidgetConfig c = WidgetConfig.get();
			c.invHudX = Math.max(0, Math.min(1, (e.x() - grabX) / Math.max(1, width - InventoryHud.W)));
			c.invHudY = Math.max(0, Math.min(1, (e.y() - grabY) / Math.max(1, height - InventoryHud.H)));
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging) {
			dragging = false;
			WidgetConfig.get().save();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public void onClose() {
		WidgetConfig.get().save();
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
