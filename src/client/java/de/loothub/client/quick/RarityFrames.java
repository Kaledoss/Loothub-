package de.loothub.client.quick;

import java.util.Map;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Seltenheits-Rahmen: Items mit „Seltenheit » …“ im Tooltip (z. B. Legendär) bekommen im Inventar einen Rahmen in
 * genau der Farbe, in der OPSUCHT die Seltenheit schreibt. Nur Anzeige, unter den Items gezeichnet.
 */
public final class RarityFrames {
	private static final int NONE = 0;
	/** Farbe je Item-Objekt merken (Tooltip nur einmal auswerten). */
	private static final Map<ItemStack, Integer> CACHE = new WeakHashMap<>();

	private RarityFrames() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof AbstractContainerScreen<?> acs) {
				ScreenEvents.afterBackground(screen).register((s, g, mx, my, d) -> draw(acs, g));
			}
		});
	}

	private static void draw(AbstractContainerScreen<?> screen, GuiGraphics g) {
		if (!WidgetConfig.get().rarityFrames) {
			return;
		}
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int left = acc.loothub$leftPos(), top = acc.loothub$topPos();
		for (Slot slot : screen.getMenu().slots) {
			if (!slot.isActive()) {
				continue;
			}
			int rgb = rarityColor(slot.getItem());
			if (rgb == NONE) {
				continue;
			}
			int x = left + slot.x, y = top + slot.y;
			int edge = 0xE0000000 | rgb, fill = 0x30000000 | rgb;
			g.fill(x, y, x + 16, y + 16, fill);
			g.fill(x - 1, y - 1, x + 17, y, edge);
			g.fill(x - 1, y + 16, x + 17, y + 17, edge);
			g.fill(x - 1, y, x, y + 16, edge);
			g.fill(x + 16, y, x + 17, y + 16, edge);
		}
	}

	/** Farbe der Seltenheit aus der Beschreibung, oder 0. */
	private static int rarityColor(ItemStack stack) {
		if (stack.isEmpty()) {
			return NONE;
		}
		Integer cached = CACHE.get(stack);
		if (cached != null) {
			return cached;
		}
		int color = NONE;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			for (Component line : lore.lines()) {
				if (!line.getString().toLowerCase(java.util.Locale.ROOT).contains("seltenheit")) {
					continue;
				}
				// letzte farbige Stelle der Zeile = die Seltenheit („Seltenheit » Legendär“)
				int[] found = {NONE};
				boolean[] after = {false};
				line.visit((style, part) -> {
					if (part.contains("»")) {
						after[0] = true;
					}
					TextColor tc = style.getColor();
					if (after[0] && tc != null && !part.isBlank() && !part.strip().equals("»")) {
						found[0] = tc.getValue() & 0xFFFFFF;
					}
					return java.util.Optional.empty();
				}, net.minecraft.network.chat.Style.EMPTY);
				color = found[0];
				break;
			}
		}
		CACHE.put(stack, color);
		return color;
	}
}
