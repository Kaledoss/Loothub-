package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Befehls-Leiste unter dem Inventar (Taste E): ein Feld mit Knöpfen für die wichtigsten Befehle.
 * Jeder Klick schickt genau einen Befehl – wie wenn man ihn selbst eintippt (keine Automatisierung).
 * Die Befehle stehen in {@code config/loothub.json} unter {@code quickCommands}.
 */
public final class QuickCommandPanel {
	private static final int BUTTON_H = 16;
	private static final int ROW_GAP = 2;
	private static final int COL_GAP = 3;
	private static final int PAD = 4;
	private static final int MIN_BUTTON_W = 62;
	private static final int RECIPE_BOOK_W = 149;

	private QuickCommandPanel() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof InventoryScreen) || !WidgetConfig.get().quickPanelEnabled) {
				return;
			}
			List<QuickCommand> commands = WidgetConfig.get().quickCommands;
			if (commands == null || commands.isEmpty()) {
				return;
			}
			List<Button> buttons = new ArrayList<>();
			for (QuickCommand qc : commands) {
				if (qc == null || qc.command == null || qc.command.isBlank()) {
					continue;
				}
				String cmd = qc.command.strip().replaceFirst("^/", "");
				String label = qc.label == null || qc.label.isBlank() ? "/" + cmd : qc.label;
				Button b = Button.builder(Component.literal(label), btn -> run(cmd))
						.bounds(0, 0, MIN_BUTTON_W, BUTTON_H)
						.tooltip(Tooltip.create(Component.literal("/" + cmd)))
						.build();
				buttons.add(b);
			}
			List<AbstractWidget> widgets = Screens.getButtons(screen);
			widgets.addAll(buttons);

			Layout layout = new Layout();
			layout.apply(screen, buttons);
			// Das Rezeptbuch verschiebt das Inventar – Knöpfe jeden Tick nachziehen
			ScreenEvents.afterTick(screen).register(s -> layout.apply(s, buttons));
			// Hintergrund des Feldes unter den Knöpfen
			ScreenEvents.afterBackground(screen).register((s, g, mx, my, delta) -> layout.drawPanel(g));
		});
	}

	private static void run(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.getConnection() != null) {
			mc.getConnection().sendCommand(command);
		}
	}

	/** Berechnet Feld und Knopfpositionen unter dem Inventar. */
	private static final class Layout {
		int x, y, w, h;
		boolean visible;

		void apply(Screen screen, List<Button> buttons) {
			AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
			int left = acc.loothub$leftPos();
			int top = acc.loothub$topPos();
			int imgW = acc.loothub$imageWidth();
			int imgH = acc.loothub$imageHeight();

			// Rezeptbuch offen? Dann ist das Inventar nach rechts gerückt – Feld darunter mit abdecken
			int centered = (screen.width - imgW) / 2;
			x = left > centered + 10 ? Math.max(2, left - RECIPE_BOOK_W) : left;
			int right = left + imgW;
			w = right - x;
			y = top + imgH + 4;

			int cols = Math.max(1, (w - 2 * PAD + COL_GAP) / (MIN_BUTTON_W + COL_GAP));
			int bw = (w - 2 * PAD - (cols - 1) * COL_GAP) / cols;
			int maxRows = Math.max(0, (screen.height - y - 2 * PAD - 2) / (BUTTON_H + ROW_GAP));
			int rows = Math.min(maxRows, (buttons.size() + cols - 1) / cols);
			visible = rows > 0;
			h = rows * (BUTTON_H + ROW_GAP) - ROW_GAP + 2 * PAD;

			for (int i = 0; i < buttons.size(); i++) {
				Button b = buttons.get(i);
				int row = i / cols;
				int col = i % cols;
				b.visible = row < rows;
				b.setWidth(bw);
				b.setX(x + PAD + col * (bw + COL_GAP));
				b.setY(y + PAD + row * (BUTTON_H + ROW_GAP));
			}
		}

		void drawPanel(GuiGraphics g) {
			if (!visible) {
				return;
			}
			g.fill(x - 1, y - 1, x + w + 1, y + h + 1, LoothubScreen.RED_DARK);
			g.fill(x, y, x + w, y + h, 0xE0140B0D);
		}
	}
}
