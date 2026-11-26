package de.loothub.client.feedback;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mojang.blaze3d.platform.NativeImage;

import de.loothub.client.LoothubScreen;
import de.loothub.client.progress.Progress;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * Admin: alle Feedback-Meldungen. Links die Liste (offene zuerst), rechts die gewählte Meldung mit Text, Version,
 * Bildschirmfoto; Log und Einstellungen lassen sich kopieren, Meldungen als erledigt markieren oder löschen.
 */
public class FeedbackListScreen extends Screen {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
	private static final Identifier SHOT_ID = Identifier.fromNamespaceAndPath("loothub", "feedback_shot");
	private static final int ROW = 22;
	private final Screen parent;
	private final List<JsonObject> list = new ArrayList<>();
	private JsonObject selected;
	private int scroll;
	private String status = "";
	private DynamicTexture shotTex;
	private int shotW, shotH;
	private boolean bigShot;

	public FeedbackListScreen(Screen parent) {
		super(Component.translatable("loothub.feedback.list"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int listW = listW();
		addRenderableWidget(Button.builder(Component.translatable("loothub.feedback.reload"), b -> load())
				.bounds(10, height - 26, 80, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(listW - 70, height - 26, 80, 18).build());
		if (selected != null) {
			int x = listW + 20, w = width - x - 10, bw = (w - 12) / 4, y = height - 26;
			boolean done = "done".equals(str(selected, "status"));
			addRenderableWidget(Button.builder(Component.translatable(done ? "loothub.feedback.reopen" : "loothub.feedback.done"),
					b -> setStatus(done ? "open" : "done")).bounds(x, y, bw, 18).build());
			addRenderableWidget(Button.builder(Component.translatable("loothub.feedback.copy_log"), b -> {
				minecraft.keyboardHandler.setClipboard(str(selected, "log"));
				status = Component.translatable("loothub.feedback.copied").getString();
			}).bounds(x + bw + 4, y, bw, 18).build());
			addRenderableWidget(Button.builder(Component.translatable("loothub.feedback.copy_settings"), b -> {
				minecraft.keyboardHandler.setClipboard(str(selected, "settings"));
				status = Component.translatable("loothub.feedback.copied").getString();
			}).bounds(x + 2 * (bw + 4), y, bw, 18).build());
			addRenderableWidget(Button.builder(Component.translatable("loothub.feedback.delete"), b -> setStatus("delete"))
					.bounds(x + 3 * (bw + 4), y, bw, 18).build());
		}
		if (list.isEmpty()) {
			load();
		}
	}

	private int listW() {
		return Math.max(180, width * 2 / 5);
	}

	private void load() {
		status = Component.translatable("loothub.feedback.loading").getString();
		JsonObject p = new JsonObject();
		p.addProperty("op", "admin_fb_list");
		Progress.admin(p, res -> minecraft.execute(() -> {
			list.clear();
			if (res != null && res.has("list")) {
				for (JsonElement e : res.getAsJsonArray("list")) {
					list.add(e.getAsJsonObject());
				}
				status = Component.translatable("loothub.feedback.count", list.size()).getString();
				if (res.has("unread")) {
					Progress.setFeedbackUnread(res.get("unread").getAsInt());
				}
			} else {
				status = Component.translatable("loothub.lh.error.offline").getString();
			}
		}));
	}

	private void open(JsonObject row) {
		if (row.has("seen") && row.get("seen").getAsInt() == 0) {
			row.addProperty("seen", 1); // gelesen: „+1“ am Knopf zählt herunter
			Progress.setFeedbackUnread(Progress.feedbackUnread() - 1);
		}
		status = Component.translatable("loothub.feedback.loading").getString();
		JsonObject p = new JsonObject();
		p.addProperty("op", "admin_fb_get");
		p.addProperty("id", row.get("id").getAsLong());
		Progress.admin(p, res -> minecraft.execute(() -> {
			if (res == null || !res.has("fb")) {
				status = Component.translatable("loothub.lh.error.offline").getString();
				return;
			}
			selected = res.getAsJsonObject("fb");
			status = "";
			showShot(str(selected, "shot"));
			rebuildWidgets();
		}));
	}

	private void showShot(String b64) {
		if (shotTex != null) {
			minecraft.getTextureManager().release(SHOT_ID);
			shotTex = null;
		}
		if (b64.isEmpty()) {
			return;
		}
		try {
			NativeImage img = NativeImage.read(Base64.getDecoder().decode(b64));
			shotW = img.getWidth();
			shotH = img.getHeight();
			shotTex = new DynamicTexture(() -> "Loothub Feedback", img);
			minecraft.getTextureManager().register(SHOT_ID, shotTex);
		} catch (Exception e) {
			shotTex = null;
		}
	}

	private void setStatus(String st) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "admin_fb_status");
		p.addProperty("id", selected.get("id").getAsLong());
		p.addProperty("status", st);
		Progress.admin(p, res -> minecraft.execute(() -> {
			if (st.equals("delete")) {
				selected = null;
				showShot("");
			} else if (selected != null) {
				selected.addProperty("status", st);
			}
			load();
			rebuildWidgets();
		}));
	}

	private static String str(JsonObject o, String k) {
		return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (bigShot) {
			bigShot = false;
			return true;
		}
		if (e.x() < listW() && e.y() >= 30 && e.y() < height - 32) {
			int i = (int) ((e.y() - 30) / ROW) + scroll;
			if (i >= 0 && i < list.size()) {
				open(list.get(i));
				return true;
			}
		}
		if (shotTex != null && selected != null && inShot(e.x(), e.y())) {
			bigShot = true;
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	private int[] shotBox() {
		int x = listW() + 20, w = width - x - 10;
		int sw = Math.min(w, 220), sh = shotW == 0 ? 0 : sw * shotH / shotW;
		return new int[]{x + w - sw, 30, sw, sh};
	}

	private boolean inShot(double mx, double my) {
		int[] b = shotBox();
		return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		int max = Math.max(0, list.size() - (height - 62) / ROW);
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(dy)));
		return true;
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		if (bigShot && shotTex != null) {
			g.nextStratum();
			g.fill(0, 0, width, height, 0xE0000000);
			int w = width - 40, h = w * shotH / shotW;
			if (h > height - 40) {
				h = height - 40;
				w = h * shotW / shotH;
			}
			g.blit(RenderPipelines.GUI_TEXTURED, SHOT_ID, (width - w) / 2, (height - h) / 2, 0f, 0f, w, h, shotW, shotH, shotW, shotH);
		}
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		super.renderBackground(g, mx, my, delta);
		g.fill(0, 0, width, height, LoothubScreen.FRAME_BG);
		int lw = listW();
		g.drawString(font, Component.literal("✉ ").append(title), 10, 10, LoothubScreen.RED, false);
		g.drawString(font, status, 10, 20, LoothubScreen.FAINT, false);
		g.fill(lw + 10, 30, lw + 11, height - 32, LoothubScreen.FRAME_EDGE);
		// Liste
		g.enableScissor(0, 30, lw, height - 32);
		for (int i = scroll; i < list.size(); i++) {
			int y = 30 + (i - scroll) * ROW;
			if (y > height - 32) {
				break;
			}
			JsonObject r = list.get(i);
			boolean sel = selected != null && selected.get("id").getAsLong() == r.get("id").getAsLong();
			boolean hover = mx < lw && my >= y && my < y + ROW;
			if (sel || hover) {
				g.fill(8, y, lw, y + ROW - 1, sel ? 0x40FFFFFF : 0x20FFFFFF);
			}
			boolean done = "done".equals(str(r, "status"));
			g.fill(8, y, 10, y + ROW - 1, done ? 0xFF55E06A : LoothubScreen.RED);
			boolean unread = r.has("seen") && r.get("seen").getAsInt() == 0;
			if (unread) {
				g.fill(lw - 8, y + 7, lw - 3, y + 12, LoothubScreen.RED); // ungelesen
			}
			String head = (unread ? "● " : "") + DATE.format(Instant.ofEpochMilli(r.get("at").getAsLong())) + "  " + str(r, "name") + "  " + str(r, "ver")
					+ (r.has("shot") && r.get("shot").getAsInt() > 0 ? "  📷" : "");
			g.drawString(font, head, 14, y + 2, done ? LoothubScreen.FAINT : LoothubScreen.MUTED, false);
			g.drawString(font, font.plainSubstrByWidth(str(r, "text").replace('\n', ' '), lw - 20), 14, y + 12,
					done ? LoothubScreen.FAINT : LoothubScreen.TEXT, false);
		}
		g.disableScissor();
		// Details
		if (selected == null) {
			g.drawString(font, Component.translatable("loothub.feedback.pick"), lw + 20, 34, LoothubScreen.FAINT, false);
			return;
		}
		int x = lw + 20, w = width - x - 10;
		int textW = shotTex != null ? w - shotBox()[2] - 8 : w;
		g.drawString(font, str(selected, "name") + " · " + str(selected, "ver") + " · "
				+ DATE.format(Instant.ofEpochMilli(selected.get("at").getAsLong())), x, 30, LoothubScreen.RED, false);
		int y = 44;
		for (FormattedCharSequence line : font.split(Component.literal(str(selected, "text")), textW)) {
			if (y > height - 40) {
				break;
			}
			g.drawString(font, line, x, y, LoothubScreen.TEXT, false);
			y += 10;
		}
		if (shotTex != null) {
			int[] b = shotBox();
			g.blit(RenderPipelines.GUI_TEXTURED, SHOT_ID, b[0], b[1], 0f, 0f, b[2], b[3], shotW, shotH, shotW, shotH);
			g.drawString(font, Component.translatable("loothub.feedback.shot.zoom"), b[0], b[1] + b[3] + 2, LoothubScreen.FAINT, false);
		}
		// Log-Vorschau (letzte Zeilen)
		String log = str(selected, "log");
		if (!log.isEmpty()) {
			y = Math.max(y + 8, shotTex != null ? shotBox()[1] + shotBox()[3] + 16 : y + 8);
			g.drawString(font, Component.translatable("loothub.feedback.log"), x, y, LoothubScreen.FAINT, false);
			y += 11;
			String[] lines = log.split("\n");
			int fit = Math.max(0, (height - 34 - y) / 9);
			for (int i = Math.max(0, lines.length - fit); i < lines.length; i++) {
				g.drawString(font, font.plainSubstrByWidth(lines[i], w), x, y, 0xFF9A8A8C, false);
				y += 9;
			}
		}
	}

	@Override
	public void onClose() {
		showShot("");
		minecraft.setScreen(parent);
	}
}
