package de.loothub.client.jobs;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Job-Widget verschieben und in der Größe ändern (aus den Einstellungen /loothub → „Position ändern“):
 * Widget mit der Maus ziehen verschiebt es, an Rändern oder Ecken ziehen ändert die Größe (Seitenverhältnis
 * bleibt), Rechtsklick setzt die Größe auf 100 % zurück. Gespeichert wird beim Loslassen.
 */
public class JobWidgetEditScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int HINT = 0xFFAAAAAA;
	private static final int OUTLINE = 0xFFE23B3B;
	private static final int OUTLINE_HOVER = 0xFFFFFFFF;

	private final Screen parent;
	private static final int EDGE = 4; // Greifbreite der Ränder

	private boolean dragging;
	private double grabX;
	private double grabY;

	// Größe ändern: welche Ränder gegriffen wurden, Startwerte
	private boolean resizing;
	private boolean edgeL, edgeR, edgeT, edgeB;
	private int startX, startY, startW, startH;
	private int baseW, baseH; // ungeskalierte Größe

	// Zuletzt gezeichnete Position/Größe – für Klick-Erkennung
	private int wx, wy, ww, wh;

	public JobWidgetEditScreen(Screen parent) {
		super(Component.translatable("loothub.job.editor.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds((width - 120) / 2, height - 28, 120, 20).build());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);

		Component title = getTitle();
		g.drawString(font, title, (width - font.width(title)) / 2, 12, TEXT, true);
		Component hint = Component.translatable("loothub.job.editor.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, 24, HINT, true);
		if (!JobTracker.hasData()) {
			Component info = Component.translatable("loothub.job.editor.no_data");
			g.drawString(font, info, (width - font.width(info)) / 2, 36, HINT, true);
		}

		JobTracker.Snapshot snap = JobTracker.snapshot();
		if (snap == null) {
			snap = JobTracker.demo();
		}
		JobWidget.Layout layout = JobWidget.layout(font, snap);
		int[] pos = JobWidget.position(width, height, layout);
		wx = pos[0];
		wy = pos[1];
		baseW = layout.width();
		baseH = layout.height();
		ww = JobWidget.scaledWidth(layout);
		wh = JobWidget.scaledHeight(layout);

		JobWidget.drawScaled(g, font, layout, wx, wy, JobWidget.scale());
		boolean[] edges = edgesAt(mouseX, mouseY);
		boolean onEdge = resizing || edges[0] || edges[1] || edges[2] || edges[3];
		boolean hover = dragging || onEdge || isOverWidget(mouseX, mouseY);
		int color = hover ? OUTLINE_HOVER : OUTLINE;
		g.fill(wx - 1, wy - 1, wx + ww + 1, wy, color);
		g.fill(wx - 1, wy + wh, wx + ww + 1, wy + wh + 1, color);
		g.fill(wx - 1, wy, wx, wy + wh, color);
		g.fill(wx + ww, wy, wx + ww + 1, wy + wh, color);
		// Griffe an den Ecken
		int hc = onEdge ? OUTLINE_HOVER : OUTLINE;
		for (int[] c : new int[][]{{wx, wy}, {wx + ww, wy}, {wx, wy + wh}, {wx + ww, wy + wh}}) {
			g.fill(c[0] - 2, c[1] - 2, c[0] + 3, c[1] + 3, hc);
		}
		if (onEdge || resizing) {
			String size = Math.round(JobWidget.scale() * 100) + " %";
			int ty = wy - 12 >= 0 ? wy - 12 : wy + wh + 4;
			g.drawString(font, size, wx + (ww - font.width(size)) / 2, ty, TEXT, true);
		}
	}

	private boolean isOverWidget(double mx, double my) {
		return mx >= wx && mx < wx + ww && my >= wy && my < wy + wh;
	}

	/** Welche Ränder liegen unter der Maus? [links, rechts, oben, unten] */
	private boolean[] edgesAt(double mx, double my) {
		boolean inX = mx >= wx - EDGE && mx <= wx + ww + EDGE;
		boolean inY = my >= wy - EDGE && my <= wy + wh + EDGE;
		if (!inX || !inY) {
			return new boolean[4];
		}
		return new boolean[]{Math.abs(mx - wx) <= EDGE, Math.abs(mx - (wx + ww)) <= EDGE,
				Math.abs(my - wy) <= EDGE, Math.abs(my - (wy + wh)) <= EDGE};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == 1 && isOverWidget(event.x(), event.y())) {
			setScaleKeepingCorner(1.0, false, false, wx, wy, wx + ww, wy + wh);
			WidgetConfig.get().save();
			return true;
		}
		boolean[] e = edgesAt(event.x(), event.y());
		if (event.button() == 0 && (e[0] || e[1] || e[2] || e[3])) {
			resizing = true;
			edgeL = e[0];
			edgeR = e[1] && !e[0];
			edgeT = e[2];
			edgeB = e[3] && !e[2];
			startX = wx;
			startY = wy;
			startW = ww;
			startH = wh;
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
		if (resizing && baseW > 0 && baseH > 0) {
			double mx = event.x();
			double my = event.y();
			double s = 0;
			if (edgeR) {
				s = Math.max(s, (mx - startX) / baseW);
			}
			if (edgeL) {
				s = Math.max(s, (startX + startW - mx) / baseW);
			}
			if (edgeB) {
				s = Math.max(s, (my - startY) / baseH);
			}
			if (edgeT) {
				s = Math.max(s, (startY + startH - my) / baseH);
			}
			s = Math.round(s * 20) / 20.0; // 5-%-Schritte
			setScaleKeepingCorner(s, edgeL, edgeT, startX, startY, startX + startW, startY + startH);
			return true;
		}
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
		if (dragging || resizing) {
			dragging = false;
			resizing = false;
			WidgetConfig.get().save();
			return true;
		}
		return super.mouseReleased(event);
	}

	/** Größe setzen; die dem gegriffenen Rand gegenüberliegende Seite bleibt stehen. */
	private void setScaleKeepingCorner(double scale, boolean fromLeft, boolean fromTop, int x0, int y0, int x1, int y1) {
		WidgetConfig c = WidgetConfig.get();
		c.jobWidgetScale = JobWidget.clampScale(scale);
		int nw = (int) Math.ceil(baseW * c.jobWidgetScale);
		int nh = (int) Math.ceil(baseH * c.jobWidgetScale);
		int nx = fromLeft ? x1 - nw : x0;
		int ny = fromTop ? y1 - nh : y0;
		c.jobWidgetX = WidgetConfig.clamp01(nx / (double) Math.max(1, width - nw));
		c.jobWidgetY = WidgetConfig.clamp01(ny / (double) Math.max(1, height - nh));
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
