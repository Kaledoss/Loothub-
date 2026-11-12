package de.loothub.client.streamer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Offene Streamereignisse (Kanalbelohnungen, Stream-Serien) als Liste im HUD. Bei offenem Chat (T): Einträge per Klick abhaken, die Liste an der
 * Kopfzeile verschieben (außer „Sperren“ ist an). Abhaken ist rein lokal.
 */
public final class RedemptionHud {
	static final int W = 270;
	private static final int ROW = 11;
	private static final int HEAD = 13;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private static boolean dragging;
	private static double grabX, grabY;

	private RedemptionHud() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof LoothubScreen) {
				return;
			}
			ScreenEvents.afterRender(screen).register((s, g, mx, my, d) -> {
				if (isChat(s) && active()) {
					renderEditable(s, g, mx, my);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !isChat(s) || !active() || !click(s, e));
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

	private static boolean isChat(Screen screen) {
		// F1 (HUD aus): auch im Chat nichts zeigen oder verschieben
		return !net.minecraft.client.Minecraft.getInstance().options.hideGui && de.loothub.client.quick.CustomChat.chatOpen(screen);
	}

	/** Streamer-Modus an, HUD-Liste an und mit Twitch verbunden. */
	static boolean active() {
		WidgetConfig c = WidgetConfig.get();
		return c.streamerMode && c.streamerHud && TwitchAuth.connected();
	}

	private static int maxRows() {
		return Math.max(1, Math.min(15, WidgetConfig.get().streamerHudRows));
	}

	private static int height(int rows) {
		return HEAD + Math.max(1, rows) * ROW + 3;
	}

	/** Zeilenhöhe eines Eintrags: mit Nachricht des Zuschauers zwei Zeilen. */
	private static int rowH(Redemptions.Entry e) {
		return e.input == null || e.input.isBlank() ? ROW : 2 * ROW - 1;
	}

	/** Höhe für die ersten {@code rows} Einträge. */
	private static int height(List<Redemptions.Entry> open, int rows) {
		int h = HEAD + 3;
		for (int i = 0; i < rows; i++) {
			h += rowH(open.get(i));
		}
		return rows == 0 ? HEAD + ROW + 3 : h;
	}

	/** Oberkante bleibt fest – gemessen an der vollen Höhe, damit die Liste beim Abhaken nicht springt. */
	static int[] pos(int sw, int sh, int ignored) {
		int h = HEAD + maxRows() * (2 * ROW - 1) + 3; // höchstmögliche Höhe (alle mit Nachricht)
		WidgetConfig c = WidgetConfig.get();
		int x = (int) Math.round(c.streamerHudX * Math.max(0, sw - W - 8)) + 4;
		int y = (int) Math.round(c.streamerHudY * Math.max(0, sh - h - 8)) + 4;
		return new int[]{x, y};
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui || !active() || isChat(mc.screen)) {
			return; // im Chat zeichnet renderEditable
		}
		List<Redemptions.Entry> open = Redemptions.open();
		if (open.isEmpty()) {
			return;
		}
		draw(g, mc.font, open, g.guiWidth(), g.guiHeight(), -1, -1);
	}

	/** Zeichnet die Liste; gibt die Position zurück. */
	private static int[] draw(GuiGraphics g, Font font, List<Redemptions.Entry> open, int sw, int sh, int mx, int my) {
		int rows = Math.min(open.size(), maxRows());
		int h = height(open, rows);
		int[] p = pos(sw, sh, h);
		int x = p[0], y = p[1];
		g.fill(x, y, x + W, y + h, de.loothub.client.Theme.bg(0xC0120A0C));
		g.fill(x, y, x + 1, y + h, 0xFF000000 | StreamerChat.PURPLE);
		String title = "Streamereignisse";
		g.drawString(font, title, x + 6, y + 3, 0xFFC9B3FF, true);
		String count = open.size() + " offen";
		g.drawString(font, count, x + W - 5 - font.width(count), y + 3, 0xFFB9A9AB, true);
		if (open.isEmpty()) {
			g.drawString(font, "Keine offenen Ereignisse", x + 6, y + HEAD + 1, 0xFF9A9A9A, false);
			return p;
		}
		int ry = y + HEAD;
		for (int i = 0; i < rows; i++) {
			Redemptions.Entry e = open.get(i);
			int rh = rowH(e);
			boolean hover = mx >= x && mx < x + W && my >= ry && my < ry + rh;
			if (hover) {
				g.fill(x + 1, ry, x + W, ry + rh, 0x20FFFFFF);
			}
			// Kästchen
			g.fill(x + 5, ry + 1, x + 13, ry + 9, hover ? 0xFFFFFFFF : 0xFF6A5A8A);
			g.fill(x + 6, ry + 2, x + 12, ry + 8, 0xFF0F0A0B);
			String time = TIME.format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()));
			g.drawString(font, time, x + 16, ry + 1, 0xFFB9A9AB, true);
			int tx = x + 18 + font.width(time);
			String text = e.user + ": " + e.reward;
			g.drawString(font, fit(font, text, x + W - 4 - tx), tx, ry + 1, 0xFFFFFFFF, true);
			if (e.input != null && !e.input.isBlank()) {
				g.drawString(font, fit(font, "„" + e.input + "“", x + W - 4 - tx), tx, ry + ROW, 0xFFB9A9AB, true);
			}
			ry += rh;
		}
		return p;
	}

	static String fit(Font font, String s, int max) {
		if (font.width(s) <= max) {
			return s;
		}
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end)) + font.width("…") > max) {
			end--;
		}
		return s.substring(0, end) + "…";
	}

	// ------------------------------------------------------------------ im Chat

	private static void renderEditable(Screen screen, GuiGraphics g, int mx, int my) {
		Font font = Minecraft.getInstance().font;
		List<Redemptions.Entry> open = Redemptions.open();
		int h = height(open, Math.min(open.size(), maxRows()));
		int[] p = draw(g, font, open, screen.width, screen.height, mx, my);
		int x = p[0], y = p[1];
		boolean locked = WidgetConfig.get().streamerHudLocked;
		int lx = x + W - 10;
		int ly = y - 11;
		g.fill(lx, ly, lx + 8, ly + 8, 0xFF4A2A2F);
		g.fill(lx + 1, ly + 1, lx + 7, ly + 7, 0xFF0F0A0B);
		if (locked) {
			g.fill(lx + 2, ly + 2, lx + 6, ly + 6, LoothubScreen.RED);
		}
		Component lock = Component.translatable("loothub.quick.lock");
		g.drawString(font, lock, lx - 3 - font.width(lock), ly, locked ? 0xFFFFFFFF : LoothubScreen.FAINT, true);
		if (!locked) {
			boolean overHead = mx >= x && mx < x + W && my >= y && my < y + HEAD;
			int c = dragging || overHead ? 0xFFFFFFFF : 0x80E23B3B;
			g.fill(x - 1, y - 1, x + W + 1, y, c);
			g.fill(x - 1, y + h, x + W + 1, y + h + 1, c);
			g.fill(x - 1, y, x, y + h, c);
			g.fill(x + W, y, x + W + 1, y + h, c);
		}
	}

	private static boolean click(Screen screen, MouseButtonEvent e) {
		if (e.button() != 0) {
			return false;
		}
		WidgetConfig c = WidgetConfig.get();
		List<Redemptions.Entry> open = Redemptions.open();
		int rows = Math.min(open.size(), maxRows());
		int[] p = pos(screen.width, screen.height, height(open, rows));
		int x = p[0], y = p[1];
		Font font = Minecraft.getInstance().font;
		int lx = x + W - 10;
		int labelW = font.width(Component.translatable("loothub.quick.lock"));
		if (e.y() >= y - 12 && e.y() < y - 2 && e.x() >= lx - labelW - 4 && e.x() < lx + 9) {
			c.streamerHudLocked = !c.streamerHudLocked;
			c.save();
			return true;
		}
		if (e.x() < x || e.x() >= x + W) {
			return false;
		}
		if (e.y() >= y && e.y() < y + HEAD) {
			if (!c.streamerHudLocked) {
				dragging = true;
				grabX = e.x() - x;
				grabY = e.y() - y;
			}
			return true;
		}
		int ry = y + HEAD;
		for (int i = 0; i < rows; i++) {
			int rh = rowH(open.get(i));
			if (e.y() >= ry && e.y() < ry + rh) {
				Redemptions.toggle(open.get(i));
				return true;
			}
			ry += rh;
		}
		return false;
	}

	private static boolean drag(Screen screen, MouseButtonEvent e) {
		if (!dragging) {
			return false;
		}
		WidgetConfig c = WidgetConfig.get();
		int h = HEAD + maxRows() * (2 * ROW - 1) + 3;
		c.streamerHudX = Math.max(0, Math.min(1, (e.x() - grabX - 4) / Math.max(1, screen.width - W - 8)));
		c.streamerHudY = Math.max(0, Math.min(1, (e.y() - grabY - 4) / Math.max(1, screen.height - h - 8)));
		return true;
	}
}
