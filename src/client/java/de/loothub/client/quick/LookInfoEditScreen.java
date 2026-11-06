package de.loothub.client.quick;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import de.loothub.client.jobs.WidgetConfig;

/** Block-Info verschieben (aus den Einstellungen): mit der Maus ziehen, „Fertig“ oder Esc führt zurück. */
public class LookInfoEditScreen extends Screen {
	private final Screen parent;
	private boolean dragging;
	private double grabX, grabY;

	public LookInfoEditScreen(Screen parent) {
		super(Component.translatable("loothub.look_info.editor.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("loothub.look_info.editor.reset"), b -> {
			WidgetConfig c = WidgetConfig.get();
			c.lookInfoX = 0.5;
			c.lookInfoY = 0.0;
			c.save();
		}).bounds(width / 2 - 124, height - 28, 120, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(width / 2 + 4, height - 28, 120, 20).build());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawString(font, getTitle(), (width - font.width(getTitle())) / 2, height / 2 - 20, 0xFFFFFFFF, true);
		Component hint = Component.translatable("loothub.look_info.editor.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, height / 2 - 8, 0xFFAAAAAA, true);
		LookInfo.drawPreview(g, width, height);
		int[] b = LookInfo.bounds();
		int c = dragging || over(mouseX, mouseY) ? 0xFFFFFFFF : 0xFFE23B3B;
		g.fill(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1], c);
		g.fill(b[0] - 1, b[1] + b[3], b[0] + b[2] + 1, b[1] + b[3] + 1, c);
		g.fill(b[0] - 1, b[1], b[0], b[1] + b[3], c);
		g.fill(b[0] + b[2], b[1], b[0] + b[2] + 1, b[1] + b[3], c);
	}

	private boolean over(double mx, double my) {
		int[] b = LookInfo.bounds();
		return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		if (e.button() == 0 && over(e.x(), e.y())) {
			int[] b = LookInfo.bounds();
			dragging = true;
			grabX = e.x() - (b[0] + b[2] / 2.0);
			grabY = e.y() - b[1];
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging) {
			LookInfo.moveTo(e.x() - grabX, e.y() - grabY, width, height);
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
}
