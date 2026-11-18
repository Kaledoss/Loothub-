package de.loothub.client.bau;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.Sounds;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Easy Place (Litematica) bzw. der Litematica-Printer findet einen Block nicht im Inventar: deutlich anzeigen, welcher
 * fehlt – statt dass einfach nichts passiert. Nur Anzeige; gesetzt oder verschoben wird nichts.
 */
final class EasyPlaceWarning {
	private static final long SHOW_MS = 3_000L;
	private static ItemStack missing = ItemStack.EMPTY;
	/** Weitere fehlende Blöcke (Printer: mehrere in Reichweite). */
	private static int more;
	private static int printerScanIn;
	private static long until;
	private static String lastSoundFor = "";
	private static long lastSoundAt;

	private EasyPlaceWarning() {
	}

	static void tick(Minecraft mc) {
		if (!WidgetConfig.get().buildWarn || mc.screen != null) {
			return;
		}
		// Printer: alle paar Ticks die Reichweite absuchen, was gebraucht wird, aber nicht im Inventar ist
		if (LitematicaBridge.printerActive()) {
			if (--printerScanIn > 0) {
				return;
			}
			printerScanIn = 10;
			java.util.Map<String, Long> inv = Build.inventory();
			java.util.List<ItemStack> lacking = new java.util.ArrayList<>();
			for (ItemStack s : LitematicaBridge.neededAround(mc, LitematicaBridge.printerRange())) {
				Long have = inv.get(Build.id(s.getItem()));
				if (have == null || have <= 0) {
					lacking.add(s);
				}
			}
			if (!lacking.isEmpty()) {
				show(lacking.get(0), lacking.size() - 1);
			}
			return;
		}
		if (!mc.options.keyUse.isDown() || !LitematicaBridge.easyPlaceOn()) {
			return;
		}
		ItemStack need = LitematicaBridge.targetedNeeded(mc);
		if (need.isEmpty()) {
			return;
		}
		Long have = Build.inventory().get(Build.id(need.getItem()));
		if (have != null && have > 0) {
			return;
		}
		show(need, 0);
	}

	private static void show(ItemStack need, int others) {
		String id = Build.id(need.getItem());
		long now = System.currentTimeMillis();
		missing = need.copyWithCount(1);
		more = others;
		until = now + SHOW_MS;
		// Ton nur einmal je Block alle paar Sekunden, nicht bei jedem Tick mit gedrückter Taste
		if (!id.equals(lastSoundFor) || now - lastSoundAt > 5_000L) {
			lastSoundFor = id;
			lastSoundAt = now;
			Sounds.play(Sounds.Group.BUILD, SoundEvents.NOTE_BLOCK_BASS, 0.7F, 0.6F);
		}
	}

	/** Hinweis über der Schnellleiste: „Fehlt im Inventar: Glas“. */
	static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (missing.isEmpty() || System.currentTimeMillis() > until || mc.options.hideGui) {
			return;
		}
		Component text = more > 0
				? Component.translatable("loothub.build.warn.missing_more", missing.getHoverName(), more)
				: Component.translatable("loothub.build.warn.missing", missing.getHoverName());
		int w = mc.font.width(text) + 30;
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() - 82;
		g.fill(x, y, x + w, y + 20, 0xD0120A0C);
		g.fill(x, y, x + w, y + 1, 0xFFE23B3B);
		g.fill(x, y + 19, x + w, y + 20, 0xFFE23B3B);
		g.renderItem(missing, x + 3, y + 2);
		g.drawString(mc.font, text, x + 24, y + 6, 0xFFFF8A8A, true);
	}
}
