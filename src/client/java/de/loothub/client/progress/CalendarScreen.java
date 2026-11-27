package de.loothub.client.progress;

import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Treue-Kalender: der aktuelle Monat, Tage mit Login sind abgehakt. Am Monatsende (beim ersten Login im neuen Monat)
 * gibt es je nach Anzahl Tage Taler – dazu Erfahrung für die Haustiere.
 */
final class CalendarScreen extends Screen {
	private static final int CELL = 26;
	private final Screen parent;

	CalendarScreen(Screen parent) {
		super(Component.translatable("loothub.cal.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width / 2 - 50, height - 26, 100, 20).build());
	}

	private JsonObject cal() {
		JsonObject s = Progress.state();
		return s != null && s.has("cal") && s.get("cal").isJsonObject() ? s.getAsJsonObject("cal") : null;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int cx = width / 2;
		g.drawCenteredString(font, title, cx, 12, 0xFFFFFFFF);
		JsonObject c = cal();
		if (c == null) {
			g.drawCenteredString(font, Component.translatable("loothub.cal.no_data"), cx, height / 2, 0xFFA0A0A0);
			return;
		}
		int year = c.get("y").getAsInt(), mon = c.get("mon").getAsInt(), today = c.get("today").getAsInt();
		int dim = c.get("dim").getAsInt(), first = c.get("first").getAsInt();
		Set<Integer> days = new HashSet<>();
		for (JsonElement e : c.getAsJsonArray("days")) {
			days.add(e.getAsInt());
		}
		Component head = Component.translatable("loothub.cal.month", Component.translatable("loothub.cal.mon." + mon), year);
		g.drawCenteredString(font, head, cx, 28, 0xFFF2C14E);
		int gx = cx - CELL * 7 / 2, gy = 44;
		String[] wd = Component.translatable("loothub.cal.weekdays").getString().split(",");
		for (int i = 0; i < 7 && i < wd.length; i++) {
			g.drawCenteredString(font, wd[i].strip(), gx + i * CELL + CELL / 2, gy, 0xFF8A8A8A);
		}
		gy += 12;
		for (int d = 1; d <= dim; d++) {
			int idx = first + d - 1, col = idx % 7, row = idx / 7;
			int x = gx + col * CELL, y = gy + row * CELL;
			boolean on = days.contains(d);
			int bg = on ? 0xFF2E6B3A : d == today ? 0xFF4A3B1A : d > today ? 0x30FFFFFF : 0x60000000;
			g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, bg);
			if (d == today) {
				int o = 0xFFF2C14E;
				g.fill(x + 1, y + 1, x + CELL - 1, y + 2, o);
				g.fill(x + 1, y + CELL - 2, x + CELL - 1, y + CELL - 1, o);
				g.fill(x + 1, y + 1, x + 2, y + CELL - 1, o);
				g.fill(x + CELL - 2, y + 1, x + CELL - 1, y + CELL - 1, o);
			}
			g.drawString(font, String.valueOf(d), x + 4, y + 4, on ? 0xFFFFFFFF : d > today ? 0xFF707070 : 0xFFB0B0B0, false);
			if (on) {
				g.drawString(font, "✔", x + CELL - 10, y + CELL - 11, 0xFF7CE08A, false);
			}
		}
		int rows = (first + dim + 6) / 7;
		int ty = gy + rows * CELL + 10;
		g.drawCenteredString(font, Component.translatable("loothub.cal.count", days.size(), dim), cx, ty, 0xFFFFFFFF);
		ty += 14;
		JsonArray tiers = c.getAsJsonArray("tiers");
		StringBuilder sb = new StringBuilder();
		for (JsonElement e : tiers) {
			JsonArray t = e.getAsJsonArray();
			int need = t.get(0).getAsInt(), reward = t.get(1).getAsInt();
			if (sb.length() > 0) {
				sb.append("   ");
			}
			sb.append(days.size() >= need ? "✔ " : "○ ").append(Component.translatable("loothub.cal.tier", need, reward).getString());
		}
		g.drawCenteredString(font, sb.toString(), cx, ty, 0xFFF2C14E);
		ty += 14;
		g.drawCenteredString(font, Component.translatable("loothub.cal.info"), cx, ty, 0xFF8A8A8A);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
