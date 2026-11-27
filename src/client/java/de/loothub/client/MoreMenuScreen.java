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
	private final int anchorX, top;
	private final boolean fromLeft;
	private final List<Entry> entries;

	/** Menü, dessen rechte Kante bei {@code right} liegt (unter einem Knopf). */
	public MoreMenuScreen(Screen parent, int right, int top, List<Entry> entries) {
		this(parent, right, top, entries, false);
	}

	/** {@code fromLeft}: linke obere Ecke bei x/y (z. B. an der Maus). */
	public MoreMenuScreen(Screen parent, int x, int top, List<Entry> entries, boolean fromLeft) {
		super(Component.translatable("loothub.more.button"));
		this.parent = parent;
		this.anchorX = x;
		this.top = top;
		this.entries = entries;
		this.fromLeft = fromLeft;
	}

	@Override
	protected void init() {
		int w = 60;
		for (Entry e : entries) {
			w = Math.max(w, font.width(e.label()) + 16);
		}
		int x = fromLeft ? Math.min(anchorX, width - w - 2) : Math.max(2, anchorX - w);
		int y = Math.max(2, Math.min(top, height - entries.size() * 19 - 2));
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
