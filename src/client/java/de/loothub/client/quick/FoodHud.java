package de.loothub.client.quick;

import de.loothub.client.jobs.WidgetConfig;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Sättigung sichtbar machen (wie AppleSkin, im Loothub-Look): unter jeder Hungerkeule ein goldener Strich für die
 * Sättigung; hält man Essen in der Hand, leuchten die Keulen (und die Sättigung), die es auffüllen würde. Dazu im
 * Tooltip von Essen: Hunger und Sättigung.
 */
public final class FoodHud {
	private static final int GOLD = 0xFFFFC93B, GOLD_DIM = 0x90FFC93B, GLOW = 0xFFFFE9A8;

	private FoodHud() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!WidgetConfig.get().foodHud) {
				return;
			}
			FoodProperties fp = stack.get(DataComponents.FOOD);
			if (fp == null) {
				return;
			}
			lines.add(Component.literal("🍗 +" + fp.nutrition() + "  ").withStyle(ChatFormatting.GOLD)
					.append(Component.translatable("loothub.food.tip", String.format(java.util.Locale.ROOT, "%.1f", fp.saturation()))
							.withStyle(ChatFormatting.YELLOW)));
		});
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Player p = mc.player;
		if (!WidgetConfig.get().foodHud || p == null || mc.options.hideGui || mc.gameMode == null
				|| !mc.gameMode.canHurtPlayer()) {
			return;
		}
		if (p.getVehicle() instanceof LivingEntity) {
			return; // auf einem Reittier zeigt Minecraft dessen Herzen statt der Keulen
		}
		FoodData food = p.getFoodData();
		int level = food.getFoodLevel();
		float sat = food.getSaturationLevel();
		int right = g.guiWidth() / 2 + 91;
		int y = g.guiHeight() - 39;

		// Vorschau: Essen in der Hand
		ItemStack hand = p.getMainHandItem().has(DataComponents.FOOD) ? p.getMainHandItem() : p.getOffhandItem();
		FoodProperties fp = hand.get(DataComponents.FOOD);
		int newLevel = level;
		float newSat = sat;
		if (fp != null) {
			newLevel = Math.min(20, level + fp.nutrition());
			newSat = Math.min(newLevel, sat + fp.saturation());
		}
		float pulse = 0.45F + 0.35F * (float) Math.sin(System.currentTimeMillis() / 160.0);

		for (int i = 0; i < 10; i++) {
			int x = right - i * 8 - 9;
			// Keulen, die das Essen auffüllen würde: sanft aufleuchten
			int from = i * 2, to = i * 2 + 2;
			if (fp != null && newLevel > level && newLevel > from && level < to) {
				g.fill(x + 1, y + 1, x + 8, y + 8, ((int) (pulse * 140) << 24) | (GLOW & 0xFFFFFF));
			}
			// Sättigung: Strich unter der Keule (2 Punkte Sättigung = voller Strich)
			float have = clamp(sat - from, 0F, 2F) / 2F;
			float will = clamp(newSat - from, 0F, 2F) / 2F;
			int by = y + 9;
			g.fill(x + 1, by, x + 8, by + 1, 0x40000000);
			if (will > have) {
				g.fill(x + 1, by, x + 1 + Math.round(7 * will), by + 1, ((int) (pulse * 255) << 24) | (GOLD_DIM & 0xFFFFFF));
			}
			if (have > 0) {
				g.fill(x + 1, by, x + 1 + Math.max(1, Math.round(7 * have)), by + 1, GOLD);
			}
		}
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
