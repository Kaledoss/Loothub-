package de.loothub.client.quick;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/** Rechtsklick auf eine Chat-Nachricht: alle Nachrichten dieser Quelle in einen Tab schieben (oder neuen Tab anlegen). */
public class ChatAssignScreen extends Screen {
	private final CustomChat.Window win;
	private final CustomChat.Msg msg;
	private static boolean only = true;
	private int px, py, pw, ph;

	public ChatAssignScreen(CustomChat.Window win, CustomChat.Msg msg) {
		super(Component.translatable("loothub.chatassign.title"));
		this.win = win;
		this.msg = msg;
	}

	@Override
	protected void init() {
		pw = Math.min(340, width - 20);
		List<CustomChat.Tab> tabs = win.tabs;
		ph = 112 + ((tabs.size() + 1 + 1) / 2) * 20;
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		int x = px + 10, w = pw - 20, half = (w - 4) / 2;
		int y = py + 62;
		int i = 0;
		for (CustomChat.Tab t : tabs) {
			boolean has = t.hasSource(msg.source());
			Component label = Component.literal((has ? "✔ " : "→ ") + t.name);
			Button b = addRenderableWidget(Button.builder(label, btn -> assign(t)).bounds(x + (i % 2) * (half + 4), y + (i / 2) * 20, half, 16).build());
			b.active = !has;
			i++;
		}
		addRenderableWidget(Button.builder(Component.translatable("loothub.chatassign.new", msg.source()).withStyle(ChatFormatting.GREEN), b -> {
			CustomChat.Tab t = new CustomChat.Tab();
			t.name = msg.source().length() > 14 ? msg.source().substring(0, 14) : msg.source();
			tabs.add(t);
			assign(t);
		}).bounds(x + (i % 2) * (half + 4), y + (i / 2) * 20, half, 16).build());
		// „Solche ausblenden“: Text der Nachricht (ohne Absender-Vorspann) im aktuellen Tab unter „Enthält nicht“ eintragen
		addRenderableWidget(Button.builder(Component.translatable("loothub.chatassign.hide", win.tab().name).withStyle(ChatFormatting.RED), b -> {
			CustomChat.Tab t = win.tab();
			String key = hideKey(msg.text().getString());
			if (!key.isEmpty()) {
				java.util.List<String> l = ChatWindowScreen.words(t.exclude);
				if (l.stream().noneMatch(e -> e.equalsIgnoreCase(key))) {
					l.add(key);
				}
				t.exclude = CustomChat.joinWords(l);
				CustomChat.invalidate();
				WidgetConfig.get().save();
			}
			onClose();
		}).bounds(x, py + ph - 44, w, 16).build());
		addRenderableWidget(Button.builder(onlyLabel(), b -> {
			only = !only;
			b.setMessage(onlyLabel());
		}).bounds(x, py + ph - 24, w - 94, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(px + pw - 90, py + ph - 24, 80, 16).build());
	}

	/** Kennzeichnender Teil einer Nachricht: ab dem Inhalt (nach „»“ bzw. „]“), höchstens ~40 Zeichen, ohne Komma. */
	static String hideKey(String text) {
		String t = net.minecraft.ChatFormatting.stripFormatting(text);
		int a = t.lastIndexOf('»', Math.min(t.length() - 1, 40));
		int br = t.indexOf(']', Math.max(0, a));
		int cut = Math.max(a, br >= 0 && br < 60 ? br : -1);
		String body = (cut >= 0 ? t.substring(cut + 1) : t).strip();
		if (body.length() > 40) {
			int sp = body.lastIndexOf(' ', 40);
			body = body.substring(0, sp > 15 ? sp : 40);
		}
		return body.strip();
	}

	private static Component onlyLabel() {
		return Component.translatable("loothub.chatassign.only").append(": ")
				.append(Component.translatable(only ? "options.on" : "options.off"));
	}

	private void assign(CustomChat.Tab t) {
		if (!t.hasSource(msg.source())) {
			t.toggleSource(msg.source());
		}
		if (only) {
			t.exclusive = true;
		}
		win.active = win.tabs.indexOf(t);
		CustomChat.invalidate();
		WidgetConfig.get().save();
		onClose();
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		g.fill(px, py, px + pw, py + ph, de.loothub.client.Theme.bg(0xF0140B0D));
		Pulse.outline(g, px, py, pw, ph);
		g.fill(px + 4, py + 3, px + pw - 4, py + 4, LoothubScreen.RED);
		g.drawString(font, getTitle(), px + 10, py + 9, LoothubScreen.RED, false);
		g.drawString(font, Component.translatable("loothub.chatassign.source",
				Component.literal(msg.source()).withStyle(ChatFormatting.GOLD)), px + 10, py + 22, LoothubScreen.TEXT, false);
		List<FormattedCharSequence> lines = font.split(msg.text(), pw - 20);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			g.drawString(font, lines.get(i), px + 10, py + 34 + i * 10, 0xFFFFFFFF, false);
		}
		super.render(g, mx, my, delta);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
	}

	@Override
	public void onClose() {
		minecraft.setScreen(null);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
