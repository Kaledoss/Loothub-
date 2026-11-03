package de.loothub.client.quick;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;
import de.loothub.client.mixin.AbstractContainerScreenAccessor;

/**
 * Befehls-Leiste unter dem Inventar (Taste E): ein Feld mit Knöpfen für wichtige Befehle.
 *
 * <ul>
 *   <li>Klick auf einen Knopf schickt genau einen Befehl – wie selbst eingetippt (keine Automatisierung).</li>
 *   <li>Knöpfe per Drag &amp; Drop umsortieren, das ganze Feld an der Kopfleiste verschieben.</li>
 *   <li>Häkchen „Sperren“ oben rechts: dann lässt sich nichts mehr verschieben.</li>
 * </ul>
 * Reihenfolge, Position und Sperre werden in {@code config/loothub.json} gespeichert.
 */
public final class QuickCommandPanel {
	private static final int BUTTON_H = 20;
	private static final int BUTTON_W = 20;
	private static final int MIN_PANEL_W = 130;
	private static final int MAX_PER_ROW = 14;
	private static final int GAP = 3;
	private static final int PAD = 4;
	private static final int HEADER_H = 12;
	private static final int RECIPE_BOOK_W = 149;
	private static final int DRAG_THRESHOLD = 4;

	private static final int PANEL_BG = 0xE0140B0D;
	private static final int BTN_BG = 0xFF24161A;
	private static final int BTN_BG_HOVER = 0xFF3A1C22;
	private static final int BTN_EDGE = 0xFF4A2A2F;

	private QuickCommandPanel() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof InventoryScreen) || !WidgetConfig.get().quickPanelEnabled) {
				return;
			}
			Panel panel = new Panel(screen);
			// Im Hintergrund zeichnen, damit Item-Tooltips über der Leiste liegen
			ScreenEvents.afterBackground(screen).register((s, g, mx, my, delta) -> panel.render(g, mx, my));
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !panel.mouseClicked(e));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !panel.mouseDragged(e));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> !panel.mouseReleased(e));
		});
	}

	private static final net.minecraft.resources.Identifier CROWN =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "icon.png");
	private static final net.minecraft.resources.Identifier TROPHY =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "textures/gui/trophy.png");
	private static final java.util.Map<String, net.minecraft.world.item.ItemStack> ITEM_ICONS = new java.util.HashMap<>();

	/** Zeichnet ein Symbol: eigene Grafik (Krone, Pokal) oder ein Minecraft-Item. */
	public static void drawIcon(GuiGraphics g, String icon, int x, int y) {
		switch (icon) {
			case "loothub:crown" -> g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CROWN,
					x, y, 0f, 0f, 16, 16, 128, 128, 128, 128);
			case "loothub:trophy" -> g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, TROPHY,
					x, y, 0f, 0f, 16, 16, 16, 16, 16, 16);
			default -> g.renderItem(ITEM_ICONS.computeIfAbsent(icon, id -> {
				net.minecraft.resources.Identifier rid = net.minecraft.resources.Identifier.tryParse(id);
				net.minecraft.world.item.Item item = rid == null ? net.minecraft.world.item.Items.BARRIER
						: net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rid).orElse(net.minecraft.world.item.Items.BARRIER);
				return new net.minecraft.world.item.ItemStack(item);
			}), x, y);
		}
	}

	private static void run(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (QuickCommand.MERCHANT.equals(command)) {
			de.loothub.client.LoothubScreen.open(de.loothub.client.LoothubScreen.Page.MERCHANT);
			return;
		}
		if (mc.player != null && mc.getConnection() != null) {
			mc.getConnection().sendCommand(command.strip().replaceFirst("^/", ""));
		}
	}

	/** Ein Feld pro geöffnetem Inventar. */
	private static final class Panel {
		private enum Mode { NONE, BUTTON, MOVE }

		private final Screen screen;
		private int x, y, w, h, cols, bw;

		private Mode mode = Mode.NONE;
		private int pressedIndex = -1;
		private double pressX, pressY, grabX, grabY;
		private boolean dragging;
		private double mouseX, mouseY;

		Panel(Screen screen) {
			this.screen = screen;
		}

		/** Nur die eingeschalteten Knöpfe, in gespeicherter Reihenfolge. */
		private List<QuickCommand> commands() {
			List<QuickCommand> list = WidgetConfig.get().quickCommands;
			if (list == null) {
				return List.of();
			}
			List<QuickCommand> visible = new java.util.ArrayList<>();
			for (QuickCommand q : list) {
				if (!q.hidden) {
					visible.add(q);
				}
			}
			return visible;
		}

		/** Position berechnen: Standard unter dem Inventar + gespeicherte Verschiebung. */
		private void layout() {
			WidgetConfig c = WidgetConfig.get();
			AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
			int left = acc.loothub$leftPos();
			int imgW = acc.loothub$imageWidth();
			int bottom = acc.loothub$topPos() + acc.loothub$imageHeight();
			int centered = (screen.width - imgW) / 2;
			// Bei offenem Rezeptbuch ist das Inventar nach rechts gerückt – Bereich unter beidem
			int regionX = left > centered + 10 ? Math.max(2, left - RECIPE_BOOK_W) : left;
			int regionW = left + imgW - regionX;

			// Knöpfe in Reihen zu je höchstens 14
			int n = Math.max(1, commands().size());
			bw = BUTTON_W;
			// höchstens MAX_PER_ROW Knöpfe pro Reihe (und nie breiter als das Fenster), sonst neue Reihe
			int maxCols = Math.min(MAX_PER_ROW, Math.max(1, (screen.width - 4 - 2 * PAD + GAP) / (bw + GAP)));
			cols = Math.min(n, maxCols);
			w = Math.max(MIN_PANEL_W, 2 * PAD + cols * (bw + GAP) - GAP);
			int rows = (n + cols - 1) / cols;
			h = HEADER_H + rows * (BUTTON_H + GAP) - GAP + PAD + 2;

			int defX = regionX + (regionW - w) / 2;
			int defY = bottom + 4;
			x = Mth.clamp(defX + c.quickPanelOffsetX, 2, Math.max(2, screen.width - w - 2));
			y = Mth.clamp(defY + c.quickPanelOffsetY, 2, Math.max(2, screen.height - h - 2));
		}

		private int buttonX(int i) {
			return x + PAD + (i % cols) * (bw + GAP);
		}

		private int buttonY(int i) {
			return y + HEADER_H + (i / cols) * (BUTTON_H + GAP);
		}

		private int buttonAt(double mx, double my) {
			for (int i = 0; i < commands().size(); i++) {
				int bx = buttonX(i);
				int by = buttonY(i);
				if (mx >= bx && mx < bx + bw && my >= by && my < by + BUTTON_H) {
					return i;
				}
			}
			return -1;
		}

		/** Ziel-Platz beim Ablegen: nächster Knopf-Platz zur Maus. */
		private int slotAt(double mx, double my) {
			int n = commands().size();
			int col = Mth.clamp((int) ((mx - x - PAD) / (bw + GAP)), 0, cols - 1);
			int row = Mth.clamp((int) ((my - y - HEADER_H) / (BUTTON_H + GAP)), 0, (n - 1) / cols);
			return Mth.clamp(row * cols + col, 0, n - 1);
		}

		private boolean inside(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}

		private int lockX() {
			return x + w - PAD - 8;
		}

		private boolean onLock(double mx, double my) {
			Font font = Minecraft.getInstance().font;
			int labelW = font.width(Component.translatable("loothub.quick.lock"));
			return my >= y + 1 && my < y + 11 && mx >= lockX() - labelW - 4 && mx < lockX() + 9;
		}

		// ---------------------------------------------------------- Maus

		boolean mouseClicked(MouseButtonEvent e) {
			layout();
			if (e.button() != 0 || !inside(e.x(), e.y())) {
				return false;
			}
			boolean locked = WidgetConfig.get().quickPanelLocked;
			if (onLock(e.x(), e.y())) {
				WidgetConfig.get().quickPanelLocked = !locked;
				WidgetConfig.get().save();
				return true;
			}
			int i = buttonAt(e.x(), e.y());
			if (i >= 0) {
				mode = Mode.BUTTON;
				pressedIndex = i;
				pressX = e.x();
				pressY = e.y();
				dragging = false;
				return true;
			}
			if (!locked && e.y() < y + HEADER_H) {
				mode = Mode.MOVE;
				grabX = e.x() - x;
				grabY = e.y() - y;
			}
			return true; // Klicks ins Feld nicht ans Inventar weitergeben (sonst fällt ein gehaltenes Item)
		}

		boolean mouseDragged(MouseButtonEvent e) {
			mouseX = e.x();
			mouseY = e.y();
			if (mode == Mode.MOVE) {
				WidgetConfig c = WidgetConfig.get();
				int oldX = x;
				int oldY = y;
				layout();
				c.quickPanelOffsetX += (int) Math.round(e.x() - grabX) - oldX;
				c.quickPanelOffsetY += (int) Math.round(e.y() - grabY) - oldY;
				return true;
			}
			if (mode == Mode.BUTTON) {
				if (!WidgetConfig.get().quickPanelLocked
						&& (Math.abs(e.x() - pressX) > DRAG_THRESHOLD || Math.abs(e.y() - pressY) > DRAG_THRESHOLD)) {
					dragging = true;
				}
				return true;
			}
			return false;
		}

		boolean mouseReleased(MouseButtonEvent e) {
			if (mode == Mode.NONE) {
				return false;
			}
			layout();
			if (mode == Mode.BUTTON) {
				List<QuickCommand> list = commands();
				if (!dragging) {
					if (buttonAt(e.x(), e.y()) == pressedIndex && pressedIndex < list.size()) {
						run(list.get(pressedIndex).command);
					}
				} else if (pressedIndex < list.size()) {
					// In der sichtbaren Liste verschieben und auf die volle Liste (mit ausgeblendeten) übertragen
					int target = slotAt(e.x(), e.y());
					QuickCommand moved = list.remove(pressedIndex);
					target = Math.min(target, list.size());
					QuickCommand before = target < list.size() ? list.get(target) : null;
					List<QuickCommand> all = WidgetConfig.get().quickCommands;
					all.remove(moved);
					int idx = before == null ? all.size() : all.indexOf(before);
					all.add(idx < 0 ? all.size() : idx, moved);
					WidgetConfig.get().save();
				}
			} else if (mode == Mode.MOVE) {
				WidgetConfig.get().save();
			}
			mode = Mode.NONE;
			pressedIndex = -1;
			dragging = false;
			return true;
		}

		// ---------------------------------------------------------- Zeichnen

		void render(GuiGraphics g, int mx, int my) {
			layout();
			Font font = Minecraft.getInstance().font;
			boolean locked = WidgetConfig.get().quickPanelLocked;

			g.fill(x, y, x + w, y + h, de.loothub.client.Theme.bg(PANEL_BG));
			Pulse.outline(g, x, y, w, h);

			// Kopfleiste: Griff zum Verschieben, rechts das Häkchen „Sperren“
			boolean headerHover = !locked && mx >= x && mx < x + w && my >= y && my < y + HEADER_H && !onLock(mx, my);
			String grip = locked ? "Loothub+" : "⠿ Loothub+";
			g.drawString(font, grip, x + PAD, y + 2, headerHover || mode == Mode.MOVE ? 0xFFFFFFFF : LoothubScreen.FAINT, false);
			int lx = lockX();
			g.fill(lx, y + 2, lx + 8, y + 10, onLock(mx, my) ? 0xFFFFFFFF : BTN_EDGE);
			g.fill(lx + 1, y + 3, lx + 7, y + 9, 0xFF0F0A0B);
			if (locked) {
				g.fill(lx + 2, y + 4, lx + 6, y + 8, LoothubScreen.RED);
			}
			Component lock = Component.translatable("loothub.quick.lock");
			g.drawString(font, lock, lx - 3 - font.width(lock), y + 2, locked ? 0xFFFFFFFF : LoothubScreen.FAINT, false);

			// Knöpfe
			List<QuickCommand> list = commands();
			int hovered = mode == Mode.NONE ? buttonAt(mx, my) : -1;
			int dropSlot = dragging ? slotAt(mx, my) : -1;
			for (int i = 0; i < list.size(); i++) {
				if (dragging && i == pressedIndex) {
					// leerer Platz, wo der Knopf herkommt
					g.fill(buttonX(i), buttonY(i), buttonX(i) + bw, buttonY(i) + BUTTON_H, 0x40FFFFFF);
					continue;
				}
				drawButton(g, font, list.get(i), buttonX(i), buttonY(i), i == hovered || (mode == Mode.BUTTON && !dragging && i == pressedIndex));
			}
			if (dropSlot >= 0) {
				int dx = buttonX(dropSlot);
				int dy = buttonY(dropSlot);
				g.fill(dx - 2, dy, dx - 1, dy + BUTTON_H, LoothubScreen.RED);
			}
			if (dragging && pressedIndex >= 0 && pressedIndex < list.size()) {
				drawButton(g, font, list.get(pressedIndex), mx - bw / 2, my - BUTTON_H / 2, true);
			}

			if (hovered >= 0) {
				QuickCommand qc = list.get(hovered);
				List<net.minecraft.util.FormattedCharSequence> tip = new java.util.ArrayList<>();
				String name = qc.label == null || qc.label.isBlank() ? "/" + qc.command : qc.label;
				tip.add(Component.literal(name).withStyle(net.minecraft.ChatFormatting.WHITE).getVisualOrderText());
				tip.add(Component.literal("/" + qc.command.strip().replaceFirst("^/", ""))
						.withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
				g.setTooltipForNextFrame(font, tip, mx, my);
			}
		}

		private void drawButton(GuiGraphics g, Font font, QuickCommand qc, int bx, int by, boolean hover) {
			g.fill(bx, by, bx + bw, by + BUTTON_H, hover ? LoothubScreen.RED : BTN_EDGE);
			g.fill(bx + 1, by + 1, bx + bw - 1, by + BUTTON_H - 1, hover ? de.loothub.client.Theme.bg(BTN_BG_HOVER) : de.loothub.client.Theme.bg(BTN_BG));
			// Nur das Symbol – Name und Befehl stehen im Tooltip
			drawIcon(g, qc.iconOrDefault(), bx + (bw - 16) / 2, by + (BUTTON_H - 16) / 2);
		}
	}
}
