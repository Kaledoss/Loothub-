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
public final class EasyPlaceWarning {
	private static final long SHOW_MS = 3_000L;
	private static ItemStack missing = ItemStack.EMPTY;
	/** Weitere fehlende Blöcke (Printer: mehrere in Reichweite). */
	private static int more;
	private static int printerScanIn;
	private static boolean printer, loggedPrinting;
	private static String lastLogged = "";
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
		boolean printing = LitematicaBridge.printerActive();
		if (printing != loggedPrinting) {
			loggedPrinting = printing;
			de.loothub.Loothub.LOGGER.info("[Loothub-Bau] Printer {}", printing ? "druckt" : "druckt nicht");
		}
		// Printer an, im Bereich einer Schematic oder Easy-Place-Taste gedrückt (wie in Litematica belegt):
		// laufend die Reichweite absuchen – ohne dass man dafür rechtsklicken muss
		boolean easyKey = LitematicaBridge.easyPlaceOn() && LitematicaBridge.easyPlaceKeyHeld();
		if (printing || easyKey || Build.here() != null) {
			if (--printerScanIn > 0) {
				return;
			}
			printerScanIn = 10;
			double range = printing ? LitematicaBridge.printerRange() : mc.player.blockInteractionRange();
			java.util.Map<String, Long> inv = Build.inventory();
			java.util.List<ItemStack> lacking = new java.util.ArrayList<>();
			for (ItemStack s : LitematicaBridge.neededAround(mc, range)) {
				Long have = inv.get(Build.id(s.getItem()));
				if (have == null || have <= 0) {
					lacking.add(s);
				}
			}
			if (!lacking.isEmpty()) {
				printer = printing;
				show(lacking.get(0), lacking.size() - 1);
			}
			return;
		}
		// sonst: beim Rechtsklick auf einen fehlenden Schematic-Block
		if (!mc.options.keyUse.isDown()) {
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
		printer = false;
		show(need, 0);
	}

	private static void show(ItemStack need, int others) {
		String id = Build.id(need.getItem());
		if (!id.equals(lastLogged)) {
			lastLogged = id;
			de.loothub.Loothub.LOGGER.info("[Loothub-Bau] Fehlt im Inventar: {} (+{} weitere, {})", id, others,
					printer ? "Printer" : "Rechtsklick");
		}
		long now = System.currentTimeMillis();
		missing = need.copyWithCount(1);
		more = others;
		until = now + SHOW_MS;
		// Ton nur beim ersten Mal je Block und dann höchstens jede Minute (die Einblendung bleibt, solange er fehlt)
		if (!id.equals(lastSoundFor) || now - lastSoundAt > 60_000L) {
			lastSoundFor = id;
			lastSoundAt = now;
			Sounds.play(Sounds.Group.BUILD, SoundEvents.NOTE_BLOCK_BASS, 0.7F, 0.6F);
		}
	}

	/** Einblendung im Stil der Twitch-Alerts: großer Titel „⚠ Glas fehlt“, darunter der Grund. */
	static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (missing.isEmpty() || now > until || mc.options.hideGui) {
			return;
		}
		String title = "⚠ " + Component.translatable("loothub.build.warn.title", missing.getHoverName()).getString();
		String sub = (more > 0 ? Component.translatable("loothub.build.warn.sub_more", more)
				: Component.translatable(printer ? "loothub.build.warn.sub_printer" : "loothub.build.warn.sub")).getString();
		int[] size = sampleBox(mc, title, sub);
		int[] p = pos(g.guiWidth(), g.guiHeight(), size[0], size[1]);
		// hereinrutschen und am Ende ausblenden wie die Alerts
		long age = SHOW_MS - (until - now);
		float a = age < 200 ? age / 200F : until - now < 400 ? (until - now) / 400F : 1F;
		a = Math.max(0F, Math.min(1F, a));
		int y = p[1] + (int) ((1 - Math.min(1F, age / 200F)) * -12);
		de.loothub.client.streamer.Alerts.drawBox(g, mc.font, title, sub, 0xFFE23B3B, p[0] + 18, y, a);
		g.renderItem(missing, p[0], y + (size[1] - 16) / 2);
	}

	/** Größe der Einblendung (mit Platz für das Item links). */
	private static int[] sampleBox(Minecraft mc, String title, String sub) {
		int[] s = de.loothub.client.streamer.Alerts.boxSize(mc.font, title, sub);
		return new int[]{s[0] + 18, s[1]};
	}

	/** Beispielgröße für „HUD-Positionen“. */
	public static int[] sampleSize() {
		Minecraft mc = Minecraft.getInstance();
		return sampleBox(mc, "⚠ Glas fehlt", Component.translatable("loothub.build.warn.sub").getString());
	}

	static int[] pos(int sw, int sh, int w, int h) {
		WidgetConfig c = WidgetConfig.get();
		return new int[]{(int) Math.round(c.buildWarnX * Math.max(0, sw - w)), (int) Math.round(c.buildWarnY * Math.max(0, sh - h))};
	}
}
