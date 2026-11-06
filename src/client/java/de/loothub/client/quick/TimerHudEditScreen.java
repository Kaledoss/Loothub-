package de.loothub.client.quick;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import de.loothub.client.jobs.WidgetConfig;

/** Timer-Anzeige verschieben (aus den Einstellungen): mit der Maus ziehen, „Fertig“ oder Esc führt zurück. */
public class TimerHudEditScreen extends Screen {
	private final Screen parent;
	private boolean dragging;
	private double grabX, grabY;

	public TimerHudEditScreen(Screen parent) {
		super(Component.translatable("loothub.timer.editor.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("loothub.look_info.editor.reset"), b -> {
			WidgetConfig c = WidgetConfig.get();
			c.timerHudX = 0.99;
			c.timerHudY = 0.35;
			c.save();
		}).bounds(width / 2 - 124, height - 28, 120, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(width / 2 + 4, height - 28, 120, 20).build());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawString(font, getTitle(), (width - font.width(getTitle())) / 2, height / 2 - 20, 0xFFFFFFFF, true);
		Component hint = Component.translatable("loothub.timer.editor.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, height / 2 - 8, 0xFFAAAAAA, true);
		Timers.drawPreview(g, width, height);
		int[] b = Timers.bounds(width, height);
		int c = dragging || over(mouseX, mouseY) ? 0xFFFFFFFF : 0xFFE23B3B;
		g.fill(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1], c);
		g.fill(b[0] - 1, b[1] + b[3], b[0] + b[2] + 1, b[1] + b[3] + 1, c);
		g.fill(b[0] - 1, b[1], b[0], b[1] + b[3], c);
		g.fill(b[0] + b[2], b[1], b[0] + b[2] + 1, b[1] + b[3], c);
	}

	private boolean over(double mx, double my) {
		int[] b = Timers.bounds(width, height);
		return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		if (e.button() == 0 && over(e.x(), e.y())) {
			int[] b = Timers.bounds(width, height);
			dragging = true;
			grabX = e.x() - b[0];
			grabY = e.y() - b[1];
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging) {
			Timers.moveTo(e.x() - grabX, e.y() - grabY, width, height);
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
