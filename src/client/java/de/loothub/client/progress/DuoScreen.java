package de.loothub.client.progress;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Duo-Partner für die Woche eintragen: Spielername eingeben; zählt, sobald der andere einen auch einträgt. */
final class DuoScreen extends Screen {
	private final Screen parent;
	private final String current;
	private EditBox name;
	private String message = "";
	private boolean busy;

	DuoScreen(Screen parent, String current) {
		super(Component.translatable("loothub.lh.duo.pick"));
		this.parent = parent;
		this.current = current == null ? "" : current;
	}

	@Override
	protected void init() {
		int cx = width / 2, cy = height / 2;
		name = addRenderableWidget(new EditBox(font, cx - 90, cy - 6, 180, 18, Component.translatable("loothub.lh.gift.name")));
		name.setMaxLength(16);
		name.setHint(Component.translatable("loothub.lh.gift.name"));
		name.setValue(current);
		setInitialFocus(name);
		addRenderableWidget(Button.builder(Component.translatable("loothub.lh.duo.set"), b -> send(name.getValue().strip()))
				.bounds(cx - 90, cy + 20, 88, 18).build());
		Button leave = addRenderableWidget(Button.builder(Component.translatable("loothub.lh.duo.leave"), b -> send(""))
				.bounds(cx + 2, cy + 20, 88, 18).build());
		leave.active = !current.isEmpty();
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(cx - 45, cy + 42, 90, 18).build());
		// Freunde direkt wählen (online zuerst): ein Klick trägt sie ein
		if (de.loothub.client.jobs.WidgetConfig.get().friendsOn && !de.loothub.client.friends.Friends.loaded()) {
			de.loothub.client.friends.Friends.refresh(e -> {
				if (minecraft.screen == this) {
					rebuildWidgets();
				}
			});
		}
		var friends = de.loothub.client.friends.Friends.friends();
		int n = Math.min(friends.size(), 8), bw = 88;
		for (int i = 0; i < n; i++) {
			var f = friends.get(i);
			int col = i % 4, row = i / 4;
			int rowCount = Math.min(4, n - row * 4);
			int x0 = cx - (rowCount * (bw + 4) - 4) / 2;
			Component label = Component.literal((f.online() ? "● " : "") + f.name())
					.withStyle(f.online() ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GRAY);
			addRenderableWidget(Button.builder(label, b -> send(f.name())).bounds(x0 + col * (bw + 4), cy + 92 + row * 22, bw, 18).build());
		}
	}

	private void send(String target) {
		if (busy) {
			return;
		}
		if (!target.isEmpty() && !target.matches("[A-Za-z0-9_]{3,16}")) {
			message = Component.translatable("loothub.lh.error.no player").getString();
			return;
		}
		busy = true;
		message = Component.translatable("loothub.lh.loading").getString();
		Progress.duo(target, err -> {
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
		g.drawCenteredString(font, getTitle(), cx, cy - 50, 0xFFF2C14E);
		int y = cy - 36;
		for (var line : font.split(Component.translatable("loothub.lh.duo.pick.hint"), 280)) {
			g.drawCenteredString(font, line, cx, y, 0xFFB9A9AB);
			y += 10;
		}
		if (!message.isEmpty()) {
			g.drawCenteredString(font, message, cx, cy + 66, 0xFFE23B3B);
		}
		if (!de.loothub.client.friends.Friends.friends().isEmpty()) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.duo.friends"), cx, cy + 80, 0xFFB9A9AB);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
