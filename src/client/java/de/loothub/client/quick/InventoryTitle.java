package de.loothub.client.quick;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.client.LoothubScreen;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Spieler-Inventar: Überschrift „Loothub+“ statt „Crafting/Handwerk“ und zwei feste Knöpfe rechts neben dem
 * Rezeptbuch-Knopf – Werkbank ({@code /werkbank}) und Amboss ({@code /anvil}). Ein Klick schickt den Befehl.
 */
public final class InventoryTitle {
	/** Gleiche Fläche wie das helle Schild in der Inventar-Textur. */
	private static final int X1 = 95, Y1 = 5, X2 = 161, Y2 = 15;

	/**
	 * Feste Knöpfe in der Zeile des Zweithand-Slots, rechts neben dem Rezeptbuch-Knopf
	 * (Rezeptbuch: x=104–124, y=61 – darf nicht überdeckt werden).
	 */
	private record IconButton(int x, int y, ItemStack icon, String command) {
		boolean contains(double mx, double my, int left, int top) {
			return mx >= left + x - 1 && mx < left + x + 17 && my >= top + y - 1 && my < top + y + 17;
		}
	}

	private static final IconButton[] BUTTONS = {
			new IconButton(130, 62, new ItemStack(Items.CRAFTING_TABLE), "werkbank"),
			new IconButton(150, 62, new ItemStack(Items.ANVIL), "anvil"),
	};

	private InventoryTitle() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof InventoryScreen)) {
				return;
			}
			ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> {
				AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
				int left = acc.loothub$leftPos();
				int top = acc.loothub$topPos();
				Font font = Minecraft.getInstance().font;
				g.fill(left + X1, top + Y1, left + X2, top + Y2, 0xFFCEBABE);
				g.fill(left + X1, top + Y2, left + X2, top + Y2 + 1, LoothubScreen.RED);
				g.drawString(font, "Loothub+", left + 97, top + 6, LoothubScreen.RED_DARK, false);
				drawButtons(g, font, left, top, mx, my);
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> {
				if (e.button() != 0) {
					return true;
				}
				AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
				for (IconButton b : BUTTONS) {
					if (b.contains(e.x(), e.y(), acc.loothub$leftPos(), acc.loothub$topPos())) {
						Minecraft mc = Minecraft.getInstance();
						if (mc.getConnection() != null) {
							mc.getConnection().sendCommand(b.command());
						}
						return false; // Klick nicht ans Inventar weitergeben
					}
				}
				return true;
			});
		});
	}

	private static void drawButtons(GuiGraphics g, Font font, int left, int top, int mx, int my) {
		for (IconButton b : BUTTONS) {
			boolean hover = b.contains(mx, my, left, top);
			if (hover) {
				g.fill(left + b.x() - 1, top + b.y() - 1, left + b.x() + 17, top + b.y() + 17, 0x50E23B3B);
			}
			g.renderItem(b.icon(), left + b.x(), top + b.y());
			if (hover) {
				g.setTooltipForNextFrame(font, Component.literal("/" + b.command()), mx, my);
			}
		}
	}
}
