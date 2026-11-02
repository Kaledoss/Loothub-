package de.loothub.client.pcg;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import de.loothub.client.jobs.WidgetConfig;

/** Pokémon-Anzeige verschieben (aus /loothub): mit der Maus ziehen, „Fertig“ oder Esc führt zurück. */
public class PcgOverlayEditScreen extends Screen {
	private final Screen parent;
	private boolean dragging;
	private double grabX, grabY;
	private int wx, wy, ww, wh;

	public PcgOverlayEditScreen(Screen parent) {
		super(Component.translatable("loothub.pcg.editor.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds((width - 120) / 2, height - 28, 120, 20).build());
	}

	private static PcgState.Spawn sample() {
		PcgState.Spawn s = PcgState.current();
		if (s != null) {
			return s;
		}
		long now = System.currentTimeMillis();
		return new PcgState.Spawn("Beispiel", now - 30_000, now + 60_000, null, 0);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		Component title = getTitle();
		g.drawString(font, title, (width - font.width(title)) / 2, 12, 0xFFFFFFFF, true);
		Component hint = Component.translatable("loothub.job.editor.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, 24, 0xFFAAAAAA, true);

		PcgState.Spawn s = sample();
		int[] size = PcgOverlay.size(font, s);
		ww = size[0];
		wh = size[1];
		int[] pos = PcgOverlay.position(width, height, ww, wh);
		wx = pos[0];
		wy = pos[1];
		PcgOverlay.draw(g, font, s, wx, wy);
		int color = dragging || over(mouseX, mouseY) ? 0xFFFFFFFF : 0xFFE23B3B;
		g.fill(wx - 1, wy - 1, wx + ww + 1, wy, color);
		g.fill(wx - 1, wy + wh, wx + ww + 1, wy + wh + 1, color);
		g.fill(wx - 1, wy, wx, wy + wh, color);
		g.fill(wx + ww, wy, wx + ww + 1, wy + wh, color);
	}

	private boolean over(double mx, double my) {
		return mx >= wx && mx < wx + ww && my >= wy && my < wy + wh;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == 0 && over(event.x(), event.y())) {
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
			double freeW = Math.max(1, width - ww - 8);
			double freeH = Math.max(1, height - wh - 8);
			c.pcgOverlayX = Math.max(0, Math.min(1, (event.x() - grabX - 4) / freeW));
			c.pcgOverlayY = Math.max(0, Math.min(1, (event.y() - grabY - 4) / freeH));
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
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
