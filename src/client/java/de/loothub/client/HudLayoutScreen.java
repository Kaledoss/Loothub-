package de.loothub.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import de.loothub.client.jobs.WidgetConfig;

/**
 * „HUD-Positionen“: alle Loothub-Anzeigen als Kästen auf einem Bildschirm. Ziehen mit der Maus, oder einen Kasten
 * anklicken und mit den Pfeiltasten verschieben (Umschalt = große Schritte). Ersatz, falls das Ziehen im Chat
 * einmal nicht klappt.
 */
public class HudLayoutScreen extends Screen {
	/** Eine Anzeige: Größe (ungefähr), Lage als Anteil 0..1, Rand und ob die Mitte (statt links) dem Anteil folgt. */
	private record Hud(String key, Supplier<int[]> size, DoubleSupplier gx, DoubleSupplier gy, DoubleConsumer sx,
			DoubleConsumer sy, int margin, boolean centered, double defX, double defY, BooleanSupplier enabled) {
	}

	private final Screen parent;
	private final List<Hud> huds = new ArrayList<>();
	private Hud selected;
	private Hud dragging;
	private double grabX, grabY;

	public HudLayoutScreen(Screen parent) {
		super(Component.translatable("loothub.hud_layout.title"));
		this.parent = parent;
		WidgetConfig c = WidgetConfig.get();
		huds.add(new Hud("loothub.hud.job", () -> {
			double s = de.loothub.client.jobs.JobWidget.scale();
			return new int[]{(int) (150 * s), (int) (56 * s)};
		}, () -> c.jobWidgetX, () -> c.jobWidgetY, v -> c.jobWidgetX = v, v -> c.jobWidgetY = v, 0, false, 0.0, 0.35,
				() -> c.jobWidgetEnabled));
		huds.add(new Hud("loothub.hud.timers", () -> new int[]{110, 24}, () -> c.timerHudX, () -> c.timerHudY,
				v -> c.timerHudX = v, v -> c.timerHudY = v, 0, false, 0.99, 0.35, () -> c.timersEnabled));
		huds.add(new Hud("loothub.hud.look", () -> {
			float s = Math.max(0.5F, Math.min(2.0F, c.lookInfoScale));
			return new int[]{Math.round(150 * s), Math.round(26 * s)};
		}, () -> c.lookInfoX, () -> c.lookInfoY, v -> c.lookInfoX = v, v -> c.lookInfoY = v, 4, true, 0.5, 0.0,
				() -> c.lookInfo));
		huds.add(new Hud("loothub.hud.inventory", () -> new int[]{de.loothub.client.quick.InventoryHud.W,
				de.loothub.client.quick.InventoryHud.H}, () -> c.invHudX, () -> c.invHudY, v -> c.invHudX = v,
				v -> c.invHudY = v, 0, false, -1, -1, () -> c.invHudEnabled));
		huds.add(new Hud("loothub.hud.music", () -> new int[]{170, 34}, () -> c.musicX, () -> c.musicY,
				v -> c.musicX = v, v -> c.musicY = v, 4, false, 0.0, 1.0, () -> c.musicEnabled));
		huds.add(new Hud("loothub.hud.pcg", () -> new int[]{150, 32}, () -> c.pcgOverlayX, () -> c.pcgOverlayY,
				v -> c.pcgOverlayX = v, v -> c.pcgOverlayY = v, 4, false, 1.0, 0.0, () -> c.pcgEnabled));
		huds.add(new Hud("loothub.hud.stream", () -> new int[]{270, 13 + Math.max(1, Math.min(15, c.streamerHudRows)) * 21 + 3},
				() -> c.streamerHudX, () -> c.streamerHudY, v -> c.streamerHudX = v, v -> c.streamerHudY = v, 4, false,
				1.0, 0.5, () -> c.streamerMode && c.streamerHud));
		huds.add(new Hud("loothub.hud.stream_alerts", () -> de.loothub.client.streamer.Alerts.sampleSize(font),
				() -> c.streamAlertX, () -> c.streamAlertY, v -> c.streamAlertX = v, v -> c.streamAlertY = v, 0, false,
				0.5, 0.03, () -> c.streamerMode && c.streamerAlerts));
		huds.add(new Hud("loothub.hud.fish", () -> de.loothub.client.jobs.FishAlert.sampleSize(font),
				() -> c.fishAlertX, () -> c.fishAlertY, v -> c.fishAlertX = v, v -> c.fishAlertY = v, 0, false, 0.5, 0.2,
				() -> c.fishAlert));
	}

	/** Mit einer bestimmten Anzeige vorausgewählt öffnen (z. B. „Fang-Einblendung verschieben“). */
	public static HudLayoutScreen select(Screen parent, String key) {
		HudLayoutScreen s = new HudLayoutScreen(parent);
		for (Hud h : s.huds) {
			if (h.key().equals(key)) {
				s.selected = h;
			}
		}
		return s;
	}

	@Override
	protected void init() {
		int bw = 90;
		int y = height - 24;
		addRenderableWidget(Button.builder(Component.translatable("loothub.hud_layout.reset"), b -> {
			if (selected != null) {
				selected.sx().accept(selected.defX());
				selected.sy().accept(selected.defY());
				WidgetConfig.get().save();
			}
		}).bounds(width / 2 - bw - 4, y, bw, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 + 4, y, bw, 18).build());
	}

	// ------------------------------------------------------------------ Lage

	/** Linke obere Ecke und Größe auf dem Bildschirm. */
	private int[] rect(Hud h) {
		int[] size = h.size().get();
		int w = size[0], hh = size[1];
		double fx = h.gx().getAsDouble(), fy = h.gy().getAsDouble();
		if (fx < 0 || fy < 0) {
			// Inventar-Anzeige ohne eigene Lage: mittig über der Hotbar
			return new int[]{(width - w) / 2, height - 22 - 39 - hh - 2, w, hh};
		}
		int m = h.margin();
		int x = h.centered() ? (int) Math.round(fx * width - w / 2.0)
				: (int) Math.round(m + fx * Math.max(0, width - w - 2 * m));
		int y = (int) Math.round(m + fy * Math.max(0, height - hh - 2 * m));
		x = Mth.clamp(x, 0, Math.max(0, width - w));
		y = Mth.clamp(y, 0, Math.max(0, height - hh));
		return new int[]{x, y, w, hh};
	}

	/** Linke obere Ecke setzen (rechnet in den Anteil der Einstellung um). */
	private void moveTo(Hud h, double x, double y) {
		int[] size = h.size().get();
		int w = size[0], hh = size[1];
		int m = h.margin();
		double fx = h.centered() ? (x + w / 2.0) / Math.max(1, width) : (x - m) / Math.max(1, width - w - 2 * m);
		double fy = (y - m) / Math.max(1, height - hh - 2 * m);
		h.sx().accept(Mth.clamp(fx, 0, 1));
		h.sy().accept(Mth.clamp(fy, 0, 1));
	}

	// ------------------------------------------------------------------ Zeichnen

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		Component hint = Component.translatable("loothub.hud_layout.hint");
		g.drawCenteredString(font, hint, width / 2, 8, 0xFFE0E0E0);
		for (Hud h : huds) {
			int[] r = rect(h);
			boolean on = h.enabled().getAsBoolean();
			boolean sel = h == selected;
			boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3];
			g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], on ? 0x90301A1E : 0x50202020);
			int edge = sel ? 0xFFFFFFFF : hover ? 0xFFE0A040 : on ? 0xFFE23B3B : 0xFF606060;
			g.fill(r[0], r[1], r[0] + r[2], r[1] + 1, edge);
			g.fill(r[0], r[1] + r[3] - 1, r[0] + r[2], r[1] + r[3], edge);
			g.fill(r[0], r[1], r[0] + 1, r[1] + r[3], edge);
			g.fill(r[0] + r[2] - 1, r[1], r[0] + r[2], r[1] + r[3], edge);
			Component name = Component.translatable(h.key());
			if (!on) {
				name = name.copy().append(Component.translatable("loothub.hud_layout.off"));
			}
			g.drawString(font, name, r[0] + 4, r[1] + 4, on ? 0xFFFFFFFF : 0xFF9A9A9A, true);
		}
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// Welt sichtbar lassen, nur leicht abdunkeln
		g.fill(0, 0, width, height, 0x40000000);
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		if (e.button() != 0) {
			return false;
		}
		for (int i = huds.size() - 1; i >= 0; i--) {
			Hud h = huds.get(i);
			int[] r = rect(h);
			if (e.x() >= r[0] && e.x() < r[0] + r[2] && e.y() >= r[1] && e.y() < r[1] + r[3]) {
				selected = h;
				dragging = h;
				grabX = e.x() - r[0];
				grabY = e.y() - r[1];
				return true;
			}
		}
		selected = null;
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging != null) {
			moveTo(dragging, e.x() - grabX, e.y() - grabY);
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging != null) {
			dragging = null;
			WidgetConfig.get().save();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (selected != null) {
			int step = minecraft.hasShiftDown() ? 10 : 1;
			int dx = 0, dy = 0;
			switch (e.key()) {
				case GLFW.GLFW_KEY_LEFT -> dx = -step;
				case GLFW.GLFW_KEY_RIGHT -> dx = step;
				case GLFW.GLFW_KEY_UP -> dy = -step;
				case GLFW.GLFW_KEY_DOWN -> dy = step;
				default -> {
					return super.keyPressed(e);
				}
			}
			int[] r = rect(selected);
			moveTo(selected, r[0] + dx, r[1] + dy);
			WidgetConfig.get().save();
			return true;
		}
		return super.keyPressed(e);
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
