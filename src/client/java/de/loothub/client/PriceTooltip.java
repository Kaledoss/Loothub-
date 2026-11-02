package de.loothub.client;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

import de.loothub.Loothub;

/** Hängt die Marktpreise unten an den Item-Tooltip (Inventar, Kisten, Markt-Menü …). */
public final class PriceTooltip {
	private PriceTooltip() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			try {
				append(stack, lines);
			} catch (RuntimeException e) {
				Loothub.LOGGER.debug("Tooltip fehlgeschlagen", e);
			}
		});
	}

	private static void append(ItemStack stack, List<Component> lines) {
		if (stack.isEmpty() || !de.loothub.client.jobs.WidgetConfig.get().tooltipPrices) {
			return;
		}
		// Umbenannte Items und Sonder-/OP-Items (eigenes Modell) werden nicht am Marktplatz gehandelt
		if (stack.get(DataComponents.CUSTOM_NAME) != null || stack.get(DataComponents.CUSTOM_MODEL_DATA) != null) {
			return;
		}

		MarketPrices.refreshIfStale();

		if (!MarketPrices.hasData()) {
			if (MarketPrices.isLoading()) {
				lines.add(Component.translatable("loothub.tooltip.loading").withStyle(ChatFormatting.DARK_GRAY));
			} else if (MarketPrices.lastAttemptFailed()) {
				lines.add(Component.translatable("loothub.tooltip.error").withStyle(ChatFormatting.DARK_RED));
			}
			return;
		}

		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		MarketPrices.Entry entry = MarketPrices.get(itemId);
		if (entry == null) {
			return; // wird am Marktplatz nicht gehandelt
		}

		int count = stack.getCount();
		lines.add(Component.empty());
		lines.add(Component.translatable("loothub.tooltip.header").withStyle(ChatFormatting.GOLD));
		// Laut API: BUY = Preis beim Kaufen (günstigstes Angebot), SELL = Preis beim Verkaufen (bester Kaufauftrag)
		lines.add(sideLine("loothub.tooltip.buy", entry.buy(), count, ChatFormatting.GOLD));
		lines.add(sideLine("loothub.tooltip.sell", entry.sell(), count, ChatFormatting.GREEN));
		lines.add(ageLine());
	}

	private static Component sideLine(String labelKey, MarketPrices.Side side, int count, ChatFormatting color) {
		MutableComponent line = Component.literal("  ")
				.append(Component.translatable(labelKey).withStyle(ChatFormatting.GRAY))
				.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));

		if (!side.available()) {
			return line.append(Component.translatable("loothub.tooltip.none").withStyle(ChatFormatting.DARK_GRAY));
		}

		line.append(Component.literal(money(side.price())).withStyle(color));
		if (count > 1) {
			line.append(Component.literal(" (" + count + "× = " + money(side.price() * count) + ")")
					.withStyle(ChatFormatting.DARK_GRAY));
		}
		line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.translatable("loothub.tooltip.orders", side.activeOrders())
						.withStyle(ChatFormatting.DARK_GRAY));
		return line;
	}

	private static Component ageLine() {
		long minutes = (System.currentTimeMillis() - MarketPrices.lastUpdate()) / 60_000;
		Component age = minutes < 1
				? Component.translatable("loothub.tooltip.age.now")
				: Component.translatable("loothub.tooltip.age.minutes", minutes);
		return Component.literal("  ").append(age).withStyle(ChatFormatting.DARK_GRAY);
	}

	private static String money(double value) {
		return Fmt.money(value);
	}
}
