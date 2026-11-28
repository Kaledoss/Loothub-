package de.loothub.client.progress;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.loothub.client.Fmt;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Admin: einen Spieler auf dem Loothub-Server ansehen (Guthaben, Geheimnisse, Shop-Artikel, Erfolge, letzter Abgleich)
 * und fehlende Geheimnisse oder Artikel zurückgeben – ohne Taler-Belohnung und ohne Kosten.
 */
public class AdminPlayerScreen extends Screen {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
	private final Screen parent;
	private EditBox nameBox, itemBox;
	private static String lastName = "";
	private JsonObject data;
	private Component status = Component.empty();

	public AdminPlayerScreen(Screen parent) {
		super(Component.translatable("loothub.adminp.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = 12;
		nameBox = addRenderableWidget(new EditBox(font, x, 30, 130, 16, Component.translatable("loothub.lh.admin.player")));
		nameBox.setMaxLength(16);
		nameBox.setHint(Component.translatable("loothub.lh.admin.player").withStyle(ChatFormatting.DARK_GRAY));
		nameBox.setValue(lastName);
		addRenderableWidget(Button.builder(Component.translatable("loothub.adminp.load"), b -> load()).bounds(x + 136, 30, 70, 16).build());
		if (data != null) {
			// fehlende Geheimnisse: je ein Knopf zum Zurückgeben
			Set<String> have = new HashSet<>();
			data.getAsJsonArray("secrets").forEach(e -> have.add(e.getAsString()));
			int bx = x, by = 150;
			for (JsonElement e : data.getAsJsonArray("allSecrets")) {
				String id = e.getAsString();
				if (have.contains(id)) {
					continue;
				}
				Component l = Component.literal("+ ").append(Component.translatable("loothub.lh.secret." + id));
				int w = font.width(l) + 12;
				if (bx + w > width - 12) {
					bx = x;
					by += 20;
				}
				addRenderableWidget(Button.builder(l, b -> grant("secret", id)).bounds(bx, by, w, 16).build());
				bx += w + 4;
			}
			int iy = Math.max(by + 40, 200);
			itemBox = addRenderableWidget(new EditBox(font, x, iy, 130, 16, Component.translatable("loothub.adminp.item")));
			itemBox.setMaxLength(32);
			itemBox.setHint(Component.translatable("loothub.adminp.item").withStyle(ChatFormatting.DARK_GRAY));
			addRenderableWidget(Button.builder(Component.translatable("loothub.adminp.give"), b -> {
				String id = itemBox.getValue().strip();
				if (!id.isEmpty()) {
					grant("item", id);
				}
			}).bounds(x + 136, iy, 90, 16).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(width / 2 - 50, height - 26, 100, 20).build());
	}

	private void load() {
		lastName = nameBox.getValue().strip();
		if (lastName.isEmpty()) {
			return;
		}
		JsonObject p = new JsonObject();
		p.addProperty("op", "admin_player");
		p.addProperty("target", lastName);
		status = Component.translatable("loothub.friends.wait");
		Progress.admin(p, res -> {
			if (res == null) {
				status = Component.translatable("loothub.lh.error.offline").withStyle(ChatFormatting.RED);
			} else if (res.has("ok")) {
				data = res;
				status = Component.empty();
			} else {
				data = null;
				status = Component.translatable("loothub.lh.admin.error." + res.get("error").getAsString()).withStyle(ChatFormatting.RED);
			}
			rebuildWidgets();
		});
	}

	private void grant(String kind, String id) {
		JsonObject p = new JsonObject();
		p.addProperty("op", "admin_grant");
		p.addProperty("target", lastName);
		p.addProperty("kind", kind);
		p.addProperty("id", id);
		Progress.admin(p, res -> {
			if (res != null && res.has("ok")) {
				status = Component.translatable("loothub.adminp.given", id).withStyle(ChatFormatting.GREEN);
				load();
			} else {
				status = Component.translatable(res == null ? "loothub.lh.error.offline" : "loothub.adminp.bad").withStyle(ChatFormatting.RED);
			}
		});
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float d) {
		super.render(g, mx, my, d);
		g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
		g.drawString(font, status, 220, 34, 0xFFFFFFFF);
		if (data == null) {
			return;
		}
		int x = 12, y = 56;
		long points = data.get("points").getAsLong(), spent = data.get("spent").getAsLong();
		long upd = data.get("updated").getAsLong();
		g.drawString(font, Component.literal(data.get("name").getAsString()).withStyle(ChatFormatting.BOLD)
				.append(Component.literal("  " + data.get("uuid").getAsString()).withStyle(ChatFormatting.DARK_GRAY)), x, y, 0xFFFFFFFF);
		y += 12;
		g.drawString(font, Component.translatable("loothub.adminp.taler", Fmt.whole(points - spent), Fmt.whole(points), Fmt.whole(spent)), x, y, 0xFFF2C14E);
		y += 12;
		g.drawString(font, Component.translatable("loothub.adminp.info", data.get("ach").getAsInt(), data.get("quests").getAsInt(),
				upd > 0 ? DATE.format(Instant.ofEpochMilli(upd)) : "–", data.get("ver").getAsString()), x, y, 0xFFB0B0B0);
		y += 16;
		List<String> secrets = new ArrayList<>();
		data.getAsJsonArray("secrets").forEach(e -> secrets.add(Component.translatable("loothub.lh.secret." + e.getAsString()).getString()));
		g.drawString(font, Component.translatable("loothub.adminp.secrets", secrets.size(), data.getAsJsonArray("allSecrets").size()), x, y, 0xFFFFFFFF);
		y += 11;
		for (var line : font.split(Component.literal(secrets.isEmpty() ? "–" : String.join(", ", secrets)), width - 24)) {
			g.drawString(font, line, x + 6, y, 0xFFB0B0B0);
			y += 10;
		}
		y = Math.max(y, 138);
		g.drawString(font, Component.translatable("loothub.adminp.missing"), x, y, 0xFF8A8A8A);
		// Shop-Artikel (rechts unter dem Eingabefeld)
		List<String> owned = new ArrayList<>();
		data.getAsJsonArray("owned").forEach(e -> owned.add(Shop.name(e.getAsString()).getString() + " (" + e.getAsString() + ")"));
		int oy = itemBox != null ? itemBox.getY() + 22 : 220;
		g.drawString(font, Component.translatable("loothub.adminp.owned", owned.size()), x, itemBox != null ? itemBox.getY() - 11 : oy, 0xFFFFFFFF);
		for (var line : font.split(Component.literal(owned.isEmpty() ? "–" : String.join(", ", owned)), width - 24)) {
			if (oy > height - 40) {
				break;
			}
			g.drawString(font, line, x + 6, oy, 0xFFB0B0B0);
			oy += 10;
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
