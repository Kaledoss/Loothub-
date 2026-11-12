package de.loothub.client.jobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.client.LoothubScreen;
import de.loothub.client.Sounds;
import de.loothub.client.streamer.Alerts;

/**
 * Fang-Einblendung: zeigt den gefangenen Fisch im Stil der Twitch-Einblendungen – Name groß in der Farbe der
 * Seltenheit, darunter Seltenheit und Größe – mit Ton passend zur Seltenheit. Welche Seltenheiten erscheinen,
 * stellt man in den Einstellungen ein (wie die Erze beim Leuchten). Bei offenem Chat (T) verschiebbar.
 */
public final class FishAlert {
	/** rarity = Seltenheit (für den Ton), "!bait" = Köder-Warnung. */
	private record Shown(String title, String sub, int color, String rarity) {
	}

	private static final long SHOW_MS = 4500;
	private static final long FADE_MS = 300;
	private static final Deque<Shown> QUEUE = new ArrayDeque<>();
	private static Shown current;
	private static long since;
	private static boolean dragging;
	private static double grabX, grabY;

	private FishAlert() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof LoothubScreen) {
				return;
			}
			ScreenEvents.afterRender(screen).register((s, g, mx, my, d) -> {
				if (isChat(s) && WidgetConfig.get().fishAlert) {
					renderEditable(s, g, mx, my);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !isChat(s) || !WidgetConfig.get().fishAlert || !click(s, e));
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
		return !Minecraft.getInstance().options.hideGui && de.loothub.client.quick.CustomChat.chatOpen(screen);
	}

	/** Soll diese Seltenheit eingeblendet werden? */
	public static boolean shows(String rarity) {
		WidgetConfig c = WidgetConfig.get();
		return c.fishAlertOff == null || !c.fishAlertOff.contains(rarity.strip().toLowerCase(Locale.ROOT));
	}

	public static void setShows(String rarity, boolean on) {
		WidgetConfig c = WidgetConfig.get();
		if (c.fishAlertOff == null) {
			c.fishAlertOff = new ArrayList<>();
		}
		String k = rarity.strip().toLowerCase(Locale.ROOT);
		c.fishAlertOff.remove(k);
		if (!on) {
			c.fishAlertOff.add(k);
		}
		c.save();
	}

	/** Alle Seltenheiten zur Auswahl: bekannte Reihenfolge plus alle, die schon einmal gefangen wurden. */
	public static List<String> rarities() {
		List<String> out = new ArrayList<>(FishingLog.knownRarities());
		for (FishingLog.Catch c : FishingLog.all()) {
			String k = c.rarity.strip().toLowerCase(Locale.ROOT);
			if (!k.isEmpty() && !out.contains(k)) {
				out.add(k);
			}
		}
		return out;
	}

	/** Neuer Fang (Spiel-Thread). */
	static void post(FishingLog.Catch c) {
		WidgetConfig cfg = WidgetConfig.get();
		if (!cfg.fishAlert || !shows(c.rarity)) {
			return;
		}
		int color = c.rarityColor >= 0 ? c.rarityColor : 0xB9A9AB;
		String sub = c.rarity.toUpperCase(Locale.ROOT) + (c.size.isEmpty() ? "" : " · " + c.size);
		if (QUEUE.size() < 10) {
			QUEUE.add(new Shown("≋ " + c.fish, sub, color, c.rarity));
		}
	}

	/** Beispiel zum Ausprobieren (Knopf „Test“). */
	public static void test() {
		QUEUE.add(new Shown("≋ Orakelfisch", "EPISCH · Mittel", 0x55FF55, "episch"));
	}

	/** Ton einer Seltenheit zum Anhören (Einstellungen). */
	public static void preview(String rarity) {
		sound(rarity);
	}

	/** Allgemeine Hinweis-Einblendung im selben Stil (z. B. Auktions-Alarm), ohne eigenen Ton. */
	public static void notice(String title, String sub, int color) {
		QUEUE.addFirst(new Shown(title, sub, color, "!silent"));
	}

	/** Köder sind aufgebraucht: rote Warnung mit Alarm, unabhängig von der Seltenheits-Auswahl. */
	static void baitEmpty() {
		QUEUE.addFirst(new Shown("⚠ Keine Köder mehr!", "Neue Köder auffüllen", 0xFF5050, "!bait"));
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (current != null && now - since > SHOW_MS) {
			current = null;
		}
		if (current == null && !QUEUE.isEmpty()) {
			current = QUEUE.poll();
			since = now;
			sound(current.rarity());
		}
		if (current == null || mc.options.hideGui) {
			return;
		}
		long age = now - since;
		float a = age < FADE_MS ? age / (float) FADE_MS : age > SHOW_MS - FADE_MS ? (SHOW_MS - age) / (float) FADE_MS : 1f;
		a = Mth.clamp(a, 0f, 1f);
		int[] size = Alerts.boxSize(mc.font, current.title(), current.sub());
		int[] p = pos(g.guiWidth(), g.guiHeight(), size[0], size[1]);
		Alerts.drawBox(g, mc.font, current.title(), current.sub(), current.color(), p[0], p[1] + (int) ((1 - a) * -8), a);
	}

	/** Position aus der Einstellung (Mitte der Box folgt dem Anteil). */
	public static int[] pos(int sw, int sh, int w, int h) {
		WidgetConfig c = WidgetConfig.get();
		int x = (int) Math.round(c.fishAlertX * Math.max(0, sw - w));
		int y = (int) Math.round(c.fishAlertY * Math.max(0, sh - h));
		return new int[]{x, y};
	}

	/** Größe der Beispiel-Box (für Verschieben im Chat und in „HUD-Positionen“). */
	public static int[] sampleSize(Font font) {
		return Alerts.boxSize(font, "≋ Orakelfisch", "EPISCH · Mittel");
	}

	/** Eigener Klang je Seltenheit – je seltener, desto prächtiger. */
	private static void sound(String rarity) {
		List<Sounds.Note> l = new ArrayList<>();
		if ("!silent".equals(rarity)) {
			return;
		}
		if ("!bait".equals(rarity)) {
			for (int i = 0; i < 3; i++) {
				l.add(Sounds.n(i * 6, SoundEvents.NOTE_BLOCK_DIDGERIDOO, 1.0F, 1.0F));
				l.add(Sounds.n(i * 6 + 3, SoundEvents.NOTE_BLOCK_BIT, 0.7F, 0.8F));
			}
			Sounds.play(Sounds.Group.FISH, l);
			return;
		}
		String k = rarity == null ? "" : rarity.strip().toLowerCase(Locale.ROOT);
		if (!FishingLog.known(k)) {
			// unbekannte Seltenheit: eigenes, auffälliges Xylophon-Motiv
			l.addAll(Sounds.arpeggio(SoundEvents.NOTE_BLOCK_XYLOPHONE, 0.9F, 0.8F, 2));
			l.add(Sounds.n(9, SoundEvents.AMETHYST_BLOCK_CHIME, 1.4F, 1.0F));
			Sounds.play(Sounds.Group.FISH, l);
			return;
		}
		switch (FishingLog.rank(k)) {
			case 0 -> l.add(Sounds.n(0, SoundEvents.NOTE_BLOCK_PLING, 1.4F, 0.4F)); // Vanilla
			case 1 -> { // Trödel: dumpfes Plopp
				l.add(Sounds.n(0, SoundEvents.NOTE_BLOCK_BASS, 0.8F, 0.6F));
				l.add(Sounds.n(3, SoundEvents.NOTE_BLOCK_BASS, 0.6F, 0.5F));
			}
			case 2 -> { // Schatz: Klimpern
				l.add(Sounds.n(0, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.6F));
				l.add(Sounds.n(2, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3F, 0.6F));
				l.add(Sounds.n(4, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F, 0.9F));
			}
			case 3 -> { // Häufig
				l.add(Sounds.n(0, SoundEvents.NOTE_BLOCK_CHIME, 1.2F, 0.6F));
				l.add(Sounds.n(3, SoundEvents.NOTE_BLOCK_CHIME, 1.5F, 0.6F));
			}
			case 4 -> { // Episch
				l.add(Sounds.n(0, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.0F));
				l.addAll(Sounds.arpeggio(SoundEvents.NOTE_BLOCK_CHIME, 0.9F, 0.7F, 2));
			}
			case 5 -> { // Legendär: schöner, aber noch bodenständig – Glockenspiel mit Amethyst-Funkeln
				l.add(Sounds.n(0, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 1.0F));
				l.addAll(Sounds.arpeggio(SoundEvents.NOTE_BLOCK_BELL, 0.84F, 0.9F, 3));
				l.add(Sounds.n(12, SoundEvents.PLAYER_LEVELUP, 1.3F, 0.4F));
			}
			case 6 -> { // Spektakulär: episch – tiefer Gong, Glocken-Wirbel, Fanfare, Feuerwerk
				l.add(Sounds.n(0, SoundEvents.BELL_BLOCK, 0.6F, 1.0F));
				l.add(Sounds.n(0, SoundEvents.NOTE_BLOCK_BASS, 0.5F, 0.9F));
				for (int i = 0; i < 6; i++) {
					l.add(Sounds.n(6 + i * 2, SoundEvents.NOTE_BLOCK_BELL, 0.7F + i * 0.2F, 0.8F));
				}
				l.add(Sounds.n(18, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.6F));
				l.add(Sounds.n(26, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0F, 0.6F));
				l.add(Sounds.n(32, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.1F, 0.7F));
			}
			default -> { // Sensationell: das ganz große Ereignis
				l.add(Sounds.n(0, SoundEvents.END_PORTAL_SPAWN, 1.2F, 0.35F));
				l.add(Sounds.n(4, SoundEvents.BEACON_ACTIVATE, 1.4F, 0.8F));
				l.add(Sounds.n(10, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.1F, 0.6F));
				for (int i = 0; i < 3; i++) {
					l.add(Sounds.n(18 + i * 8, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.9F + i * 0.1F, 0.6F));
					l.add(Sounds.n(22 + i * 8, SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0F, 0.7F));
				}
			}
		}
		Sounds.play(Sounds.Group.FISH, l);
	}

	// ------------------------------------------------------------------ im Chat verschieben

	private static void renderEditable(Screen screen, GuiGraphics g, int mx, int my) {
		Font font = Minecraft.getInstance().font;
		int[] size = sampleSize(font);
		int[] p = pos(screen.width, screen.height, size[0], size[1]);
		if (current == null) {
			Alerts.drawBox(g, font, "≋ Orakelfisch", "EPISCH · Mittel", 0x55FF55, p[0], p[1], 0.5f);
		}
		boolean over = mx >= p[0] && mx < p[0] + size[0] && my >= p[1] && my < p[1] + size[1];
		int c = dragging || over ? 0xFFFFFFFF : 0x80E23B3B;
		g.fill(p[0] - 1, p[1] - 1, p[0] + size[0] + 1, p[1], c);
		g.fill(p[0] - 1, p[1] + size[1], p[0] + size[0] + 1, p[1] + size[1] + 1, c);
		g.fill(p[0] - 1, p[1], p[0], p[1] + size[1], c);
		g.fill(p[0] + size[0], p[1], p[0] + size[0] + 1, p[1] + size[1], c);
	}

	private static boolean click(Screen screen, MouseButtonEvent e) {
		if (e.button() != 0) {
			return false;
		}
		int[] size = sampleSize(Minecraft.getInstance().font);
		int[] p = pos(screen.width, screen.height, size[0], size[1]);
		if (e.x() >= p[0] && e.x() < p[0] + size[0] && e.y() >= p[1] && e.y() < p[1] + size[1]) {
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
		int[] size = sampleSize(Minecraft.getInstance().font);
		WidgetConfig c = WidgetConfig.get();
		c.fishAlertX = Mth.clamp((e.x() - grabX) / Math.max(1, screen.width - size[0]), 0, 1);
		c.fishAlertY = Mth.clamp((e.y() - grabY) / Math.max(1, screen.height - size[1]), 0, 1);
		return true;
	}
}
