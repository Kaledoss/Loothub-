package de.loothub.client.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;

/** Rangliste eines Minispiels (Top 10 der Woche bzw. längste Serien) mit eigener Platzierung. */
final class MgBoardScreen extends Screen {
	private final Screen parent;
	private final String game;
	private final boolean streak;
	private int week;
	private JsonObject data;
	private boolean loading;

	MgBoardScreen(Screen parent, String game) {
		super(Component.translatable("loothub.lh.mg.board.title"));
		this.parent = parent;
		this.game = game;
		this.streak = game.equals("quiz") || game.equals("guess");
		load();
	}

	private void load() {
		loading = true;
		data = null;
		Progress.mgBoard(game, week, o -> {
			data = o;
			loading = false;
		});
	}

	@Override
	protected void init() {
		int cx = width / 2, y = height / 2 + 112;
		if (!streak) {
			addRenderableWidget(Button.builder(Component.translatable("loothub.lh.mg.board.week"), b -> {
				week = 0;
				load();
				rebuildWidgets();
			}).bounds(cx - 122, y, 118, 18).build()).active = week != 0;
			addRenderableWidget(Button.builder(Component.translatable("loothub.lh.mg.board.last"), b -> {
				week = 1;
				load();
				rebuildWidgets();
			}).bounds(cx + 4, y, 118, 18).build()).active = week != 1;
			y += 22;
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(cx - 60, y, 120, 18).build());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int cx = width / 2, top = height / 2 - 120;
		int w = 260, x = cx - w / 2;
		g.fill(x - 6, top - 6, x + w + 6, height / 2 + 106, 0xE0100A0C);
		g.fill(x - 6, top - 6, x + w + 6, top - 5, LoothubScreen.RED);
		Component name = streak ? Component.translatable("loothub.lh.mg.streak." + game) : Component.translatable("loothub.lh.mg." + game);
		g.drawCenteredString(font, Component.translatable("loothub.lh.mg.board.of", name), cx, top, 0xFFF2C14E);
		String sub;
		if (streak) {
			sub = Component.translatable("loothub.lh.mg.board.streak_sub").getString();
		} else if (week == 0 && data != null && data.has("ends")) {
			sub = Component.translatable("loothub.lh.mg.board.ends", Texts.left(data.get("ends").getAsLong() - System.currentTimeMillis()))
					.getString();
		} else {
			sub = Component.translatable("loothub.lh.mg.board.last").getString();
		}
		g.drawCenteredString(font, sub, cx, top + 12, 0xFFB9A9AB);
		JsonArray reward = data != null && data.has("reward") ? data.getAsJsonArray("reward") : null;
		if (reward != null && reward.size() >= 3) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.mg.board.reward", reward.get(0).getAsInt(), reward.get(1).getAsInt(),
					reward.get(2).getAsInt()), cx, top + 24, 0xFF55E06A);
		}
		int y = top + 40;
		if (loading) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.loading"), cx, y + 20, 0xFFB9A9AB);
			return;
		}
		if (data == null) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.status.offline"), cx, y + 20, 0xFFE23B3B);
			return;
		}
		JsonArray list = data.getAsJsonArray("list");
		if (list.isEmpty()) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.mg.board.empty"), cx, y + 20, 0xFF7A6A6C);
		}
		String self = minecraft.getUser().getName();
		for (int i = 0; i < list.size(); i++) {
			JsonArray r = list.get(i).getAsJsonArray();
			String n = r.get(1).getAsString();
			int v = r.get(2).getAsInt();
			int col = i == 0 ? 0xFFF2C14E : i == 1 ? 0xFFD4DAE0 : i == 2 ? 0xFFD08A4E : 0xFFFFFFFF;
			if (n.equalsIgnoreCase(self)) {
				g.fill(x, y - 2, x + w, y + 10, 0x30F2C14E);
			}
			g.drawString(font, (i + 1) + ".", x + 4, y, col, false);
			g.drawString(font, n, x + 28, y, col, false);
			String val = Fmt.whole(v);
			g.drawString(font, val, x + w - 4 - font.width(val), y, col, false);
			y += 14;
		}
		if (data.has("me")) {
			JsonObject me = data.getAsJsonObject("me");
			g.drawCenteredString(font, Component.translatable("loothub.lh.mg.board.me", me.get("rank").getAsInt(), Fmt.whole(me.get("v").getAsInt())),
					cx, height / 2 + 94, 0xFFB9A9AB);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
