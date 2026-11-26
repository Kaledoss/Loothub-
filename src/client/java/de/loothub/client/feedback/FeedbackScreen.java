package de.loothub.client.feedback;

import de.loothub.client.LoothubScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Feedback/Fehler melden: Text, optional Bildschirmfoto (Spielbild ohne Menü), dazu automatisch Version, Spielername,
 * Loothub-Einstellungen und die letzten Loothub-Zeilen aus dem Log.
 */
public class FeedbackScreen extends Screen {
	private final Screen parent;
	private static String draft = "";
	private static boolean withShot = true;
	private MultiLineEditBox text;
	private Button send, shotButton;
	private String status = "";
	private int statusColor = LoothubScreen.MUTED;
	private boolean sending;

	public FeedbackScreen(Screen parent) {
		super(Component.translatable("loothub.feedback.title"));
		this.parent = parent;
	}

	private int pw() {
		return Math.min(360, width - 40);
	}

	private int px() {
		return (width - pw()) / 2;
	}

	private int py() {
		return Math.max(10, height / 2 - 110);
	}

	@Override
	protected void init() {
		int x = px() + 10, y = py() + 30, w = pw() - 20;
		text = addRenderableWidget(MultiLineEditBox.builder().setX(x).setY(y)
				.setPlaceholder(Component.translatable("loothub.feedback.placeholder"))
				.build(font, w, 90, Component.translatable("loothub.feedback.title")));
		text.setCharacterLimit(2000);
		text.setValue(draft);
		setInitialFocus(text);
		y += 96;
		shotButton = addRenderableWidget(Button.builder(shotLabel(), b -> {
			withShot = !withShot;
			b.setMessage(shotLabel());
		}).bounds(x, y, w / 2 - 2, 16).build());
		addRenderableWidget(Button.builder(Component.translatable(Feedback.shot == null ? "loothub.feedback.shot.take"
				: "loothub.feedback.shot.retake"), b -> {
					draft = text.getValue();
					withShot = true;
					Feedback.capture(() -> minecraft.setScreen(new FeedbackScreen(parent)));
				}).bounds(x + w / 2 + 2, y, w / 2 - 2, 16).build());
		y += 22;
		send = addRenderableWidget(Button.builder(Component.translatable("loothub.feedback.send"), b -> send())
				.bounds(x, py() + 196, w / 2 - 2, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(x + w / 2 + 2, py() + 196, w / 2 - 2, 18).build());
	}

	private Component shotLabel() {
		boolean has = Feedback.shot != null;
		return Component.literal(withShot && has ? "☑ " : "☐ ").append(Component.translatable(
				has ? "loothub.feedback.shot" : "loothub.feedback.shot.none"));
	}

	private void send() {
		String t = text.getValue().strip();
		if (t.length() < 5) {
			status = Component.translatable("loothub.feedback.short").getString();
			statusColor = 0xFFFF6B6B;
			return;
		}
		if (sending) {
			return;
		}
		sending = true;
		send.active = false;
		status = Component.translatable("loothub.feedback.sending").getString();
		statusColor = LoothubScreen.MUTED;
		Feedback.send(t, withShot, err -> minecraft.execute(() -> {
			sending = false;
			send.active = true;
			if (err == null) {
				draft = "";
				text.setValue("");
				status = Component.translatable("loothub.feedback.thanks").getString();
				statusColor = 0xFF55E06A;
				shotButton.setMessage(shotLabel());
			} else {
				status = Component.translatable("loothub.feedback.error." + (err.equals("too_many") ? "too_many" : "other")).getString();
				statusColor = 0xFFFF6B6B;
			}
		}));
	}

	@Override
	public void onClose() {
		draft = text == null ? draft : text.getValue();
		minecraft.setScreen(parent);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		super.renderBackground(g, mx, my, delta);
		int x = px(), y = py(), w = pw(), h = 222;
		g.fill(x, y, x + w, y + h, LoothubScreen.FRAME_BG);
		g.fill(x, y, x + w, y + 1, LoothubScreen.RED);
		g.fill(x, y + h - 1, x + w, y + h, LoothubScreen.FRAME_EDGE);
		g.drawString(font, Component.literal("✉ ").append(title).withStyle(ChatFormatting.BOLD), x + 10, y + 8, LoothubScreen.RED, false);
		g.drawString(font, Component.translatable("loothub.feedback.info"), x + 10, y + 19, LoothubScreen.FAINT, false);
		g.drawString(font, Component.translatable("loothub.feedback.attach"), x + 10, y + 148, LoothubScreen.FAINT, false);
		if (!status.isEmpty()) {
			g.drawCenteredString(font, status, x + w / 2, y + 166, statusColor);
		}
		if (Feedback.shot != null && withShot) {
			g.drawString(font, Component.translatable("loothub.feedback.shot.ready"), x + 10, y + 178, 0xFF55E06A, false);
		}
	}
}
