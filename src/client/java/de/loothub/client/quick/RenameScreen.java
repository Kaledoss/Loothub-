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

	@Override
	protected void init() {
		int w = Math.min(320, width - 40);
		int x = (width - w) / 2;
		int y = height / 2 - 40;
		box = new EditBox(font, x, y + 26, w, 18, Component.translatable("loothub.rename.input"));
		box.setMaxLength(command.equals("sign") ? 120 : 60);
		box.setHint(Component.translatable("loothub.rename.hint").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		addRenderableWidget(box);
		setFocused(box);
		// Farben
		int cw = Math.max(12, (w - 15 * 2) / 16);
		for (int i = 0; i < 16; i++) {
			String code = CODES[i];
			addRenderableWidget(Button.builder(Component.empty(), b -> insert("&" + code))
					.bounds(x + i * (cw + 2), y + 50, cw, 12).build());
		}
		// Formatierung
		for (int i = 0; i < STYLES.length; i++) {
			String[] st = STYLES[i];
			addRenderableWidget(Button.builder(Component.literal(st[1]), b -> insert("&" + st[0]))
					.bounds(x + i * 26, y + 66, 22, 14).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("loothub.color.apply"), b -> send())
				.bounds(width / 2 - 104, y + 92, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.color.cancel"), b -> onClose())
				.bounds(width / 2 + 4, y + 92, 100, 20).build());
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

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		int w = Math.min(320, width - 40);
		int x = (width - w) / 2;
		int y = height / 2 - 40;
		g.drawString(font, getTitle(), (width - font.width(getTitle())) / 2, y - 26, 0xFFFFFFFF, true);
		// Item in der Hand + Vorschau
		ItemStack st = held();
		g.fill(x - 2, y - 4, x + w + 2, y + 22, 0xC0140B0D);
		g.renderItem(st, x + 2, y + 1);
		Component preview = box.getValue().isBlank()
				? st.getHoverName()
				: Component.literal(box.getValue().replace('&', '§'));
		g.drawString(font, preview, x + 24, y + 5, 0xFFFFFFFF, true);
		// Farbfelder auf die Knöpfe malen
		int cw = Math.max(12, (w - 15 * 2) / 16);
		for (int i = 0; i < 16; i++) {
			int bx = x + i * (cw + 2);
			g.fill(bx + 2, y + 52, bx + cw - 2, y + 60, 0xFF000000 | COLORS[i]);
		}
		Component hint = Component.translatable("loothub.rename.codes");
		g.drawString(font, hint, x + STYLES.length * 26 + 6, y + 69, LoothubScreen.FAINT, false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
