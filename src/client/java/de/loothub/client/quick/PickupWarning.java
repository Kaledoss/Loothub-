package de.loothub.client.quick;

import de.loothub.client.jobs.WidgetConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Warnung, wenn ein Item direkt neben einem liegt, aber nicht aufgesammelt werden kann, weil das Inventar voll ist
 * (kein freier Platz und kein passender Stapel mit Platz). Höchstens alle paar Sekunden.
 */
public final class PickupWarning {
	private static final long COOLDOWN_MS = 6000;
	/** So lange muss das Item schon in Reichweite liegen (frisch gedroppte Items haben eine kurze Aufsammel-Sperre). */
	private static final int WAIT_TICKS = 15;
	private static long lastWarn;
	private static final long SHOW_MS = 3500;
	private static ItemStack shown = ItemStack.EMPTY;
	private static long until;
	private static int stuckTicks;

	private PickupWarning() {
	}

	public static void register() {
		de.loothub.client.util.Guard.tick("PickupWarning", PickupWarning::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || !WidgetConfig.get().pickupWarning || mc.player.isSpectator()) {
			stuckTicks = 0;
			return;
		}
		Inventory inv = mc.player.getInventory();
		if (inv.getFreeSlot() >= 0) {
			stuckTicks = 0; // noch Platz frei: alles passt
			return;
		}
		ItemStack blocked = null;
		for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(1.0, 0.5, 1.0))) {
			ItemStack s = e.getItem();
			if (!s.isEmpty() && !fits(inv, s)) {
				blocked = s;
				break;
			}
		}
		if (blocked == null) {
			stuckTicks = 0;
			return;
		}
		if (++stuckTicks < WAIT_TICKS) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastWarn < COOLDOWN_MS) {
			return;
		}
		lastWarn = now;
		shown = blocked.copyWithCount(1);
		until = now + SHOW_MS;
		de.loothub.client.Sounds.play(de.loothub.client.Sounds.Group.PROTECT, SoundEvents.NOTE_BLOCK_BASS.value(), 0.7F, 0.6F);
	}

	/** Einblendung wie die Bau-Warnung „Block fehlt“ (gleicher Stil, gleiche Stelle): ⚠ Inventar voll + Item. */
	public static void render(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (shown.isEmpty() || now > until || mc.options.hideGui) {
			return;
		}
		String title = "⚠ " + Component.translatable("loothub.pickup_warning.title").getString();
		String sub = Component.translatable("loothub.pickup_warning.sub", shown.getHoverName()).getString();
		int[] size = de.loothub.client.streamer.Alerts.boxSize(mc.font, title, sub, true);
		int[] p = de.loothub.client.bau.EasyPlaceWarning.pos(g.guiWidth(), g.guiHeight(), size[0], size[1]);
		long age = SHOW_MS - (until - now);
		float a = age < 200 ? age / 200F : until - now < 400 ? (until - now) / 400F : 1F;
		a = Math.max(0F, Math.min(1F, a));
		int y = p[1] + (int) ((1 - Math.min(1F, age / 200F)) * -12);
		de.loothub.client.streamer.Alerts.drawBox(g, mc.font, title, sub, 0xFFE23B3B, p[0], y, a, shown);
	}

	/** Passt das Item noch auf einen vorhandenen Stapel? */
	private static boolean fits(Inventory inv, ItemStack s) {
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack have = inv.getItem(i);
			if (ItemStack.isSameItemSameComponents(have, s) && have.getCount() < have.getMaxStackSize()) {
				return true;
			}
		}
		ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
		return ItemStack.isSameItemSameComponents(off, s) && off.getCount() < off.getMaxStackSize();
	}
}
