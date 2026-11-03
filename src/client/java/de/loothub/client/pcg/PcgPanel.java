package de.loothub.client.pcg;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.Loothub;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;
import de.loothub.client.quick.Pulse;

/**
 * Leiste über dem Inventar für das Pokémon Community Game: Bälle werfen ({@code !pokecatch}) und kaufen
 * ({@code !pokeshop}). Jeder Knopf schickt genau eine Chat-Nachricht – nur auf Klick, nie automatisch.
 * Kaufen will einen zweiten Klick zur Bestätigung. Die Leiste lässt sich an der Kopfzeile verschieben und sperren.
 */
public final class PcgPanel {
	/** Ball-Art: Anzeigename, Befehls-ID und eigenes Kugel-Symbol. */
	private record Ball(String label, String id, Identifier icon) {
	}

	private static final List<Ball> BALLS = List.of(
			new Ball("Pokéball", "pokeball", tex("orb_red")),
			new Ball("Superball", "greatball", tex("orb_blue")),
			new Ball("Hyperball", "ultraball", tex("orb_gold")));
	private static final int BUY_AMOUNT = 10;
	/** 0–2 Standardbälle werfen, 3 Sonderball werfen, 4–6 Standardbälle kaufen. */
	private static final int SPECIAL = 3;
	private static final int FIRST_BUY = 4;
	private static final int COUNT = 7;
	private static final Identifier SPECIAL_ICON = tex("orb_green");

	private static final int BTN = 22;
	private static final int GAP = 3;
	private static final int SEP = 8;
	private static final int PAD = 4;
	private static final int HEADER_H = 13;
	private static final int BTN_BG = 0xFF24161A;
	private static final int BTN_BG_HOVER = 0xFF3A1C22;
	private static final int BTN_EDGE = 0xFF4A2A2F;

	private static long feedbackUntil;
	private static String feedback = "";
	private static int armedBuy = -1;
	private static long armedUntil;
	private static boolean moving;
	private static double grabX, grabY;

	private PcgPanel() {
	}

	private static Identifier tex(String name) {
		return Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/gui/" + name + ".png");
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof InventoryScreen) || !WidgetConfig.get().pcgEnabled) {
				return;
			}
			ScreenEvents.afterBackground(screen).register((s, g, mx, my, delta) -> render(s, g, mx, my));
			// Rückmeldung zuletzt zeichnen, damit sie über dem Inventar lesbar bleibt
			ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> renderFeedback(s, g));
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !click(s, e));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !drag(s, e));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> !release());
		});
	}

	// ------------------------------------------------------------------ Lage

	private static String headText() {
		PcgState.Spawn spawn = PcgState.current();
		if (spawn != null && spawn.result() == null) {
			return GermanNames.of(spawn.name()) + " · " + Fmt.clock(spawn.remainingMs());
		}
		long next = PcgState.untilNextMs();
		if (next < 0) {
			return "Nächstes: warte auf Spawn";
		}
		return next == 0 ? "Nächstes: gleich" : "Nächstes in " + Fmt.clock(next);
	}

	private static int panelW(Font font) {
		int buttons = 2 * PAD + COUNT * BTN + (COUNT - 2) * GAP + SEP;
		// Breite ohne die laufende Uhr berechnen, damit die Leiste nicht im Sekundentakt zappelt
		PcgState.Spawn spawn = PcgState.current();
		String sample = spawn != null && spawn.result() == null ? GermanNames.of(spawn.name()) + " · 00:00 · ? prüfen" : "Nächstes: warte auf Spawn";
		int head = 2 * PAD + font.width("⠿ " + sample) + 8 + font.width("Verbinde …") + 8 + lockW(font);
		return Math.max(buttons, head);
	}

	private static int panelH() {
		return HEADER_H + BTN + PAD;
	}

	private static int lockW(Font font) {
		return font.width(Component.translatable("loothub.quick.lock")) + 12;
	}

	/** Linke obere Ecke: Standard mittig über dem Inventar + gespeicherte Verschiebung. */
	private static int[] origin(Screen s, Font font) {
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
		WidgetConfig c = WidgetConfig.get();
		int w = panelW(font);
		int defX = acc.loothub$leftPos() + (acc.loothub$imageWidth() - w) / 2;
		int defY = acc.loothub$topPos() - panelH() - 5;
		int x = Mth.clamp(defX + c.pcgPanelOffsetX, 2, Math.max(2, s.width - w - 2));
		int y = Mth.clamp(defY + c.pcgPanelOffsetY, 2, Math.max(2, s.height - panelH() - 2));
		return new int[]{x, y, defX, defY};
	}

	/** Knopf i: 0–3 werfen (3 = Sonderball), 4–6 kaufen. */
	private static int buttonX(int ox, int i) {
		return ox + PAD + i * (BTN + GAP) + (i >= FIRST_BUY ? SEP - GAP : 0);
	}

	private static boolean onLock(int x, int y, int w, Font font, double mx, double my) {
		return my >= y + 1 && my < y + 12 && mx >= x + w - PAD - lockW(font) && mx < x + w - PAD;
	}

	// ------------------------------------------------------------------ Zeichnen

	private static void render(Screen s, GuiGraphics g, int mx, int my) {
		Font font = Minecraft.getInstance().font;
		int[] o = origin(s, font);
		int x = o[0], y = o[1], w = panelW(font), h = panelH();
		boolean locked = WidgetConfig.get().pcgPanelLocked;
		g.fill(x, y, x + w, y + h, 0xE0140B0D);
		Pulse.outline(g, x, y, w, h);

		// Kopf: Griff + Spawn/Zeit, rechts Status und „Sperren“
		PcgState.Spawn spawn = PcgState.current();
		boolean active = spawn != null && spawn.result() == null;
		String head = (locked ? "" : "⠿ ") + headText();
		g.drawString(font, head, x + PAD, y + 3, active ? 0xFFFFD66B : 0xFFB9A9AB, false);
		if (active) {
			int tx = x + PAD + font.width(head) + 5;
			String tag = dexTag(spawn.name());
			boolean tagHover = mx >= tx - 2 && mx < tx + font.width(tag) + 2 && my >= y + 1 && my < y + 12;
			g.drawString(font, tag, tx, y + 3, tagHover ? 0xFFFFFFFF : dexColor(spawn.name()), false);
			if (tagHover) {
				g.setTooltipForNextFrame(font, Component.literal(dexTooltip(spawn.name())), mx, my);
			}
		}

		int lx = x + w - PAD - 9;
		boolean lockHover = onLock(x, y, w, font, mx, my);
		g.fill(lx, y + 3, lx + 8, y + 11, lockHover ? 0xFFFFFFFF : BTN_EDGE);
		g.fill(lx + 1, y + 4, lx + 7, y + 10, 0xFF0F0A0B);
		if (locked) {
			g.fill(lx + 2, y + 5, lx + 6, y + 9, LoothubScreen.RED);
		}
		Component lock = Component.translatable("loothub.quick.lock");
		g.drawString(font, lock, lx - 3 - font.width(lock), y + 3, locked ? 0xFFFFFFFF : LoothubScreen.FAINT, false);
		String st = statusText();
		g.drawString(font, st, lx - 3 - font.width(lock) - 8 - font.width(st), y + 3, statusColor(), false);

		long now = System.currentTimeMillis();
		if (armedBuy >= 0 && now > armedUntil) {
			armedBuy = -1;
		}
		boolean ok = TwitchChat.canWrite();
		int by = y + HEADER_H;
		GermanNames.Special special = active ? GermanNames.specialBall(spawn.name()) : null;
		for (int i = 0; i < COUNT; i++) {
			boolean isSpecial = i == SPECIAL;
			Ball b = isSpecial ? null : BALLS.get(i >= FIRST_BUY ? i - FIRST_BUY : i);
			boolean buy = i >= FIRST_BUY;
			int bx = buttonX(x, i);
			boolean hover = !moving && mx >= bx && mx < bx + BTN && my >= by && my < by + BTN;
			boolean armed = buy && armedBuy == i;
			boolean recommended = !buy && !isSpecial && active && GermanNames.recommendedBall(spawn.name()) == i;
			boolean usable = !isSpecial || special != null;
			int edge = armed ? 0xFFFF5050 : recommended || (isSpecial && special != null) ? 0xFFFFD040
					: hover && usable ? LoothubScreen.RED : BTN_EDGE;
			g.fill(bx, by, bx + BTN, by + BTN, edge);
			g.fill(bx + 1, by + 1, bx + BTN - 1, by + BTN - 1, hover && usable ? BTN_BG_HOVER : BTN_BG);
			g.blit(RenderPipelines.GUI_TEXTURED, isSpecial ? SPECIAL_ICON : b.icon(), bx + 3, by + 3, 0f, 0f, 16, 16, 16, 16, 16, 16);
			if (isSpecial) {
				g.drawString(font, "★", bx + BTN - 8, by + BTN - 9, 0xFFFFD040, true);
				if (special == null) {
					g.fill(bx + 1, by + 1, bx + BTN - 1, by + BTN - 1, 0xA0140B0D);
				}
			}
			if (buy) {
				// Dollarzeichen unten rechts = kaufen
				g.drawString(font, "$", bx + BTN - 7, by + BTN - 9, 0xFFFFC040, true);
			}
			if (!ok) {
				g.fill(bx + 1, by + 1, bx + BTN - 1, by + BTN - 1, 0x90140B0D); // ausgegraut
			}
			if (hover) {
				Component tip;
				if (!ok) {
					tip = Component.literal("Zum Senden Twitch-Name und Token in /loothub eintragen");
				} else if (buy) {
					tip = Component.literal(armed ? BUY_AMOUNT + "× " + b.label() + " kaufen – nochmal klicken"
							: BUY_AMOUNT + "× " + b.label() + " kaufen (2× klicken)");
				} else if (isSpecial) {
					tip = special == null
							? Component.literal("Sonderball: gerade keiner sinnvoll")
							: Component.literal(special.label() + " werfen – " + special.reason());
				} else {
					tip = Component.literal(b.label() + " werfen" + (recommended ? " – empfohlen" : ""));
				}
				g.setTooltipForNextFrame(font, tip, mx, my);
			}
		}
		// Trenner zwischen Werfen und Kaufen
		int sx = buttonX(x, FIRST_BUY) - SEP / 2 - 1;
		g.fill(sx, by + 3, sx + 1, by + BTN - 3, BTN_EDGE);

	}

	/** „Gesendet: …“ direkt über der Leiste, auf eigenem dunklen Streifen. */
	private static void renderFeedback(Screen s, GuiGraphics g) {
		if (System.currentTimeMillis() >= feedbackUntil) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int[] o = origin(s, font);
		int x = o[0];
		int y = Math.max(0, o[1] - 14);
		int w = font.width(feedback) + 2 * PAD;
		g.fill(x, y, x + w, y + 12, 0xF0140B0D);
		g.fill(x, y + 11, x + w, y + 12, 0xFF55E06A);
		g.drawString(font, feedback, x + PAD, y + 2, 0xFF55E06A, false);
	}

	// ------------------------------------------------------------------ Maus

	private static boolean click(Screen s, MouseButtonEvent e) {
		if (e.button() != 0) {
			return false;
		}
		Font font = Minecraft.getInstance().font;
		int[] o = origin(s, font);
		int x = o[0], y = o[1], w = panelW(font), h = panelH();
		if (e.x() < x || e.x() >= x + w || e.y() < y || e.y() >= y + h) {
			return false;
		}
		WidgetConfig c = WidgetConfig.get();
		if (onLock(x, y, w, font, e.x(), e.y())) {
			c.pcgPanelLocked = !c.pcgPanelLocked;
			c.save();
			return true;
		}
		if (e.y() < y + HEADER_H) {
			// Klick auf den Pokédex-Hinweis: per !pokecheck nachfragen
			PcgState.Spawn sp = PcgState.current();
			if (sp != null && sp.result() == null) {
				String head = (c.pcgPanelLocked ? "" : "⠿ ") + headText();
				int tx = x + PAD + font.width(head) + 5;
				if (e.x() >= tx - 2 && e.x() < tx + font.width(dexTag(sp.name())) + 2) {
					if (TwitchChat.canWrite()) {
						show(PcgState.requestCheck(sp.name()) ? "Gesendet: !pokecheck" : "Nicht gesendet");
					}
					return true;
				}
			}
			if (!c.pcgPanelLocked) {
				moving = true;
				grabX = e.x() - x;
				grabY = e.y() - y;
			}
			return true;
		}
		int by = y + HEADER_H;
		for (int i = 0; i < COUNT; i++) {
			int bx = buttonX(x, i);
			if (e.x() < bx || e.x() >= bx + BTN || e.y() < by || e.y() >= by + BTN) {
				continue;
			}
			if (!TwitchChat.canWrite()) {
				return true;
			}
			long now = System.currentTimeMillis();
			if (i == SPECIAL) {
				PcgState.Spawn sp = PcgState.current();
				GermanNames.Special special = sp != null && sp.result() == null ? GermanNames.specialBall(sp.name()) : null;
				if (special != null) {
					show(TwitchChat.send("!pokecatch " + special.id()) ? "Gesendet: " + special.label() + " geworfen" : "Nicht gesendet");
				}
				return true;
			}
			Ball b = BALLS.get(i >= FIRST_BUY ? i - FIRST_BUY : i);
			if (i >= FIRST_BUY) {
				if (armedBuy != i || now > armedUntil) {
					armedBuy = i;
					armedUntil = now + 2500;
					return true;
				}
				armedBuy = -1;
				show(TwitchChat.send("!pokeshop " + b.id() + " " + BUY_AMOUNT)
						? "Gesendet: " + BUY_AMOUNT + "× " + b.label() + " kaufen" : "Nicht gesendet");
			} else {
				show(TwitchChat.send("!pokecatch " + b.id()) ? "Gesendet: " + b.label() + " geworfen" : "Nicht gesendet");
			}
			return true;
		}
		return true; // Klicks ins Feld nicht ans Inventar weitergeben
	}

	private static boolean drag(Screen s, MouseButtonEvent e) {
		if (!moving) {
			return false;
		}
		Font font = Minecraft.getInstance().font;
		int[] o = origin(s, font);
		WidgetConfig c = WidgetConfig.get();
		c.pcgPanelOffsetX += (int) Math.round(e.x() - grabX) - o[0];
		c.pcgPanelOffsetY += (int) Math.round(e.y() - grabY) - o[1];
		return true;
	}

	private static boolean release() {
		if (!moving) {
			return false;
		}
		moving = false;
		WidgetConfig.get().save();
		return true;
	}

	static String dexTag(String name) {
		return switch (Pokedex.state(name)) {
			case CAUGHT -> "✔ im Dex";
			case NOT_CAUGHT -> "NEU!";
			case UNKNOWN -> "? prüfen";
		};
	}

	static int dexColor(String name) {
		return switch (Pokedex.state(name)) {
			case CAUGHT -> 0xFF9A9A9A;
			case NOT_CAUGHT -> 0xFF55E06A;
			case UNKNOWN -> 0xFF6FA8FF;
		};
	}

	private static String dexTooltip(String name) {
		return switch (Pokedex.state(name)) {
			case CAUGHT -> "Schon gefangen – klicken zum erneuten Prüfen";
			case NOT_CAUGHT -> "Noch nicht gefangen – neu für deinen Pokédex!";
			case UNKNOWN -> "Unbekannt – klicken: !pokecheck senden";
		};
	}

	private static void show(String text) {
		feedback = text;
		feedbackUntil = System.currentTimeMillis() + 2500;
	}

	private static String statusText() {
		return switch (TwitchChat.status()) {
			case OFF -> "Aus";
			case CONNECTING -> "Verbinde …";
			case READ_ONLY -> "Nur lesen";
			case CONNECTED -> "Verbunden";
			case ERROR -> "Token falsch";
		};
	}

	private static int statusColor() {
		return switch (TwitchChat.status()) {
			case CONNECTED -> 0xFF55E06A;
			case READ_ONLY -> 0xFFFFD66B;
			case ERROR -> 0xFFFF5050;
			default -> LoothubScreen.FAINT;
		};
	}
}
