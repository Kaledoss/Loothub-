package de.loothub.client.quick;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Eigene Timer: Countdown in Minuten (einmalig oder wiederholend) oder täglich zu einer Uhrzeit (z. B. Tageswechsel
 * 00:00). Läuft einer ab, gibt es einen Hinweis im Chat und einen Ton. Die laufenden Timer stehen im HUD.
 * Reine Erinnerung – es wird nichts im Spiel ausgelöst.
 */
public final class Timers {
	/** Ein Timer (wird in der Konfiguration gespeichert). */
	public static final class Timer {
		public String name = "Timer";
		/** "interval" oder "daily". */
		public String mode = "interval";
		public int minutes = 30;
		/** Uhrzeit für "daily" als „HH:mm“. */
		public String time = "00:00";
		public boolean repeat = false;
		public boolean enabled = true;
		/** Nächster Ablauf (Epoch-ms), 0 = nicht gestartet. */
		public long nextAt = 0;
		/** In der Anzeige (HUD) eingeblendet? Höchstens {@link #MAX_SHOWN} gleichzeitig. */
		public boolean shown = true;
		/** Gemerkte Auktion (Schlüssel aus {@link AuctionWatch}), sonst null. Läuft von selbst mit der Merkliste. */
		public String auction;
		/** Auktion: nur grob bekannt (der Server nannte Stunden oder Tage) – Ende frühestens dann. */
		public boolean approx;
		public String seller = "";
		public String price = "";

		public boolean daily() {
			return "daily".equals(mode);
		}

		public boolean isAuction() {
			return auction != null;
		}
	}

	/** So viele Timer dürfen gleichzeitig in der Anzeige stehen. */
	public static final int MAX_SHOWN = 5;

	public static int shownCount() {
		int n = 0;
		for (Timer t : list()) {
			if (t.shown) {
				n++;
			}
		}
		return n;
	}

	/** Einblenden nur, solange noch Platz ist. @return ob der Timer jetzt eingeblendet ist */
	public static boolean setShown(Timer t, boolean on) {
		if (on && !t.shown && shownCount() >= MAX_SHOWN) {
			return false;
		}
		t.shown = on;
		return t.shown;
	}

	private static long lastCheck;

	private Timers() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
	}

	public static List<Timer> list() {
		WidgetConfig c = WidgetConfig.get();
		if (c.timers == null) {
			c.timers = new ArrayList<>();
		}
		return c.timers;
	}

	/** Startet bzw. setzt einen Timer neu. */
	public static void start(Timer t) {
		t.enabled = true;
		t.nextAt = next(t, System.currentTimeMillis());
	}

	static long next(Timer t, long now) {
		if (t.daily()) {
			LocalTime at = parseTime(t.time);
			ZoneId z = ZoneId.systemDefault();
			LocalDateTime cand = LocalDate.now(z).atTime(at);
			long ms = cand.atZone(z).toInstant().toEpochMilli();
			if (ms <= now) {
				ms = cand.plusDays(1).atZone(z).toInstant().toEpochMilli();
			}
			return ms;
		}
		return now + Math.max(1, t.minutes) * 60_000L;
	}

	public static LocalTime parseTime(String s) {
		try {
			String[] p = s.strip().split(":");
			return LocalTime.of(Mth.clamp(Integer.parseInt(p[0]), 0, 23), p.length > 1 ? Mth.clamp(Integer.parseInt(p[1]), 0, 59) : 0);
		} catch (RuntimeException e) {
			return LocalTime.MIDNIGHT;
		}
	}

	private static void tick() {
		long now = System.currentTimeMillis();
		if (now - lastCheck < 250) {
			return;
		}
		lastCheck = now;
		WidgetConfig c = WidgetConfig.get();
		if (!c.timersEnabled || c.timers == null) {
			return;
		}
		boolean changed = false;
		for (Timer t : c.timers) {
			if (!t.enabled || t.isAuction()) {
				continue; // Auktionen meldet der Auktions-Alarm
			}
			if (t.nextAt == 0) {
				t.nextAt = next(t, now);
				changed = true;
				continue;
			}
			if (now < t.nextAt) {
				continue;
			}
			// Ablauf. Lag der Ablauf lange zurück (Spiel war zu), nur einmal melden.
			fire(t, now - t.nextAt > 120_000L);
			if (t.daily()) {
				t.nextAt = next(t, now);
			} else if (t.repeat) {
				long step = Math.max(1, t.minutes) * 60_000L;
				while (t.nextAt <= now) {
					t.nextAt += step;
				}
			} else {
				t.enabled = false;
				t.nextAt = 0;
			}
			changed = true;
		}
		if (changed) {
			c.save();
		}
	}

	private static void fire(Timer t, boolean late) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		mc.player.displayClientMessage(Component.translatable(late ? "loothub.timer.done_late" : "loothub.timer.done",
				Component.literal(t.name).withStyle(ChatFormatting.GOLD)).withStyle(ChatFormatting.YELLOW), false);
		mc.gui.setOverlayMessage(Component.literal("⏰ " + t.name).withStyle(ChatFormatting.GOLD), false);
		if (WidgetConfig.get().timerSound) {
			de.loothub.client.Sounds.timer(WidgetConfig.get().timerSoundType);
		}
	}

	/** Restzeit als „1:02:03“ bzw. „4:05“. */
	public static String format(long ms) {
		long s = Math.max(0, (ms + 999) / 1000);
		long d = s / 86400, h = s / 3600 % 24, m = s / 60 % 60, sec = s % 60;
		if (d > 0) {
			return String.format("%d T %d:%02d:%02d", d, h, m, sec);
		}
		h = s / 3600;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
	}

	// ------------------------------------------------------------------ HUD

	/** Eine Zeile der Anzeige: Text, Farbe, Restzeit (zum Sortieren). */
	private record Line(String text, int color, long left) {
	}

	/** Farbe nach Restzeit: letzte Minute rot (blinkt), letzte 10 Minuten gelb, sonst weiß. */
	private static int color(long left) {
		if (left <= 60_000L) {
			return (System.currentTimeMillis() / 400L) % 2 == 0 ? 0xFFFF5555 : 0xFFFF9090;
		}
		return left <= 600_000L ? 0xFFFFD84A : 0xFFFFFFFF;
	}

	/** Eingeblendete Timer, der nächste zuerst. */
	private static List<Line> lines(boolean preview) {
		List<Line> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Timer t : list()) {
			if (!t.shown || !t.enabled || t.nextAt <= 0) {
				continue;
			}
			long left = t.nextAt - now;
			if (t.isAuction()) {
				if (left < -60_000L) {
					continue; // beendet – nicht länger in der Anzeige
				}
				String name = t.name.length() > 22 ? t.name.substring(0, 21) + "…" : t.name;
				out.add(new Line(name + "  " + (left <= 0 ? Component.translatable("loothub.alltag.auction.ended").getString() : (t.approx ? "≥ " : "") + format(left)),
						left <= 0 ? 0xFF9A9A9A : color(left), left));
			} else {
				out.add(new Line(t.name + "  " + format(left), color(left), left));
			}
		}
		out.sort(java.util.Comparator.comparingLong(Line::left));
		if (out.isEmpty() && preview) {
			out.add(new Line("Farm  0:45", 0xFFFF5555, 0));
			out.add(new Line("Auktion  8:30", 0xFFFFD84A, 0));
			out.add(new Line("Tageswechsel  3:12:45", 0xFFFFFFFF, 0));
		}
		return out;
	}

	private static int[] box(List<Line> lines, int sw, int sh) {
		Font f = Minecraft.getInstance().font;
		int w = 0;
		for (Line l : lines) {
			w = Math.max(w, f.width(l.text()) + 14);
		}
		int h = lines.size() * 10 + 4;
		WidgetConfig c = WidgetConfig.get();
		int x = (int) Math.round(c.timerHudX * (sw - w));
		int y = (int) Math.round(c.timerHudY * (sh - h));
		return new int[]{Mth.clamp(x, 0, Math.max(0, sw - w)), Mth.clamp(y, 0, Math.max(0, sh - h)), w, h};
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!WidgetConfig.get().timersEnabled || mc.options.hideGui) {
			return;
		}
		draw(g, lines(false), g.guiWidth(), g.guiHeight());
	}

	public static void drawPreview(GuiGraphics g, int sw, int sh) {
		draw(g, lines(true), sw, sh);
	}

	private static void draw(GuiGraphics g, List<Line> lines, int sw, int sh) {
		if (lines.isEmpty()) {
			return;
		}
		int[] b = box(lines, sw, sh);
		Font f = Minecraft.getInstance().font;
		g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0x90000000);
		g.fill(b[0], b[1], b[0] + 1, b[1] + b[3], 0xFFE23B3B);
		for (int i = 0; i < lines.size(); i++) {
			Line l = lines.get(i);
			g.drawString(f, "⏰", b[0] + 3, b[1] + 3 + i * 10, l.color() == 0xFFFFFFFF ? 0xFFF2C14E : l.color(), false);
			g.drawString(f, l.text(), b[0] + 12, b[1] + 3 + i * 10, l.color(), true);
		}
	}

	public static int[] bounds(int sw, int sh) {
		return box(lines(true), sw, sh);
	}

	/** Linke obere Ecke auf (x, y) setzen. */
	public static void moveTo(double x, double y, int sw, int sh) {
		int[] b = bounds(sw, sh);
		WidgetConfig c = WidgetConfig.get();
		c.timerHudX = sw > b[2] ? Mth.clamp(x / (sw - b[2]), 0, 1) : 0;
		c.timerHudY = sh > b[3] ? Mth.clamp(y / (sh - b[3]), 0, 1) : 0;
	}
}
