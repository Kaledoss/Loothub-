package de.loothub.client.bau;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import de.loothub.client.Fmt;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Bau-Fortschritt im HUD – nur, solange man im Bereich einer Schematic steht (Rahmen + {@link Build#AREA} Blöcke):
 * „63 % · 12.340 / 19.600 Blöcke · heute +1.200 · noch ca. 3 Std.“. Verschieben über „HUD-Positionen“.
 */
public final class BuildHud {
	public static final int W = 190;
	public static final int H = 40;

	private BuildHud() {
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
				net.minecraft.resources.Identifier.fromNamespaceAndPath(de.loothub.Loothub.MOD_ID, "build"), BuildHud::render);
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
				net.minecraft.resources.Identifier.fromNamespaceAndPath(de.loothub.Loothub.MOD_ID, "build_warning"),
				EasyPlaceWarning::render);
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		WidgetConfig c = WidgetConfig.get();
		if (!Build.present() || !c.buildHud || mc.options.hideGui) {
			return;
		}
		Plan p = Build.here();
		if (p == null) {
			return;
		}
		int x = (int) Math.round(c.buildHudX * Math.max(0, g.guiWidth() - W - 8)) + 4;
		int y = (int) Math.round(c.buildHudY * Math.max(0, g.guiHeight() - H - 8)) + 4;
		draw(g, mc.font, p, x, y);
	}

	static void draw(GuiGraphics g, Font font, Plan p, int x, int y) {
		g.fill(x, y, x + W, y + H, de.loothub.client.Theme.bg(0xC0120A0C));
		g.fill(x, y, x + W, y + 1, de.loothub.client.Theme.bg(0xFF3A1F23));
		long total = p.totalBlocks();
		double frac = total <= 0 ? 0 : Math.min(1.0, (double) p.correct / total);
		String pct = p.counted == 0 ? "…" : Math.round(frac * 100) + " %";
		g.drawString(font, "⛏", x + 5, y + 4, 0xFFE07A7A, true);
		int pw = font.width(pct);
		g.drawString(font, fit(font, p.name, W - 30 - pw), x + 16, y + 4, 0xFFFFD66B, true);
		g.drawString(font, pct, x + W - 5 - pw, y + 4, 0xFFFFFFFF, true);
		// Balken
		int bx = x + 5, bw = W - 10, by = y + 15;
		g.fill(bx, by, bx + bw, by + 2, 0x40FFFFFF);
		g.fill(bx, by, bx + (int) Math.round(bw * frac), by + 2, 0xFF7BD88F);
		// Zahlen
		StringBuilder line = new StringBuilder(Fmt.whole(p.correct)).append(" / ").append(Fmt.whole(total));
		long today = Build.gainedToday(p);
		if (today >= 0) {
			line.append(" · ").append(Component.translatable("loothub.build.hud.today", Fmt.whole(today)).getString());
		}
		g.drawString(font, fit(font, line.toString(), W - 10), x + 5, y + 20, 0xFFE8D8DA, true);
		StringBuilder line2 = new StringBuilder();
		if (!p.fullyScanned()) {
			line2.append(Component.translatable("loothub.build.hud.scanned", p.chunkCounts.size(), p.chunkTotal).getString());
		} else {
			long eta = Build.remainingMs(p);
			if (eta >= 0 && p.correct < total) {
				line2.append(Component.translatable("loothub.build.hud.eta", Fmt.duration(eta)).getString());
			} else if (p.correct >= total && total > 0) {
				line2.append(Component.translatable("loothub.build.hud.done").getString());
			}
		}
		if (p.wrong > 0) {
			if (line2.length() > 0) {
				line2.append(" · ");
			}
			line2.append(Component.translatable("loothub.build.hud.wrong", Fmt.whole(p.wrong)).getString());
		}
		g.drawString(font, fit(font, line2.toString(), W - 10), x + 5, y + 30, 0xFFB9A9AB, true);
	}

	private static String fit(Font font, String s, int max) {
		if (font.width(s) <= max) {
			return s;
		}
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end)) + font.width("…") > max) {
			end--;
		}
		return s.substring(0, end) + "…";
	}
}
