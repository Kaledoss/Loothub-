package de.loothub.client;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import de.loothub.client.jobs.WidgetConfig;

/**
 * „Tauschitem des Tages“ beim Rohstoffhändler: steht nicht in der OPSUCHT-API, deshalb liest die Mod es aus dem
 * Händler-Menü mit, sobald es geöffnet ist (Item mit „Tauschitem des Tages“ in der Beschreibung). Nur Mitlesen.
 */
public final class DailyMerchantItem {
	/** „1x Grasblock wird zu 0,5 OPShards“ */
	private static final Pattern RATE = Pattern.compile("(?i)(\\d+)\\s*x\\s+(.+?)\\s+wird\\s+zu\\s+([\\d.,]+)\\s+(\\S+)");
	/** „Tageslimit: 2.500/2.500 Items“ */
	private static final Pattern LIMIT = Pattern.compile("(?i)tageslimit:?\\s*([\\d.]+)\\s*/\\s*([\\d.]+)");

	private static int ticks;

	private DailyMerchantItem() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (++ticks % 10 != 0 || !(mc.screen instanceof AbstractContainerScreen<?> screen) || mc.player == null) {
				return;
			}
			for (Slot slot : screen.getMenu().slots) {
				if (slot.container == mc.player.getInventory()) {
					continue;
				}
				if (read(slot.getItem())) {
					return;
				}
			}
		});
	}

	/** Ist das das Tauschitem des Tages? Dann Daten übernehmen. */
	private static boolean read(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) {
			return false;
		}
		boolean daily = false;
		String name = null;
		double rate = Double.NaN;
		String currency = null;
		long used = -1;
		long max = -1;
		for (Component line : lore.lines()) {
			String t = line.getString().strip();
			String low = t.toLowerCase(Locale.ROOT);
			if (low.contains("tauschitem des tages")) {
				daily = true;
			}
			Matcher m = RATE.matcher(t);
			if (m.find()) {
				int amount = Math.max(1, Integer.parseInt(m.group(1)));
				name = m.group(2).strip();
				rate = parseNumber(m.group(3)) / amount;
				currency = m.group(4).strip();
			}
			Matcher l = LIMIT.matcher(t);
			if (l.find()) {
				used = (long) parseNumber(l.group(1));
				max = (long) parseNumber(l.group(2));
			}
		}
		if (!daily) {
			return false;
		}
		if (name == null) {
			name = stack.getHoverName().getString().replaceFirst("(?i)\\s*eintauschen\\s*$", "").strip();
		}
		WidgetConfig c = WidgetConfig.get();
		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		String today = LocalDate.now().toString();
		boolean changed = !today.equals(c.dailyItemDate) || !itemId.equals(c.dailyItemId) || c.dailyItemUsed != used
				|| Double.compare(c.dailyItemRate, Double.isNaN(rate) ? 0 : rate) != 0;
		c.dailyItemDate = today;
		c.dailyItemId = itemId;
		c.dailyItemName = name;
		c.dailyItemRate = Double.isNaN(rate) ? 0 : rate;
		c.dailyItemCurrency = currency == null ? "" : currency;
		c.dailyItemUsed = used;
		c.dailyItemLimit = max;
		if (changed) {
			c.save();
		}
		return true;
	}

	/** „2.500“ → 2500, „0,5“ → 0.5 */
	private static double parseNumber(String s) {
		String t = s.strip();
		if (t.contains(",")) {
			t = t.replace(".", "").replace(',', '.');
		} else if (t.matches("\\d{1,3}(\\.\\d{3})+")) {
			t = t.replace(".", "");
		}
		try {
			return Double.parseDouble(t);
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

	/** Wurde das Tagesitem heute schon gesehen? */
	public static boolean isToday() {
		return LocalDate.now().toString().equals(WidgetConfig.get().dailyItemDate);
	}

	public static boolean known() {
		WidgetConfig c = WidgetConfig.get();
		return c.dailyItemId != null && !c.dailyItemId.isEmpty();
	}

	static String currencyLabel(String c) {
		if (c == null) {
			return "";
		}
		String l = c.toLowerCase(Locale.ROOT);
		return l.contains("shard") ? "OPShards" : l.contains("coin") ? "RedCoins" : c;
	}
}
