package de.loothub.client.music;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * „Läuft gerade“ aus YouTube Music im HUD. Verschieben: Chat öffnen (T) und das Feld ziehen –
 * außer es ist gesperrt (Häkchen „Sperren“ am Feld, solange der Chat offen ist).
 */
public final class MusicWidget {
	private static final int W = 170;
	private static final int H = 34;
	private static final int BG = 0xC0120A0C;
	private static final int EDGE = 0xE0000000;
	private static final int RED = 0xFFB3262E;

	private static boolean dragging;
	private static double grabX, grabY;

	private MusicWidget() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ChatScreen)) {
				return;
			}
			ScreenEvents.afterRender(screen).register((s, g, mx, my, d) -> renderEditable(s, g, mx, my));
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !click(s, e));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !drag(s, e));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> {
				if (dragging) {
					dragging = false;
					WidgetConfig.get().save();
					return false;
				}
				return true;
			});
		});
	}

	private static YtMusic.Song shownSong() {
		WidgetConfig c = WidgetConfig.get();
		if (!c.musicEnabled) {
			return null;
		}
		YtMusic.Song s = YtMusic.song();
		return s != null ? s : WindowsMedia.song(); // Desktop-App bevorzugt, sonst Windows-Medienanzeige
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || mc.screen instanceof ChatScreen) {
			return; // im Chat zeichnet renderEditable (mit Rahmen zum Verschieben)
		}
		YtMusic.Song s = shownSong();
		if (s == null) {
			return;
		}
		int[] p = pos(g.guiWidth(), g.guiHeight());
		draw(g, mc.font, s, p[0], p[1]);
	}

	private static int[] pos(int sw, int sh) {
		WidgetConfig c = WidgetConfig.get();
		int x = (int) Math.round(c.musicX * Math.max(0, sw - W - 8)) + 4;
		int y = (int) Math.round(c.musicY * Math.max(0, sh - H - 8)) + 4;
		return new int[]{x, y};
	}

	private static String fit(Font font, String s, int max) {
		if (font.width(s) <= max) {
			return s;
		}
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end)) + font.width("…") > max) {
			end--;
		}
		return s.substring(0, end) + "…";
	}

	static void draw(GuiGraphics g, Font font, YtMusic.Song s, int x, int y) {
		g.fill(x + 1, y, x + W - 1, y + H, de.loothub.client.Theme.bg(BG));
		g.fill(x, y + 1, x + 1, y + H - 1, de.loothub.client.Theme.bg(BG));
		g.fill(x + W - 1, y + 1, x + W, y + H - 1, de.loothub.client.Theme.bg(BG));
		g.fill(x + 2, y, x + W - 2, y + 1, de.loothub.client.Theme.bg(EDGE));
		g.fill(x + 2, y + H - 1, x + W - 2, y + H, de.loothub.client.Theme.bg(EDGE));
		g.fill(x + 4, y + 2, x + W - 4, y + 3, de.loothub.client.LoothubScreen.RED);

		String icon = s.paused() ? "⏸" : "♪";
		g.drawString(font, icon, x + 6, y + 7, s.paused() ? 0xFF9A9A9A : 0xFFE07A7A, true);
		g.drawString(font, fit(font, s.title(), W - 24), x + 17, y + 7, 0xFFFFD66B, true);
		g.drawString(font, fit(font, s.artist(), W - 24), x + 17, y + 17, 0xFFB9A9AB, true);

		// Fortschritt (bei Quellen ohne Länge weglassen)
		if (s.duration() <= 0) {
			return;
		}
		double dur = Math.max(1, s.duration());
		double pos = s.position();
		int bx = x + 6;
		int bw = W - 12;
		int filled = (int) Math.round(bw * Math.min(1, pos / dur));
		g.fill(bx, y + H - 5, bx + bw, y + H - 4, 0x40FFFFFF);
		g.fill(bx, y + H - 5, bx + filled, y + H - 4, 0xFFE23B3B);
		String time = Fmt.clock((long) (pos * 1000)) + " / " + Fmt.clock((long) (dur * 1000));
		g.drawString(font, time, x + W - 6 - font.width(time), y + 17, 0xFF7A6A6C, false);
	}

	// ------------------------------------------------------------------ Verschieben im Chat

	private static int lockX(int x) {
		return x + W - 10;
	}

	private static void renderEditable(Screen screen, GuiGraphics g, int mx, int my) {
		YtMusic.Song s = shownSong();
		if (s == null) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int[] p = pos(screen.width, screen.height);
		int x = p[0], y = p[1];
		draw(g, font, s, x, y);
		boolean locked = WidgetConfig.get().musicLocked;
		// Sperren-Häkchen über dem Feld
		int lx = lockX(x);
		int ly = y - 11;
		g.fill(lx, ly, lx + 8, ly + 8, 0xFF4A2A2F);
		g.fill(lx + 1, ly + 1, lx + 7, ly + 7, 0xFF0F0A0B);
		if (locked) {
			g.fill(lx + 2, ly + 2, lx + 6, ly + 6, LoothubScreen.RED);
		}
		Component lock = Component.translatable("loothub.quick.lock");
		g.drawString(font, lock, lx - 3 - font.width(lock), ly, locked ? 0xFFFFFFFF : LoothubScreen.FAINT, true);
		if (!locked) {
			int c = dragging || (mx >= x && mx < x + W && my >= y && my < y + H) ? 0xFFFFFFFF : 0x80E23B3B;
			g.fill(x - 1, y - 1, x + W + 1, y, c);
			g.fill(x - 1, y + H, x + W + 1, y + H + 1, c);
			g.fill(x - 1, y, x, y + H, c);
			g.fill(x + W, y, x + W + 1, y + H, c);
		}
	}

	private static boolean click(Screen screen, MouseButtonEvent e) {
		if (e.button() != 0 || shownSong() == null) {
			return false;
		}
		WidgetConfig c = WidgetConfig.get();
		int[] p = pos(screen.width, screen.height);
		int lx = lockX(p[0]);
		Font font = Minecraft.getInstance().font;
		int labelW = font.width(Component.translatable("loothub.quick.lock"));
		if (e.y() >= p[1] - 12 && e.y() < p[1] - 2 && e.x() >= lx - labelW - 4 && e.x() < lx + 9) {
			c.musicLocked = !c.musicLocked;
			c.save();
			return true;
		}
		if (!c.musicLocked && e.x() >= p[0] && e.x() < p[0] + W && e.y() >= p[1] && e.y() < p[1] + H) {
			dragging = true;
			grabX = e.x() - p[0];
			grabY = e.y() - p[1];
			return true;
		}
		return false;
	}

	private static boolean drag(Screen screen, MouseButtonEvent e) {
		if (!dragging) {
			return false;
		}
		WidgetConfig c = WidgetConfig.get();
		c.musicX = Math.max(0, Math.min(1, (e.x() - grabX - 4) / Math.max(1, screen.width - W - 8)));
		c.musicY = Math.max(0, Math.min(1, (e.y() - grabY - 4) / Math.max(1, screen.height - H - 8)));
		return true;
	}
}
