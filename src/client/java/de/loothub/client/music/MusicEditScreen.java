package de.loothub.client.music;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import de.loothub.client.jobs.WidgetConfig;

/** Musik-Anzeige verschieben (aus /loothub): mit der Maus ziehen, „Fertig“ oder Esc führt zurück. */
public class MusicEditScreen extends Screen {
	private final Screen parent;
	private boolean dragging;
	private double grabX, grabY;

	public MusicEditScreen(Screen parent) {
		super(Component.translatable("loothub.music.editor.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds((width - 120) / 2, height - 28, 120, 20).build());
	}

	private static YtMusic.Song sample() {
		YtMusic.Song s = YtMusic.song();
		if (s == null) {
			s = WindowsMedia.song();
		}
		return s != null ? s : new YtMusic.Song("Beispieltitel", "Interpret", false, 83, 225, System.currentTimeMillis());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawString(font, getTitle(), (width - font.width(getTitle())) / 2, 12, 0xFFFFFFFF, true);
		Component hint = Component.translatable("loothub.job.editor.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, 24, 0xFFAAAAAA, true);
		int[] p = MusicWidget.pos(width, height);
		MusicWidget.draw(g, font, sample(), p[0], p[1]);
		int c = dragging || over(mouseX, mouseY) ? 0xFFFFFFFF : 0xFFE23B3B;
		int l = MusicWidget.left(font, sample(), p[0]);
		g.fill(l - 1, p[1] - 1, p[0] + MusicWidget.W + 1, p[1], c);
		g.fill(l - 1, p[1] + MusicWidget.H, p[0] + MusicWidget.W + 1, p[1] + MusicWidget.H + 1, c);
		g.fill(l - 1, p[1], l, p[1] + MusicWidget.H, c);
		g.fill(p[0] + MusicWidget.W, p[1], p[0] + MusicWidget.W + 1, p[1] + MusicWidget.H, c);
	}

	private boolean over(double mx, double my) {
		int[] p = MusicWidget.pos(width, height);
		int l = MusicWidget.left(font, sample(), p[0]);
		return mx >= l && mx < p[0] + MusicWidget.W && my >= p[1] && my < p[1] + MusicWidget.H;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		if (e.button() == 0 && over(e.x(), e.y())) {
			int[] p = MusicWidget.pos(width, height);
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
			c.musicX = Math.max(0, Math.min(1, (e.x() - grabX - 4) / Math.max(1, width - MusicWidget.W - 8)));
			c.musicY = Math.max(0, Math.min(1, (e.y() - grabY - 4) / Math.max(1, height - MusicWidget.H - 8)));
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
