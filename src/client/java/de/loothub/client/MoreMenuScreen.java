package de.loothub.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Kleines Aufklappmenü („⋯“) über dem Loothub-Menü; ein Klick daneben oder Esc schließt es. */
public class MoreMenuScreen extends Screen {
	public record Entry(Component label, Runnable action) {
	}

	private final Screen parent;
	private final int right, top;
	private final List<Entry> entries;

	public MoreMenuScreen(Screen parent, int right, int top, List<Entry> entries) {
		super(Component.translatable("loothub.more.button"));
		this.parent = parent;
		this.right = right;
		this.top = top;
		this.entries = entries;
	}

	@Override
	protected void init() {
		int w = 60;
		for (Entry e : entries) {
			w = Math.max(w, font.width(e.label()) + 16);
		}
		int x = Math.max(2, right - w), y = top;
		for (Entry e : entries) {
			addRenderableWidget(Button.builder(e.label(), b -> e.action().run()).bounds(x, y, w, 18).build());
			y += 19;
		}
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// Menü dahinter unverändert zeigen (ohne Weichzeichnen)
		parent.renderBackground(g, -1, -1, delta);
		parent.render(g, -1, -1, delta);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (!super.mouseClicked(event, doubleClick)) {
			onClose(); // daneben geklickt
		}
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return parent.isPauseScreen();
	}
}
