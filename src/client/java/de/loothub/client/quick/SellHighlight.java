package de.loothub.client.quick;

import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.MarketPrices;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * In Markt-Menüs des Servers (Titel enthält „Markt“ oder „verkaufen“) werden alle Slots mit Items, die man
 * laut Marktplatz verkaufen kann (aktive Kaufaufträge), grün hinterlegt – auch im eigenen Inventar darunter.
 * Reine Anzeige; aufgerufen von {@code ContainerScreenMixin} direkt nach der Menü-Textur, also unter den Items.
 */
public final class SellHighlight {
	private static final int FILL = 0x6036C25A;
	private static final int EDGE = 0xB046E070;

	private SellHighlight() {
	}

	public static boolean isMarketMenu(ContainerScreen screen) {
		String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
		return title.contains("markt") || title.contains("verkauf");
	}

	public static void draw(ContainerScreen screen, GuiGraphics g) {
		if (!WidgetConfig.get().sellHighlight || !isMarketMenu(screen)) {
			return;
		}
		MarketPrices.refreshIfStale();
		if (!MarketPrices.hasData()) {
			return;
		}
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int left = acc.loothub$leftPos();
		int top = acc.loothub$topPos();
		for (Slot slot : screen.getMenu().slots) {
			if (!slot.isActive() || !sellable(slot.getItem())) {
				continue;
			}
			int x = left + slot.x;
			int y = top + slot.y;
			g.fill(x, y, x + 16, y + 16, FILL);
			g.fill(x, y + 15, x + 16, y + 16, EDGE);
		}
	}

	private static boolean sellable(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		// Umbenannte Items und Menü-/Sonder-Items (eigenes Modell) handelt der Marktplatz nicht
		if (stack.get(DataComponents.CUSTOM_NAME) != null || stack.get(DataComponents.CUSTOM_MODEL_DATA) != null) {
			return false;
		}
		MarketPrices.Entry entry = MarketPrices.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		return entry != null && entry.sell().available();
	}
}
