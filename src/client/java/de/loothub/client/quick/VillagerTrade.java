package de.loothub.client.quick;

import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;
import de.loothub.client.mixin.MerchantScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * Dorfbewohner halb automatisch: Knopf „Max handeln“ im Handelsfenster. Ein Klick wiederholt den gewählten Handel so
 * oft, wie Bezahlung im Inventar, Vorrat des Dorfbewohners und Platz reichen – wie von Hand: Handel wählen (füllt die
 * Bezahl-Felder), Ergebnis mit Shift-Klick nehmen, nächster Durchgang. Ein Durchgang alle paar Ticks.
 */
public final class VillagerTrade {
	private static final int MAX_TRADES = 64, EVERY_TICKS = 3;
	private static int trade = -1, done, wait, stuck;

	private VillagerTrade() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof MerchantScreen ms) || !WidgetConfig.get().villagerTrade) {
				return;
			}
			trade = -1;
			var acc = (AbstractContainerScreenAccessor) ms;
			int x = acc.loothub$leftPos() + acc.loothub$imageWidth() - 74, y = acc.loothub$topPos() - 18;
			Button b = Button.builder(Component.translatable("loothub.villager.max"), btn -> start(ms)).bounds(x, y, 74, 16).build();
			b.setTooltip(Tooltip.create(Component.translatable("loothub.villager.max.tooltip")));
			Screens.getButtons(screen).add(b);
		});
		ClientTickEvents.END_CLIENT_TICK.register(VillagerTrade::tick);
	}

	private static void start(MerchantScreen ms) {
		trade = ((MerchantScreenAccessor) ms).loothub$shopItem();
		done = 0;
		wait = 0;
		stuck = 0;
	}

	private static void tick(Minecraft mc) {
		if (trade < 0) {
			return;
		}
		if (!(mc.screen instanceof MerchantScreen ms) || mc.player == null || mc.gameMode == null || mc.getConnection() == null) {
			trade = -1;
			return;
		}
		if (++wait < EVERY_TICKS) {
			return;
		}
		wait = 0;
		MerchantMenu menu = ms.getMenu();
		if (trade >= menu.getOffers().size() || done >= MAX_TRADES) {
			stop(mc);
			return;
		}
		MerchantOffer offer = menu.getOffers().get(trade);
		if (offer.isOutOfStock()) {
			stop(mc);
			return;
		}
		if (menu.getSlot(2).getItem().isEmpty()) {
			// Bezahlung in die Felder legen (wie ein Klick auf den Handel)
			if (++stuck > 3) {
				stop(mc); // keine Bezahlung mehr im Inventar
				return;
			}
			menu.setSelectionHint(trade);
			menu.tryMoveItems(trade);
			mc.getConnection().send(new ServerboundSelectTradePacket(trade));
			return;
		}
		int before = menu.getSlot(2).getItem().getCount();
		mc.gameMode.handleInventoryMouseClick(menu.containerId, 2, 0, ClickType.QUICK_MOVE, mc.player);
		if (!menu.getSlot(2).getItem().isEmpty() && menu.getSlot(2).getItem().getCount() == before && ++stuck > 3) {
			stop(mc); // Inventar voll
			return;
		}
		stuck = 0;
		done++;
	}

	private static void stop(Minecraft mc) {
		if (done > 0 && mc.player != null) {
			mc.gui.setOverlayMessage(Component.translatable("loothub.villager.done", done), false);
		}
		trade = -1;
	}
}
