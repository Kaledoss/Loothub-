package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.Fmt;
import de.loothub.client.Theme;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Marktwert des Inhalts neben echten Kisten, Fässern und Shulkerkisten (nicht in Server-Menüs): Verkaufspreis am
 * Marktplatz und OPShards beim Wertstoffhändler. Nur Anzeige.
 */
public final class ContainerValue {
	private static final int PAD = 5;

	private ContainerValue() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen) || !isRealContainer(screen)) {
				return;
			}
			ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> render((AbstractContainerScreen<?>) s, g));
		});
	}

	/** Echte Kiste/Fass/Shulker: Titel ist der Minecraft-Standardname („Kiste“, „Fass“ …), kein Server-Menü. */
	private static boolean isRealContainer(Screen screen) {
		return screen.getTitle().getContents() instanceof TranslatableContents t && t.getKey().startsWith("container.");
	}

	private static void render(AbstractContainerScreen<?> screen, GuiGraphics g) {
		if (!WidgetConfig.get().containerValue) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		List<ItemStack> items = new ArrayList<>();
		for (Slot slot : screen.getMenu().slots) {
			if (slot.container != mc.player.getInventory()) {
				items.add(slot.getItem());
			}
		}
		double[] v = InventoryValue.valueOf(items);
		Font font = mc.font;
		String title = "Marktwert";
		String market = Fmt.exact(v[0]) + " $";
		String shards = v[1] > 0 ? Fmt.exact(v[1]) + " OPShards" : null;
		int w = Math.max(font.width(title), Math.max(font.width(market), shards == null ? 0 : font.width(shards))) + 2 * PAD;
		int h = PAD + 10 + 10 + (shards == null ? 0 : 10) + PAD - 2;
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int x = acc.loothub$leftPos() - w - 6;
		int y = acc.loothub$topPos();
		if (x < 2) {
			return; // kein Platz links
		}
		g.fill(x, y, x + w, y + h, Theme.bg(0xE0140B0D));
		Pulse.outline(g, x, y, w, h);
		g.drawString(font, title, x + PAD, y + PAD, 0xFFB9A9AB, false);
		g.drawString(font, market, x + PAD, y + PAD + 10, 0xFF55E06A, false);
		if (shards != null) {
			g.drawString(font, shards, x + PAD, y + PAD + 20, 0xFF55D5E0, false);
		}
	}
}
