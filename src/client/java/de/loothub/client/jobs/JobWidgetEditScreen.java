package de.loothub.client.jobs;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import de.loothub.client.Tabs;

/**
 * Editor für das Job-Widget (Reiter „Job-Widget“ im Loothub+-Menü, Taste L): Widget mit der Maus an die gewünschte Stelle ziehen,
 * ein-/ausschalten oder die Sitzung zurücksetzen. Die Position wird beim Loslassen gespeichert.
 */
public class JobWidgetEditScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int HINT = 0xFFAAAAAA;
	private static final int OUTLINE = 0xFFFFC040;
	private static final int OUTLINE_HOVER = 0xFFFFFFFF;

	private Button toggleButton;
	private boolean dragging;
	private double grabX;
	private double grabY;

	// Zuletzt gezeichnete Position/Größe – für Klick-Erkennung
	private int wx, wy, ww, wh;

	public JobWidgetEditScreen() {
		super(Component.translatable("loothub.job.editor.title"));
	}

	@Override
	protected void init() {
		Tabs.create(Tabs.Tab.JOBS, 8, 4).forEach(this::addRenderableWidget);

		int bw = 120;
		int gap = 6;
		int total = 3 * bw + 2 * gap;
		int bx = (width - total) / 2;
		int by = height - 28;

		toggleButton = addRenderableWidget(Button.builder(toggleLabel(), b -> {
			WidgetConfig c = WidgetConfig.get();
			c.jobWidgetEnabled = !c.jobWidgetEnabled;
			c.save();
			b.setMessage(toggleLabel());
		}).bounds(bx, by, bw, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.reset"), b -> JobTracker.reset())
				.bounds(bx + bw + gap, by, bw, 20).build());

		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(bx + 2 * (bw + gap), by, bw, 20).build());
	}

	private Component toggleLabel() {
		return Component.translatable(WidgetConfig.get().jobWidgetEnabled
				? "loothub.job.editor.enabled"
				: "loothub.job.editor.disabled");
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);

		Component title = getTitle();
		g.drawString(font, title, (width - font.width(title)) / 2, 28, TEXT, true);
		Component hint = Component.translatable("loothub.job.editor.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, 40, HINT, true);
		if (!JobTracker.hasData()) {
			Component info = Component.translatable("loothub.job.editor.no_data");
			g.drawString(font, info, (width - font.width(info)) / 2, 52, HINT, true);
		}

		JobTracker.Snapshot snap = JobTracker.snapshot();
		if (snap == null) {
			snap = JobTracker.demo();
		}
		JobWidget.Layout layout = JobWidget.layout(font, snap);
		int[] pos = JobWidget.position(width, height, layout);
		wx = pos[0];
		wy = pos[1];
		ww = layout.width();
		wh = layout.height();

		JobWidget.draw(g, font, layout, wx, wy);
		boolean hover = dragging || isOverWidget(mouseX, mouseY);
		int color = hover ? OUTLINE_HOVER : OUTLINE;
		g.fill(wx - 1, wy - 1, wx + ww + 1, wy, color);
		g.fill(wx - 1, wy + wh, wx + ww + 1, wy + wh + 1, color);
		g.fill(wx - 1, wy, wx, wy + wh, color);
		g.fill(wx + ww, wy, wx + ww + 1, wy + wh, color);
	}

	private boolean isOverWidget(double mx, double my) {
		return mx >= wx && mx < wx + ww && my >= wy && my < wy + wh;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == 0 && isOverWidget(event.x(), event.y())) {
			dragging = true;
			grabX = event.x() - wx;
			grabY = event.y() - wy;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			WidgetConfig c = WidgetConfig.get();
			double freeW = Math.max(1, width - ww);
			double freeH = Math.max(1, height - wh);
			c.jobWidgetX = WidgetConfig.clamp01((event.x() - grabX) / freeW);
			c.jobWidgetY = WidgetConfig.clamp01((event.y() - grabY) / freeH);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging) {
			dragging = false;
			WidgetConfig.get().save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public void onClose() {
		WidgetConfig.get().save();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
