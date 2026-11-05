package de.loothub.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Was der Wertstoffhändler für ein Item gibt (OPShards pro Stück), aus {@link MerchantRates}. */
public final class ShardValue {
	private ShardValue() {
	}

	/** OPShards pro Stück, oder NaN, wenn der Händler das Item nicht annimmt. */
	private static Object indexedFrom;
	private static java.util.Map<String, java.util.List<MerchantRates.Rate>> index = java.util.Map.of();

	/** OPShards-Kurse nach Item-ID (neu aufgebaut, sobald sich die Kursliste ändert). */
	private static java.util.Map<String, java.util.List<MerchantRates.Rate>> index() {
		var rates = MerchantRates.rates();
		if (rates != indexedFrom) {
			java.util.Map<String, java.util.List<MerchantRates.Rate>> m = new java.util.HashMap<>();
			for (MerchantRates.Rate r : rates) {
				if (r.targetLabel().equals("OPShards")) {
					m.computeIfAbsent(r.itemId(), k -> new java.util.ArrayList<>()).add(r);
				}
			}
			index = m;
			indexedFrom = rates;
		}
		return index;
	}

	public static double perItem(ItemStack stack) {
		if (stack.isEmpty()) {
			return Double.NaN;
		}
		MerchantRates.refreshIfStale();
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		Component custom = stack.get(DataComponents.CUSTOM_NAME);
		String customName = custom == null ? null : custom.getString().strip();
		for (MerchantRates.Rate r : index().getOrDefault(id, java.util.List.of())) {
			boolean match = r.name() == null ? customName == null : r.name().strip().equalsIgnoreCase(customName == null ? "" : customName);
			if (match && !Double.isNaN(r.rate())) {
				return r.rate();
			}
		}
		return Double.NaN;
	}
}
