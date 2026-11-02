package de.loothub.client.pcg;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;
import de.loothub.client.quick.Pulse;

/**
 * Leiste über dem Inventar für das Pokémon Community Game: Bälle werfen ({@code !pokecatch}) und kaufen
 * ({@code !pokeshop}). Jeder Knopf schickt genau eine Chat-Nachricht – nur auf Klick, nie automatisch.
 * Kaufen will einen zweiten Klick zur Bestätigung.
 */
public final class PcgPanel {
	private record Ball(String label, String id, int color) {
	}

	private static final List<Ball> BALLS = List.of(
			new Ball("Poké", "pokeball", 0xFFE04848),
			new Ball("Super", "greatball", 0xFF4A90E0),
			new Ball("Hyper", "ultraball", 0xFFE8C547));
	private static final int BUY_AMOUNT = 10;

	private static final int BTN_W = 44;
	private static final int BTN_H = 16;
	private static final int GAP = 3;
	private static final int PAD = 4;
	private static final int HEADER_H = 12;

	private static long feedbackUntil;
	private static String feedback = "";
	private static int armedBuy = -1;
	private static long armedUntil;

	private PcgPanel() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof InventoryScreen) || !WidgetConfig.get().pcgEnabled) {
				return;
			}
			ScreenEvents.afterBackground(screen).register((s, g, mx, my, delta) -> render(s, g, mx, my));
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !click(s, e));
		});
	}

	// Positionen: Leiste mittig über dem Inventar
	private static int panelW() {
		return 2 * PAD + 6 * BTN_W + 5 * GAP + 6;
	}

	private static int panelH() {
		return HEADER_H + BTN_H + PAD + 2;
	}

	private static int[] origin(Screen s) {
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
		int x = acc.loothub$leftPos() + (acc.loothub$imageWidth() - panelW()) / 2;
		int y = acc.loothub$topPos() - panelH() - 5;
		return new int[]{Math.max(2, x), Math.max(2, y)};
	}

	/** Knopf i: 0–2 werfen, 3–5 kaufen. */
	private static int buttonX(int ox, int i) {
		return ox + PAD + i * (BTN_W + GAP) + (i >= 3 ? 6 : 0);
	}

	private static void render(Screen s, GuiGraphics g, int mx, int my) {
		Font font = Minecraft.getInstance().font;
		int[] o = origin(s);
		int x = o[0], y = o[1], w = panelW(), h = panelH();
		g.fill(x, y, x + w, y + h, 0xE0140B0D);
		Pulse.outline(g, x, y, w, h);

		// Kopf: Spawn + Zeit, rechts Verbindungsstatus
		PcgState.Spawn spawn = PcgState.current();
		String head;
		int headColor;
		if (spawn != null && spawn.result() == null) {
			head = spawn.name() + " · " + Fmt.clock(spawn.remainingMs());
			headColor = 0xFFFFD66B;
		} else {
			head = "Kein Pokémon";
			headColor = LoothubScreen.FAINT;
		}
		g.drawString(font, head, x + PAD, y + 2, headColor, false);
		String st = statusText();
		g.drawString(font, st, x + w - PAD - font.width(st), y + 2, statusColor(), false);

		long now = System.currentTimeMillis();
		if (armedBuy >= 0 && now > armedUntil) {
			armedBuy = -1;
		}
		boolean ok = TwitchChat.canWrite();
		int by = y + HEADER_H;
		for (int i = 0; i < 6; i++) {
			Ball b = BALLS.get(i % 3);
			boolean buy = i >= 3;
			int bx = buttonX(x, i);
			boolean hover = mx >= bx && mx < bx + BTN_W && my >= by && my < by + BTN_H;
			int edge = buy ? 0xFFB08A2A : b.color();
			g.fill(bx, by, bx + BTN_W, by + BTN_H, ok ? (hover ? 0xFF3A1C22 : 0xFF24161A) : 0xFF1A1214);
			g.fill(bx, by + BTN_H - 1, bx + BTN_W, by + BTN_H, ok ? edge : 0xFF3A2A2C);
			g.fill(bx, by, bx + 2, by + BTN_H - 1, ok ? b.color() : 0xFF3A2A2C);
			String label = buy ? (armedBuy == i ? "Sicher?" : "+" + BUY_AMOUNT + " " + b.label()) : b.label();
			int tc = !ok ? LoothubScreen.FAINT : (buy && armedBuy == i ? 0xFFFF5050 : 0xFFFFFFFF);
			g.drawString(font, label, bx + 2 + (BTN_W - 2 - font.width(label)) / 2, by + 4, tc, false);
			if (hover) {
				Component tip = !ok
						? Component.literal("Zum Senden Twitch-Name und Token in /loothub eintragen")
						: Component.literal(buy ? "!pokeshop " + b.id() + " " + BUY_AMOUNT + "  (2× klicken)" : "!pokecatch " + b.id());
				g.setTooltipForNextFrame(font, tip, mx, my);
			}
		}
		// Trenner zwischen Werfen und Kaufen
		int sx = buttonX(x, 3) - 5;
		g.fill(sx, by + 2, sx + 1, by + BTN_H - 2, 0xFF4A2A2F);

		if (now < feedbackUntil) {
			g.drawString(font, feedback, x + PAD, y + h + 2, 0xFF55E06A, true);
		}
	}

	private static boolean click(Screen s, MouseButtonEvent e) {
		if (e.button() != 0) {
			return false;
		}
		int[] o = origin(s);
		int by = o[1] + HEADER_H;
		if (e.y() < by || e.y() >= by + BTN_H) {
			return false;
		}
		for (int i = 0; i < 6; i++) {
			int bx = buttonX(o[0], i);
			if (e.x() < bx || e.x() >= bx + BTN_W) {
				continue;
			}
			if (!TwitchChat.canWrite()) {
				return true;
			}
			Ball b = BALLS.get(i % 3);
			long now = System.currentTimeMillis();
			if (i >= 3) {
				if (armedBuy != i || now > armedUntil) {
					armedBuy = i;
					armedUntil = now + 2500;
					return true;
				}
				armedBuy = -1;
				show(TwitchChat.send("!pokeshop " + b.id() + " " + BUY_AMOUNT) ? "Gesendet: " + BUY_AMOUNT + "× " + b.label() + " kaufen" : "Nicht gesendet");
			} else {
				show(TwitchChat.send("!pokecatch " + b.id()) ? "Gesendet: " + b.label() + "-Ball geworfen" : "Nicht gesendet");
			}
			return true;
		}
		return false;
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
