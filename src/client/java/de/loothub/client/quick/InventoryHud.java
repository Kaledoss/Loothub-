package de.loothub.client.quick;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Inventar (die 27 Plätze ohne Hotbar) dauerhaft über der Hotbar anzeigen. Hintergrundfarbe und
 * Deckkraft sind einstellbar. Reine Anzeige.
 */
public final class InventoryHud {
	private static final int CELL = 18;

	private InventoryHud() {
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		WidgetConfig c = WidgetConfig.get();
		if (!c.invHudEnabled || mc.player == null || mc.options.hideGui || mc.screen != null) {
			return;
		}
		Inventory inv = mc.player.getInventory();
		int w = 9 * CELL + 2;
		int h = 3 * CELL + 2;
		int x = (g.guiWidth() - w) / 2;
		// über Hotbar, Herzen/Hunger und Rüstung
		int y = g.guiHeight() - 22 - 39 - h - 2;
		int alpha = (int) Math.round(Math.max(0, Math.min(1, c.invHudOpacity)) * 255);
		int bg = (alpha << 24) | (c.invHudColor & 0xFFFFFF);
		int slot = ((alpha * 2 / 3) << 24) | (darker(c.invHudColor) & 0xFFFFFF);
		g.fill(x, y, x + w, y + h, bg);
		for (int i = 0; i < 27; i++) {
			int cx = x + 1 + (i % 9) * CELL;
			int cy = y + 1 + (i / 9) * CELL;
			g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, slot);
			ItemStack st = inv.getItem(9 + i);
			if (!st.isEmpty()) {
				g.renderItem(st, cx + 1, cy + 1);
				g.renderItemDecorations(mc.font, st, cx + 1, cy + 1);
			}
		}
	}

	private static int darker(int rgb) {
		int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		return ((r * 6 / 10) << 16) | ((gr * 6 / 10) << 8) | (b * 6 / 10);
	}
}
