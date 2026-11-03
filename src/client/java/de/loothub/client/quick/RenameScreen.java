package de.loothub.client.quick;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import de.loothub.client.LoothubScreen;

/**
 * „/rename“ bzw. „/sign“ ohne Text öffnet ein Fenster: Item in der Hand, Eingabefeld mit Live-Vorschau
 * (Farbcodes mit &amp;) und Farb-Knöpfe. „Übernehmen“ schickt genau einen Befehl – wie selbst getippt.
 */
public class RenameScreen extends Screen {
	private static final String[] CODES = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
	private static final int[] COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
	private static final String[][] STYLES = {{"l", "B"}, {"o", "I"}, {"n", "U"}, {"m", "S"}, {"r", "↺"}};

	private static String pending;

	private final String command;
	private EditBox box;

	public RenameScreen(String command) {
		super(Component.translatable("loothub.rename.title." + command));
		this.command = command;
	}

	/** Befehle ohne Text abfangen und das Fenster im nächsten Tick öffnen (der Chat schließt sich vorher). */
	public static void register() {
		ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
			String c = cmd.strip().toLowerCase(Locale.ROOT);
			if (c.equals("rename") || c.equals("sign")) {
				pending = c;
				return false;
			}
			return true;
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (pending != null && mc.screen == null) {
				String c = pending;
				pending = null;
				mc.setScreen(new RenameScreen(c));
			}
		});
	}

	private ItemStack held() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
	}

	// Lage des Fensters (rechts) – links daneben steht die Tooltip-Vorschau
	private int panelW() {
		return Math.min(300, width / 2 - 20);
	}

	private int panelX() {
		return width / 2 + 10;
	}

	private int panelY() {
		return height / 2 - 70;
	}

	@Override
	protected void init() {
		int w = panelW() - 20;
		int x = panelX() + 10;
		int y = panelY() + 50;
		box = new EditBox(font, x, y, w, 18, Component.translatable("loothub.rename.input"));
		box.setMaxLength(command.equals("sign") ? 120 : 60);
		box.setHint(Component.translatable("loothub.rename.hint").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		addRenderableWidget(box);
		setFocused(box);
		// Farben in zwei Reihen à 8
		int cw = (w - 7 * 3) / 8;
		for (int i = 0; i < 16; i++) {
			String code = CODES[i];
			addRenderableWidget(Button.builder(Component.empty(), b -> insert("&" + code))
					.bounds(x + (i % 8) * (cw + 3), y + 26 + (i / 8) * 16, cw, 13).build());
		}
		// Formatierung
		for (int i = 0; i < STYLES.length; i++) {
			String[] st = STYLES[i];
			addRenderableWidget(Button.builder(Component.literal(st[1]), b -> insert("&" + st[0]))
					.bounds(x + i * 26, y + 62, 22, 14).build());
		}
		int half = (w - 6) / 2;
		addRenderableWidget(Button.builder(Component.translatable("loothub.color.apply"), b -> send())
				.bounds(x, y + 86, half, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.color.cancel"), b -> onClose())
				.bounds(x + half + 6, y + 86, half, 20).build());
	}

	private void insert(String s) {
		box.insertText(s);
		setFocused(box);
	}

	private void send() {
		String text = box.getValue().strip();
		Minecraft mc = Minecraft.getInstance();
		if (!text.isEmpty() && mc.getConnection() != null) {
			mc.getConnection().sendCommand(command + " " + text);
		}
		onClose();
	}

	private Component typed() {
		return Component.literal(box.getValue().replace('&', '§'));
	}

	/** Tooltip des Items, wie er nach dem Befehl aussähe. */
	private java.util.List<net.minecraft.util.FormattedCharSequence> previewTooltip(ItemStack st) {
		java.util.List<Component> lines = new java.util.ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), st));
		boolean empty = box.getValue().isBlank();
		if (command.equals("rename")) {
			if (!empty && !lines.isEmpty()) {
				lines.set(0, typed()); // neuer Name statt des alten
			}
		} else if (!empty) {
			lines.add(Component.empty());
			lines.add(typed()); // Signatur unten
		}
		java.util.List<net.minecraft.util.FormattedCharSequence> out = new java.util.ArrayList<>();
		for (Component c : lines) {
			out.add(c.getVisualOrderText());
		}
		return out;
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		int px = panelX();
		int py = panelY();
		int pw = panelW();
		int ph = 162;
		// Fenster im Loothub-Stil
		g.fill(px, py, px + pw, py + ph, de.loothub.client.Theme.bg(0xF0140B0D));
		Pulse.outline(g, px, py, pw, ph);
		g.fill(px + 4, py + 3, px + pw - 4, py + 4, LoothubScreen.RED);
		g.drawString(font, getTitle(), px + 10, py + 10, LoothubScreen.RED, false);
		// Item + kurze Vorschau des Namens
		ItemStack st = held();
		g.fill(px + 10, py + 24, px + pw - 10, py + 44, de.loothub.client.Theme.bg(0xFF24161A));
		g.renderItem(st, px + 12, py + 26);
		Component name = command.equals("rename") && !box.getValue().isBlank() ? typed() : st.getHoverName();
		g.drawString(font, name, px + 32, py + 30, 0xFFFFFFFF, true);
		// Farbfelder auf die Knöpfe malen
		int w = pw - 20;
		int x = px + 10;
		int y = py + 50;
		int cw = (w - 7 * 3) / 8;
		for (int i = 0; i < 16; i++) {
			int bx = x + (i % 8) * (cw + 3);
			int by = y + 26 + (i / 8) * 16;
			g.fill(bx + 3, by + 3, bx + cw - 3, by + 10, 0xFF000000 | COLORS[i]);
		}
		g.drawString(font, Component.translatable("loothub.rename.codes"), x + STYLES.length * 26 + 4, y + 65, LoothubScreen.FAINT, false);

		// Links: vollständige Vorschau als Tooltip
		if (!st.isEmpty()) {
			java.util.List<net.minecraft.util.FormattedCharSequence> tip = previewTooltip(st);
			int tw = 0;
			for (net.minecraft.util.FormattedCharSequence l : tip) {
				tw = Math.max(tw, font.width(l));
			}
			int tx = Math.max(4, px - tw - 24);
			g.drawString(font, Component.translatable("loothub.rename.preview"), tx, py - 4, LoothubScreen.FAINT, false);
			g.setTooltipForNextFrame(font, tip, tx - 12, py + 18);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
