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
			// Pets: über der Zweithand, links neben der Überschrift „Loothub+“
			new IconButton(79, 4, new ItemStack(Items.BONE), "pets"),
			new IconButton(130, 62, new ItemStack(Items.CRAFTING_TABLE), "werkbank"),
			new IconButton(150, 62, new ItemStack(Items.ANVIL), "anvil"),
	};

	private InventoryTitle() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			// Kisten, Server-Menüs (z. B. OPSUCHT), Werkbank und Amboss: nur der pulsierende Rand
			if (screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen
					|| screen instanceof net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen
					|| screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen
					|| screen instanceof net.minecraft.client.gui.screens.inventory.AnvilScreen) {
				ScreenEvents.afterBackground(screen).register((s, g, mx, my, delta) -> {
					AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
					Pulse.outline(g, acc.loothub$leftPos(), acc.loothub$topPos(), acc.loothub$imageWidth(), acc.loothub$imageHeight());
				});
				return;
			}
			if (!(screen instanceof InventoryScreen)) {
				return;
			}
			// Pulsierender Schein um Inventar und – falls offen – Rezeptbuch (liegt 149 px links, 147×166 groß).
			// Im Hintergrund gezeichnet, damit die Rezeptbuch-Reiter darüber liegen.
			ScreenEvents.afterBackground(screen).register((s, g, mx, my, delta) -> {
				AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
				int left = acc.loothub$leftPos();
				int imgW = acc.loothub$imageWidth();
				Pulse.outline(g, left, acc.loothub$topPos(), imgW, acc.loothub$imageHeight());
				if (left > (s.width - imgW) / 2 + 10) {
					Pulse.outline(g, left - 149, acc.loothub$topPos(), 147, 166);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> {
				if (e.button() != 0 || !de.loothub.client.jobs.WidgetConfig.get().inventoryDesign) {
					return true;
				}
				AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
				for (IconButton b : BUTTONS) {
					if (b.contains(e.x(), e.y(), acc.loothub$leftPos(), acc.loothub$topPos())) {
						CommandGuard.send(b.command(), "Inventar-Knopf");
						return false; // Klick nicht ans Inventar weitergeben
					}
				}
				return true;
			});
		});
	}

	/** Aufgerufen von {@code InventoryScreenMixin} direkt nach der Inventar-Textur (unter Items und Tooltips). */
	public static void drawOverlay(InventoryScreen screen, GuiGraphics g, int mx, int my) {
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int left = acc.loothub$leftPos();
		int top = acc.loothub$topPos();
		drawButtons(g, Minecraft.getInstance().font, left, top, mx, my);
	}

	/** Überschrift im Inventar (Koordinaten relativ zum Inventar, wie in renderLabels). */
	public static void drawLabel(GuiGraphics g) {
		g.drawString(Minecraft.getInstance().font, "Loothub+", 97, 8, LoothubScreen.RED, false);
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
