package de.loothub.client.quick;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;


import de.loothub.client.MarketPrices;
import de.loothub.client.ShardValue;

/**
 * Wert des eigenen Inventars am Marktplatz (Verkaufspreis) und beim Wertstoffhändler (OPShards)
 * – angezeigt im Job-Widget.
 */
public final class InventoryValue {

	private static long lastCalc;
	private static double market, shards;

	private InventoryValue() {
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
		Inventory inv = mc.player.getInventory();
		java.util.List<ItemStack> items = new java.util.ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			items.add(inv.getItem(i));
		}
		double[] v = valueOf(items);
		double m = v[0], sh = v[1];
		market = m;
		shards = sh;
	}

	/** {Marktwert (Verkaufspreis), OPShards beim Wertstoffhändler} für beliebige Items, z. B. Kisteninhalt. */
	public static double[] valueOf(Iterable<ItemStack> items) {
		MarketPrices.refreshIfStale();
		double m = 0, sh = 0;
		for (ItemStack st : items) {
			if (st == null || st.isEmpty()) {
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
		return new double[]{m, sh};
	}

	/** Marktwert des Inventars (Verkaufspreis), höchstens alle 0,5 s neu berechnet. */
	public static double market() {
		recalc();
		return market;
	}

	/** OPShards, die der Wertstoffhändler für das Inventar gäbe. */
	public static double shards() {
		recalc();
		return shards;
	}
}
