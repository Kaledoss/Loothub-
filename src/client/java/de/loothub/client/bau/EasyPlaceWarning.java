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
 * Easy Place (Litematica) findet den Block, auf den man zielt, nicht im Inventar: deutlich anzeigen, welcher Block
 * fehlt – statt dass einfach nichts passiert. Nur Anzeige; gesetzt oder verschoben wird nichts.
 */
final class EasyPlaceWarning {
	private static final long SHOW_MS = 3_000L;
	private static ItemStack missing = ItemStack.EMPTY;
	private static long until;
	private static String lastSoundFor = "";
	private static long lastSoundAt;

	private EasyPlaceWarning() {
	}

	static void tick(Minecraft mc) {
		if (!WidgetConfig.get().buildWarn || mc.screen != null || !mc.options.keyUse.isDown()
				|| !LitematicaBridge.easyPlaceOn()) {
			return;
		}
		ItemStack need = LitematicaBridge.targetedNeeded(mc);
		if (need.isEmpty()) {
			return;
		}
		String id = Build.id(need.getItem());
		Long have = Build.inventory().get(id);
		if (have != null && have > 0) {
			return;
		}
		long now = System.currentTimeMillis();
		missing = need.copyWithCount(1);
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
		Component text = Component.translatable("loothub.build.warn.missing", missing.getHoverName());
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
