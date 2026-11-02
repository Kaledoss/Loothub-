package de.loothub.client.quick;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import de.loothub.client.LoothubScreen;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Ersetzt im Spieler-Inventar die Überschrift „Crafting/Handwerk“ optisch durch „Loothub+“.
 * Die Werkbank und andere Menüs bleiben unverändert.
 */
public final class InventoryTitle {
	/** Gleiche Fläche wie das helle Schild in der Inventar-Textur. */
	private static final int X1 = 95, Y1 = 5, X2 = 161, Y2 = 15;

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
			});
		});
	}
}
