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
				// Nur einmal melden – der Tooltip wird jedes Bild neu gebaut und würde sonst das Log fluten
				if (!loggedError) {
					loggedError = true;
					Loothub.LOGGER.warn("Tooltip fehlgeschlagen (weitere Meldungen unterdrückt)", e);
				}
			}
		});
	}

	private static boolean loggedError;

	private static void append(ItemStack stack, List<Component> lines) {
		if (stack.isEmpty() || !de.loothub.client.jobs.WidgetConfig.get().tooltipPrices) {
			return;
		}
		// Umbenannte Items und Sonder-/OP-Items (eigenes Modell) werden nicht am Marktplatz gehandelt
		if (stack.get(DataComponents.CUSTOM_NAME) != null || stack.get(DataComponents.CUSTOM_MODEL_DATA) != null) {
			// Sonderitems (z. B. Gräbergemisch) haben keinen Marktpreis, aber evtl. einen Shard-Wert
			double special = ShardValue.perItem(stack);
			if (!Double.isNaN(special)) {
				lines.add(Component.empty());
				lines.add(Component.empty().append(Component.translatable("loothub.tooltip.shards").withStyle(ChatFormatting.GRAY))
						.append(Component.literal(Fmt.exact(special * stack.getCount()) + " OPShards").withStyle(ChatFormatting.AQUA)));
			}
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
		// Normale Handelswaren (z. B. Leder) haben einen Marktplatz-Preis – dann kein Auktionshaus-Block
		AuctionPrices.Now ah = entry != null ? null : AuctionPrices.get(itemId);
		if (entry == null && ah == null) {
			return; // weder am Marktplatz noch im Auktionshaus
		}

		int count = stack.getCount();
		if (entry != null) {
			lines.add(Component.empty());
			lines.add(Component.translatable("loothub.tooltip.header").withStyle(ChatFormatting.GOLD));
			// Laut API: BUY = Preis beim Kaufen (günstigstes Angebot), SELL = Preis beim Verkaufen (bester Kaufauftrag)
			lines.add(sideLine("loothub.tooltip.buy", entry.buy(), count, ChatFormatting.GOLD));
			lines.add(sideLine("loothub.tooltip.sell", entry.sell(), count, ChatFormatting.GREEN));
		}
		if (ah != null) {
			if (entry == null) {
				lines.add(Component.empty());
			}
			lines.add(Component.translatable("loothub.tooltip.ah.header").withStyle(ChatFormatting.LIGHT_PURPLE));
			MutableComponent l = Component.literal("  ")
					.append(Component.translatable("loothub.tooltip.ah.from").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(money(ah.minPerUnit())).withStyle(ChatFormatting.LIGHT_PURPLE));
			if (count > 1) {
				l.append(Component.literal(" (" + count + "× = " + money(ah.minPerUnit() * count) + ")").withStyle(ChatFormatting.DARK_GRAY));
			}
			l.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.translatable("loothub.tooltip.ah.offers", ah.offers()).withStyle(ChatFormatting.DARK_GRAY));
			lines.add(l);
			double w1 = AuctionPrices.average(itemId, 7);
			double w4 = AuctionPrices.average(itemId, 28);
			if (!Double.isNaN(w1) || !Double.isNaN(w4)) {
				MutableComponent t = Component.literal("  ").append(Component.translatable("loothub.tooltip.ah.avg").withStyle(ChatFormatting.GRAY));
				t.append(Component.literal(Double.isNaN(w1) ? "–" : money(w1)).withStyle(ChatFormatting.WHITE))
						.append(Component.literal(" (7 T) · ").withStyle(ChatFormatting.DARK_GRAY))
						.append(Component.literal(Double.isNaN(w4) ? "–" : money(w4)).withStyle(ChatFormatting.WHITE))
						.append(Component.literal(" (4 W)").withStyle(ChatFormatting.DARK_GRAY));
				lines.add(t);
			}
		}
		double shards = ShardValue.perItem(stack);
		if (!Double.isNaN(shards)) {
			lines.add(Component.empty().append(Component.translatable("loothub.tooltip.shards").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(Fmt.exact(shards * count) + " OPShards").withStyle(ChatFormatting.AQUA)));
		}
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
