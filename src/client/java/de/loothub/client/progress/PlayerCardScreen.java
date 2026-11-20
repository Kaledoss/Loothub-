package de.loothub.client.progress;

import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import de.loothub.client.Fmt;
import de.loothub.client.MenuSkin;
import de.loothub.client.cosmetics.SkinLookup;

/** Spielerkarte aus der Rangliste: Skin (Vorderansicht), Abzeichen, Titel und die wichtigsten Werte. */
public class PlayerCardScreen extends Screen {
	private final Screen parent;
	private final String uuid;
	private final String name;
	private JsonObject data;
	private boolean failed;

	public PlayerCardScreen(Screen parent, String uuid, String name) {
		super(Component.literal(name));
		this.parent = parent;
		this.uuid = uuid;
		this.name = name;
		Progress.card(uuid, o -> {
			data = o;
			failed = o == null;
		});
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width / 2 - 50, height / 2 + 92, 100, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int w = 300, h = 180;
		int x = (width - w) / 2, y = height / 2 - 90;
		if (MenuSkin.on()) {
			MenuSkin.card(g, x, y, w, h);
		} else {
			g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF4A161B);
			g.fill(x, y, x + w, y + h, 0xF0110B0C);
		}
		// Skin von vorn
		PlayerSkin skin = SkinLookup.get(name);
		if (skin != null) {
			doll(g, skin, x + 18, y + 22, 4);
		} else {
			g.drawCenteredString(font, Component.translatable("loothub.lh.loading"), x + 50, y + 80, 0xFF9A9A9A);
		}
		int tx = x + 100;
		// Name mit Abzeichen, darunter Titel
		Component badge = data == null ? null : Shop.badge(data.get("badge").getAsString());
		Component nameLine = badge == null ? Component.literal(name)
				: Component.empty().append(badge).append(" ").append(Component.literal(name));
		g.drawString(font, nameLine, tx, y + 14, 0xFFFFFFFF, true);
		if (data != null && !data.get("title").getAsString().isEmpty()) {
			g.drawString(font, Shop.titleText(data.get("title").getAsString()), tx, y + 26, 0xFFF2C14E, false);
		}
		if (data == null) {
			g.drawString(font, Component.translatable(failed ? "loothub.lh.card.none" : "loothub.lh.loading"), tx, y + 46,
					0xFF9A9A9A, false);
			return;
		}
		int ry = y + 44;
		ry = row(g, tx, ry, w - 110, "loothub.lh.card.earned", Fmt.whole(data.get("earned").getAsLong()));
		ry = row(g, tx, ry, w - 110, "loothub.lh.card.ach",
				data.get("ach").getAsLong() + " / " + data.get("achMax").getAsLong());
		ry = row(g, tx, ry, w - 110, "loothub.lh.card.login", Component.translatable("loothub.lh.card.login.value",
				days(data.get("loginCur").getAsLong()), days(data.get("loginBest").getAsLong())).getString());
		ry = row(g, tx, ry, w - 110, "loothub.lh.board.dquests", Fmt.whole(data.get("dquests").getAsLong()));
		ry = row(g, tx, ry, w - 110, "loothub.lh.board.wquests", Fmt.whole(data.get("wquests").getAsLong()));
		ry = row(g, tx, ry, w - 110, "loothub.lh.board.blocks", Texts.value("blocks", data.get("blocks").getAsLong()));
		ry = row(g, tx, ry, w - 110, "loothub.lh.board.placed", Texts.value("placed", data.get("placed").getAsLong()));
		ry = row(g, tx, ry, w - 110, "loothub.lh.board.mobs", Texts.value("mobs", data.get("mobs").getAsLong()));
		ry = row(g, tx, ry, w - 110, "loothub.lh.board.fish", Texts.value("fish", data.get("fish").getAsLong()));
		ry = row(g, tx, ry, w - 110, "loothub.lh.board.play", Texts.value("play", data.get("play").getAsLong()));
		row(g, tx, ry, w - 110, "loothub.lh.board.jobday", Texts.value("jobmoney", data.get("jobday").getAsLong()));
	}

	private String days(long n) {
		return Component.translatable(n == 1 ? "loothub.lh.day" : "loothub.lh.days", n).getString();
	}

	private int row(GuiGraphics g, int x, int y, int w, String key, String value) {
		g.drawString(font, Component.translatable(key), x, y, 0xFFB9A9AB, false);
		g.drawString(font, value, x + w - font.width(value), y, 0xFFFFFFFF, false);
		return y + 11;
	}

	/** Skin als Vorderansicht (Kopf, Körper, Arme, Beine samt zweiter Schicht), k = Bildpunkte je Skin-Pixel. */
	private static void doll(GuiGraphics g, PlayerSkin skin, int x, int y, int k) {
		Identifier tex = skin.body().texturePath();
		int arm = skin.model() == PlayerModelType.SLIM ? 3 : 4;
		int ax = 4 - arm; // schmale Arme näher am Körper
		// Schicht 1, dann Schicht 2 darüber
		part(g, tex, x + 4 * k, y, 8, 8, 8, 8, k);            // Kopf
		part(g, tex, x + 4 * k, y + 8 * k, 20, 20, 8, 12, k);  // Körper
		part(g, tex, x + ax * k, y + 8 * k, 44, 20, arm, 12, k);          // rechter Arm
		part(g, tex, x + 12 * k, y + 8 * k, 36, 52, arm, 12, k);          // linker Arm
		part(g, tex, x + 4 * k, y + 20 * k, 4, 20, 4, 12, k);  // rechtes Bein
		part(g, tex, x + 8 * k, y + 20 * k, 20, 52, 4, 12, k); // linkes Bein
		part(g, tex, x + 4 * k, y, 40, 8, 8, 8, k);            // Hut
		part(g, tex, x + 4 * k, y + 8 * k, 20, 36, 8, 12, k);  // Jacke
		part(g, tex, x + ax * k, y + 8 * k, 44, 36, arm, 12, k);
		part(g, tex, x + 12 * k, y + 8 * k, 52, 52, arm, 12, k);
		part(g, tex, x + 4 * k, y + 20 * k, 4, 36, 4, 12, k);
		part(g, tex, x + 8 * k, y + 20 * k, 4, 52, 4, 12, k);
	}

	private static void part(GuiGraphics g, Identifier tex, int x, int y, int u, int v, int w, int h, int k) {
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w * k, h * k, w, h, 64, 64);
	}
}
