package de.loothub.client;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import de.loothub.client.quick.Pulse;

/** Bestätigungsfenster im Loothub-Stil: „Ja, löschen“ / „Abbrechen“. Esc = Abbrechen. */
public class ConfirmScreen extends Screen {
	private final Screen parent;
	private final Component message;
	private final Runnable onYes;
	private int px, py, pw, ph;

	public ConfirmScreen(Screen parent, Component title, Component message, Runnable onYes) {
		super(title);
		this.parent = parent;
		this.message = message;
		this.onYes = onYes;
	}

	@Override
	protected void init() {
		pw = Math.min(280, width - 20);
		List<FormattedCharSequence> lines = font.split(message, pw - 20);
		ph = 52 + lines.size() * 10 + 4;
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		int bw = (pw - 24) / 2;
		addRenderableWidget(Button.builder(Component.translatable("loothub.confirm.yes").withStyle(ChatFormatting.RED), b -> {
			minecraft.setScreen(parent);
			onYes.run();
		}).bounds(px + 10, py + ph - 24, bw, 16).build());
		Button no = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(px + pw - 10 - bw, py + ph - 24, bw, 16).build());
		setInitialFocus(no); // Enter bricht im Zweifel ab
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		g.fill(px, py, px + pw, py + ph, Theme.bg(0xF0140B0D));
		Pulse.outline(g, px, py, pw, ph);
		g.fill(px + 4, py + 3, px + pw - 4, py + 4, LoothubScreen.RED);
		g.drawString(font, Component.literal("⚠ ").append(getTitle()), px + 10, py + 9, LoothubScreen.RED, false);
		List<FormattedCharSequence> lines = font.split(message, pw - 20);
		for (int i = 0; i < lines.size(); i++) {
			g.drawString(font, lines.get(i), px + 10, py + 24 + i * 10, LoothubScreen.TEXT, false);
		}
		super.render(g, mx, my, delta);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0xA0000000);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
