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

		public boolean daily() {
			return "daily".equals(mode);
		}
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
			if (!t.enabled) {
				continue;
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
		long h = s / 3600, m = s / 60 % 60, sec = s % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
	}

	// ------------------------------------------------------------------ HUD

	private static List<String> lines(boolean preview) {
		List<String> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Timer t : list()) {
			if (t.enabled && t.nextAt > 0) {
				out.add(t.name + "  " + format(t.nextAt - now));
			}
		}
		if (out.isEmpty() && preview) {
			out.add("Tageswechsel  3:12:45");
			out.add("Farm  12:30");
		}
		return out;
	}

	private static int[] box(List<String> lines, int sw, int sh) {
		Font f = Minecraft.getInstance().font;
		int w = 0;
		for (String l : lines) {
			w = Math.max(w, f.width(l) + 14);
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

	private static void draw(GuiGraphics g, List<String> lines, int sw, int sh) {
		if (lines.isEmpty()) {
			return;
		}
		int[] b = box(lines, sw, sh);
		Font f = Minecraft.getInstance().font;
		g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0x90000000);
		g.fill(b[0], b[1], b[0] + 1, b[1] + b[3], 0xFFE23B3B);
		for (int i = 0; i < lines.size(); i++) {
			g.drawString(f, "⏰", b[0] + 3, b[1] + 3 + i * 10, 0xFFF2C14E, false);
			g.drawString(f, lines.get(i), b[0] + 12, b[1] + 3 + i * 10, 0xFFFFFFFF, true);
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
