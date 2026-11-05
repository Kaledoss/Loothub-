package de.loothub.client;

import java.util.Locale;
import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Farbwähler: Feld für Sättigung/Helligkeit, Farbton-Leiste, Hex-Eingabe und Vorschläge.
 * „Übernehmen“ gibt die Farbe (RGB) zurück, „Abbrechen“/Esc verwirft sie.
 */
public class ColorPickerScreen extends Screen {
	private static final int SV = 120;
	private static final int CELL = 3;
	private static final int HUE_W = 14;

	private final Screen parent;
	private final IntConsumer onDone;
	private final int original;
	private float h, s, v;
	private int x0, y0;
	private int dragging; // 0 nichts, 1 Feld, 2 Farbton
	private EditBox hexBox;
	private boolean updatingHex;
	/** Optionale Live-Vorschau (z. B. Flügel in der gewählten Farbe), links neben dem Farbfeld. */
	private Preview preview;

	public interface Preview {
		void draw(GuiGraphics g, int x, int y, int w, int h, int rgb);
	}

	public ColorPickerScreen withPreview(Preview preview) {
		this.preview = preview;
		return this;
	}

	public ColorPickerScreen(Screen parent, Component title, int rgb, IntConsumer onDone) {
		super(title);
		this.parent = parent;
		this.onDone = onDone;
		this.original = rgb & 0xFFFFFF;
		float[] hsv = Theme.rgbToHsv(original);
		h = hsv[0];
		s = hsv[1];
		v = hsv[2];
	}

	private int color() {
		return Theme.hsvToRgb(h, s, v);
	}

	@Override
	protected void init() {
		int totalW = SV + 8 + HUE_W + 12 + 120;
		x0 = (width - totalW) / 2;
		y0 = Math.max(30, (height - SV) / 2 - 10);
		int rx = x0 + SV + 8 + HUE_W + 12;
		hexBox = new EditBox(font, rx, y0 + 50, 80, 16, Component.literal("Hex"));
		hexBox.setMaxLength(7);
		hexBox.setValue(hex(color()));
		hexBox.setResponder(t -> {
			if (updatingHex) {
				return;
			}
			String c = t.strip().replace("#", "");
			if (c.matches("[0-9a-fA-F]{6}")) {
				float[] hsv = Theme.rgbToHsv(Integer.parseInt(c, 16));
				h = hsv[0];
				s = hsv[1];
				v = hsv[2];
			}
		});
		addRenderableWidget(hexBox);
		// Vorschläge
		for (int i = 0; i < Theme.PRESETS.length; i++) {
			int preset = Theme.PRESETS[i];
			int bx = rx + (i % 4) * 22;
			int by = y0 + 74 + (i / 4) * 22;
			addRenderableWidget(Button.builder(Component.empty(), b -> setColor(preset)).bounds(bx, by, 18, 18).build());
		}
		int by = y0 + SV + 14;
		addRenderableWidget(Button.builder(Component.translatable("loothub.color.apply"), b -> {
			onDone.accept(color());
			minecraft.setScreen(parent);
		}).bounds(width / 2 - 104, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.color.cancel"), b -> onClose())
				.bounds(width / 2 + 4, by, 100, 20).build());
	}

	private void setColor(int rgb) {
		float[] hsv = Theme.rgbToHsv(rgb);
		h = hsv[0];
		s = hsv[1];
		v = hsv[2];
		syncHex();
	}

	private void syncHex() {
		updatingHex = true;
		hexBox.setValue(hex(color()));
		updatingHex = false;
	}

	private static String hex(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawString(font, getTitle(), (width - font.width(getTitle())) / 2, y0 - 18, 0xFFFFFFFF, true);

		// Sättigung (x) / Helligkeit (y)
		for (int yy = 0; yy < SV; yy += CELL) {
			for (int xx = 0; xx < SV; xx += CELL) {
				int c = Theme.hsvToRgb(h, xx / (float) (SV - 1), 1 - yy / (float) (SV - 1));
				g.fill(x0 + xx, y0 + yy, x0 + xx + CELL, y0 + yy + CELL, 0xFF000000 | c);
			}
		}
		int cx = x0 + Math.round(s * (SV - 1));
		int cy = y0 + Math.round((1 - v) * (SV - 1));
		g.fill(cx - 3, cy, cx + 4, cy + 1, 0xFFFFFFFF);
		g.fill(cx, cy - 3, cx + 1, cy + 4, 0xFFFFFFFF);

		// Farbton
		int hx = x0 + SV + 8;
		for (int yy = 0; yy < SV; yy += 2) {
			g.fill(hx, y0 + yy, hx + HUE_W, y0 + yy + 2, 0xFF000000 | Theme.hsvToRgb(yy / (float) SV, 1, 1));
		}
		int hy = y0 + Math.round(h * (SV - 1));
		g.fill(hx - 2, hy, hx + HUE_W + 2, hy + 1, 0xFFFFFFFF);

		// Vorschau alt/neu
		int rx = hx + HUE_W + 12;
		g.drawString(font, Component.translatable("loothub.color.preview"), rx, y0, 0xFFB9A9AB, false);
		g.fill(rx, y0 + 12, rx + 40, y0 + 40, 0xFF000000 | original);
		g.fill(rx + 40, y0 + 12, rx + 80, y0 + 40, 0xFF000000 | color());
		if (preview != null) {
			int pw = 120;
			int px = x0 - pw - 12;
			if (px >= 4) {
				g.fill(px, y0, px + pw, y0 + SV, 0xC0140B0D);
				preview.draw(g, px, y0, pw, SV, color());
			}
		}
		// Vorschlags-Felder einfärben
		for (int i = 0; i < Theme.PRESETS.length; i++) {
			int bx = rx + (i % 4) * 22;
			int by = y0 + 74 + (i / 4) * 22;
			g.fill(bx + 3, by + 3, bx + 15, by + 15, 0xFF000000 | Theme.PRESETS[i]);
		}
	}

	private void pick(double mx, double my) {
		if (dragging == 1) {
			s = (float) Math.max(0, Math.min(1, (mx - x0) / (SV - 1)));
			v = (float) Math.max(0, Math.min(1, 1 - (my - y0) / (SV - 1)));
		} else if (dragging == 2) {
			h = (float) Math.max(0, Math.min(0.999, (my - y0) / (SV - 1)));
		}
		syncHex();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		if (e.button() == 0 && e.y() >= y0 && e.y() < y0 + SV) {
			if (e.x() >= x0 && e.x() < x0 + SV) {
				dragging = 1;
			} else if (e.x() >= x0 + SV + 6 && e.x() < x0 + SV + 8 + HUE_W + 2) {
				dragging = 2;
			}
			if (dragging != 0) {
				pick(e.x(), e.y());
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging != 0) {
			pick(e.x(), e.y());
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging != 0) {
			dragging = 0;
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
