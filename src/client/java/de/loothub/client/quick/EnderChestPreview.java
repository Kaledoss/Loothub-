package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Enderkiste als Vorschau unter der Befehls-Leiste im Inventar. Der Inhalt wird gemerkt, sobald man die
 * Enderkiste (z. B. per /ec) öffnet – reine Anzeige. Ein Klick auf die Vorschau schickt /ec.
 */
public final class EnderChestPreview {
	private static final int CELL = 18;
	private static final int PAD = 4;
	private static final int HEADER_H = 12;

	/** Zuletzt gesehener Inhalt (Kopien) und wann. */
	private static List<ItemStack> items = List.of();
	private static long seenAt;

	private EnderChestPreview() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof ContainerScreen cs && isEnderChest(cs)) {
				// Inhalt laufend mitschreiben, solange die Enderkiste offen ist
				ScreenEvents.afterTick(screen).register(s -> capture(cs));
				ScreenEvents.remove(screen).register(s -> capture(cs));
				return;
			}
			if (screen instanceof InventoryScreen && WidgetConfig.get().enderChestPreview) {
				ScreenEvents.afterBackground(screen).register((s, g, mx, my, d) -> render(s, g, mx, my));
				ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> {
					if (e.button() == 0 && inside(s, e.x(), e.y())) {
						Minecraft mc = Minecraft.getInstance();
						if (mc.getConnection() != null) {
							mc.getConnection().sendCommand("ec");
						}
						return false;
					}
					return true;
				});
			}
		});
	}

	private static boolean isEnderChest(ContainerScreen screen) {
		String t = screen.getTitle().getString().toLowerCase(Locale.ROOT);
		return t.contains("ender") || t.contains("enderkiste") || t.contains("endertruhe");
	}

	private static void capture(ContainerScreen screen) {
		int containerSlots = screen.getMenu().getRowCount() * 9;
		List<ItemStack> copy = new ArrayList<>(containerSlots);
		List<Slot> slots = screen.getMenu().slots;
		for (int i = 0; i < containerSlots && i < slots.size(); i++) {
			copy.add(slots.get(i).getItem().copy());
		}
		items = copy;
		seenAt = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------ Lage

	private static int[] rect(Screen s) {
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
		int cols = 9;
		int rows = Math.max(1, (Math.max(items.size(), 27) + cols - 1) / cols);
		int w = 2 * PAD + cols * CELL;
		int h = HEADER_H + rows * CELL + PAD;
		int x;
		int y;
		boolean panelNow = WidgetConfig.get().quickPanelEnabled
				&& System.nanoTime() - QuickCommandPanel.lastFrame < 200_000_000L;
		if (panelNow) {
			x = QuickCommandPanel.lastX + (QuickCommandPanel.lastW - w) / 2;
			y = QuickCommandPanel.lastBottom + 4;
		} else {
			x = acc.loothub$leftPos() + (acc.loothub$imageWidth() - w) / 2;
			y = acc.loothub$topPos() + acc.loothub$imageHeight() + 4;
		}
		return new int[]{x, y, w, h};
	}

	private static boolean inside(Screen s, double mx, double my) {
		int[] r = rect(s);
		return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
	}

	// ------------------------------------------------------------------ Zeichnen

	private static void render(Screen s, GuiGraphics g, int mx, int my) {
		Font font = Minecraft.getInstance().font;
		int[] r = rect(s);
		int x = r[0], y = r[1], w = r[2], h = r[3];
		g.fill(x, y, x + w, y + h, 0xE0140B0D);
		Pulse.outline(g, x, y, w, h);
		g.drawString(font, "Enderkiste", x + PAD, y + 2, LoothubScreen.FAINT, false);
		String age = seenAt == 0 ? "einmal /ec öffnen" : "Stand " + de.loothub.client.Fmt.time(seenAt);
		g.drawString(font, age, x + w - PAD - font.width(age), y + 2, LoothubScreen.FAINT, false);

		int top = y + HEADER_H;
		int cells = Math.max(items.size(), 27);
		ItemStack hovered = ItemStack.EMPTY;
		for (int i = 0; i < cells; i++) {
			int cx = x + PAD + (i % 9) * CELL;
			int cy = top + (i / 9) * CELL;
			g.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, 0xFF24161A);
			g.fill(cx, cy + CELL - 2, cx + CELL - 1, cy + CELL - 1, 0xFF4A2A2F);
			if (i < items.size()) {
				ItemStack st = items.get(i);
				if (!st.isEmpty()) {
					g.renderItem(st, cx + 1, cy + 1);
					g.renderItemDecorations(font, st, cx + 1, cy + 1);
					if (mx >= cx && mx < cx + CELL - 1 && my >= cy && my < cy + CELL - 1) {
						hovered = st;
						g.fill(cx + 1, cy + 1, cx + 17, cy + 17, 0x40FFFFFF);
					}
				}
			}
		}
		if (!hovered.isEmpty()) {
			List<Component> lines = Screen.getTooltipFromItem(Minecraft.getInstance(), hovered);
			List<FormattedCharSequence> seq = new ArrayList<>(lines.size() + 1);
			for (Component c : lines) {
				seq.add(c.getVisualOrderText());
			}
			seq.add(Component.literal("Klick: /ec öffnen").withStyle(net.minecraft.ChatFormatting.DARK_GRAY).getVisualOrderText());
			g.setTooltipForNextFrame(font, seq, mx, my);
		} else if (mx >= x && mx < x + w && my >= y && my < y + h) {
			g.setTooltipForNextFrame(font, Component.literal("Klick: /ec öffnen"), mx, my);
		}
	}
}
