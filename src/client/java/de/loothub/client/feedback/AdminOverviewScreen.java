package de.loothub.client.feedback;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.loothub.client.LoothubScreen;
import de.loothub.client.progress.Progress;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Admin-Übersicht: aktive Spieler (7 / 30 Tage), welche Mod-Versionen sie nutzen und welche Loothub-Funktionen wie
 * oft eingeschaltet sind – zeigt, was ankommt und was kaum jemand nutzt.
 */
public class AdminOverviewScreen extends Screen {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
	private final Screen parent;
	private JsonObject data;
	private String status = "";
	private int scroll;

	public AdminOverviewScreen(Screen parent) {
		super(Component.translatable("loothub.admin.overview"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("loothub.feedback.reload"), b -> load())
				.bounds(10, height - 26, 80, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width - 90, height - 26, 80, 18).build());
		if (data == null) {
			load();
		}
	}

	private void load() {
		status = Component.translatable("loothub.feedback.loading").getString();
		JsonObject p = new JsonObject();
		p.addProperty("op", "admin_overview");
		Progress.admin(p, res -> minecraft.execute(() -> {
			if (res != null && res.has("ok")) {
				data = res;
				status = "";
			} else {
				status = Component.translatable("loothub.lh.error.offline").getString();
			}
		}));
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
		return true;
	}

	/** „jobWidgetEnabled“ → „job Widget Enabled“ (Namen der Schalter lesbar machen). */
	private static String pretty(String field) {
		return field.replaceAll("([a-z])([A-Z])", "$1 $2");
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		super.renderBackground(g, mx, my, delta);
		g.fill(0, 0, width, height, LoothubScreen.FRAME_BG);
		g.drawString(font, Component.literal("📊 ").append(title), 10, 10, LoothubScreen.RED, false);
		if (data == null) {
			g.drawString(font, status, 10, 26, LoothubScreen.FAINT, false);
			return;
		}
		int a7 = data.get("active7").getAsInt(), a30 = data.get("active30").getAsInt(), wf = data.get("withFeat").getAsInt();
		g.drawString(font, Component.translatable("loothub.admin.active", a7, a30), 10, 24, LoothubScreen.TEXT, false);
		int colW = (width - 30) / 3;
		// Versionen
		int x = 10, y = 42;
		g.drawString(font, Component.translatable("loothub.admin.versions"), x, y, LoothubScreen.MUTED, false);
		y += 12;
		for (JsonElement e : data.getAsJsonArray("versions")) {
			JsonArray v = e.getAsJsonArray();
			int n = v.get(1).getAsInt();
			bar(g, x, y, colW - 10, v.get(0).getAsString(), n, Math.max(1, a7));
			y += 13;
			if (y > height - 40) {
				break;
			}
		}
		// zuletzt aktiv
		y += 8;
		g.drawString(font, Component.translatable("loothub.admin.recent"), x, y, LoothubScreen.MUTED, false);
		y += 12;
		for (JsonElement e : data.getAsJsonArray("recent")) {
			JsonArray r = e.getAsJsonArray();
			if (y > height - 40) {
				break;
			}
			String line = r.get(0).getAsString() + " · " + r.get(1).getAsString() + " · " + DATE.format(Instant.ofEpochMilli(r.get(2).getAsLong()));
			g.drawString(font, font.plainSubstrByWidth(line, colW - 10), x, y, LoothubScreen.FAINT, false);
			y += 10;
		}
		// Funktionen (zwei Spalten, scrollbar)
		int fx = 10 + colW + 10;
		g.drawString(font, Component.translatable("loothub.admin.features", wf), fx, 42, LoothubScreen.MUTED, false);
		List<JsonArray> feats = new ArrayList<>();
		data.getAsJsonArray("features").forEach(e -> feats.add(e.getAsJsonArray()));
		int rows = Math.max(1, (height - 40 - 56) / 13);
		scroll = Math.min(scroll, Math.max(0, feats.size() - rows * 2));
		g.enableScissor(fx, 54, width - 10, height - 34);
		for (int i = scroll; i < feats.size(); i++) {
			int k = i - scroll;
			int col = k / rows, row = k % rows;
			if (col > 1) {
				break;
			}
			JsonArray f = feats.get(i);
			bar(g, fx + col * (colW + 10), 54 + row * 13, colW - 10, FeatureNames.of(f.get(0).getAsString()), f.get(1).getAsInt(), Math.max(1, wf));
		}
		g.disableScissor();
	}

	private void bar(GuiGraphics g, int x, int y, int w, String label, int n, int total) {
		float share = n / (float) total;
		g.fill(x, y, x + w, y + 11, 0x30FFFFFF);
		g.fill(x, y, x + Math.max(1, Math.round(w * share)), y + 11, 0x90E23B3B);
		String right = n + " · " + Math.round(share * 100) + "%";
		g.drawString(font, font.plainSubstrByWidth(label, w - font.width(right) - 8), x + 3, y + 2, LoothubScreen.TEXT, false);
		g.drawString(font, right, x + w - font.width(right) - 3, y + 2, LoothubScreen.MUTED, false);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
