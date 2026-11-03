package de.loothub.client.quick;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.MarketPrices;
import de.loothub.client.ShardValue;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Feld links neben dem Inventar: Wert des eigenen Inventars am Marktplatz (Verkaufspreis)
 * und beim Wertstoffhändler (OPShards). Reine Anzeige.
 */
public final class InventoryValue {
	private static final int W = 112;
	private static final int PAD = 5;

	private static long lastCalc;
	private static double market, shards;

	private InventoryValue() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof InventoryScreen && WidgetConfig.get().inventoryValue) {
				ScreenEvents.afterBackground(screen).register((s, g, mx, my, d) -> render(s, g));
			}
		});
	}

	private static void recalc() {
		long now = System.currentTimeMillis();
		if (now - lastCalc < 500) {
			return;
		}
		lastCalc = now;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		MarketPrices.refreshIfStale();
		double m = 0, sh = 0;
		Inventory inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (st.isEmpty()) {
				continue;
			}
			int n = st.getCount();
			double shard = ShardValue.perItem(st);
			if (!Double.isNaN(shard)) {
				sh += shard * n;
			}
			if (st.get(DataComponents.CUSTOM_NAME) != null || st.get(DataComponents.CUSTOM_MODEL_DATA) != null) {
				continue;
			}
			String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
			MarketPrices.Entry e = MarketPrices.get(id);
			if (e != null && e.sell().available()) {
				m += e.sell().price() * n;
			}
		}
		market = m;
		shards = sh;
	}

	private static void render(Screen s, GuiGraphics g) {
		recalc();
		Font font = Minecraft.getInstance().font;
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
		int left = acc.loothub$leftPos();
		int centered = (s.width - acc.loothub$imageWidth()) / 2;
		boolean recipeOpen = left > centered + 10;
		int x = (recipeOpen ? left - 149 : left) - W - 6;
		int y = acc.loothub$topPos();
		if (x < 2) {
			return; // kein Platz
		}
		int h = 3 * 11 + 2 * PAD - 1;
		g.fill(x, y, x + W, y + h, 0xE0140B0D);
		Pulse.outline(g, x, y, W, h);
		int ty = y + PAD;
		g.drawString(font, "Inventarwert", x + PAD, ty, LoothubScreen.RED, false);
		ty += 12;
		row(g, font, x, ty, "Markt", Fmt.compact(market) + " $", 0xFF55E06A);
		ty += 11;
		row(g, font, x, ty, "Shards", shards > 0 ? Fmt.compact(shards) : "–", 0xFF55D5E0);
	}

	private static void row(GuiGraphics g, Font font, int x, int y, String label, String value, int color) {
		g.drawString(font, label, x + PAD, y, LoothubScreen.MUTED, false);
		g.drawString(font, value, x + W - PAD - font.width(value), y, color, false);
	}
}
