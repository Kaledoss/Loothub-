package de.loothub.client.progress;

import de.loothub.client.Fmt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Geschenk: Spielername eingeben, der Schenkende zahlt den Preis. */
final class GiftScreen extends Screen {
	private final Screen parent;
	private final String id;
	private final int cost;
	private EditBox name;
	private String message = "";
	private boolean busy;

	GiftScreen(Screen parent, String id, int cost) {
		super(Component.translatable("loothub.lh.gift.title"));
		this.parent = parent;
		this.id = id;
		this.cost = cost;
	}

	@Override
	protected void init() {
		int cx = width / 2, cy = height / 2;
		name = addRenderableWidget(new EditBox(font, cx - 90, cy - 6, 180, 18, Component.translatable("loothub.lh.gift.name")));
		name.setMaxLength(16);
		name.setHint(Component.translatable("loothub.lh.gift.name"));
		setInitialFocus(name);
		addRenderableWidget(Button.builder(Component.translatable("loothub.lh.gift.send", Fmt.whole(cost)), b -> send())
				.bounds(cx - 90, cy + 20, 118, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(cx + 32, cy + 20, 58, 18).build());
	}

	private void send() {
		String target = name.getValue().strip();
		if (busy || !target.matches("[A-Za-z0-9_]{3,16}")) {
			message = Component.translatable("loothub.lh.error.no player").getString();
			return;
		}
		busy = true;
		message = Component.translatable("loothub.lh.loading").getString();
		Progress.gift(id, target, err -> {
			busy = false;
			if (err == null) {
				minecraft.execute(this::onClose);
			} else {
				message = Component.translatable("loothub.lh.error." + err).getString();
			}
		});
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int cx = width / 2, cy = height / 2;
		g.drawCenteredString(font, Component.translatable("loothub.lh.gift.title"), cx, cy - 50, 0xFFF2C14E);
		g.drawCenteredString(font, Component.translatable("loothub.lh.gift.what", Shop.name(id), Fmt.whole(cost)), cx, cy - 34, 0xFFE8D8DA);
		if (!message.isEmpty()) {
			g.drawCenteredString(font, message, cx, cy + 46, 0xFFE23B3B);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
