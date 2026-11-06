package de.loothub.client.quick;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import de.loothub.client.LoothubScreen;

/**
 * Loothub-Fenster zum Beschriften eines Schildes: vier Zeilen mit Farb- und Format-Knöpfen (&amp;-Codes),
 * Live-Vorschau des Schildes und Zeichen-Grenze wie im Spiel. „Fertig“ schickt die Zeilen wie der normale Editor.
 */
public class SignEditorScreen extends Screen {
	/** Vom Original-Editor bereitgestellt (siehe SignEditMixin). */
	public interface Access {
		String[] loothub$lines();

		SignBlockEntity loothub$sign();

		void loothub$finish(String[] lines);
	}

	private static final String[] CODES = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
	private static final int[] COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
			0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
	private static final String[][] STYLES = {{"l", "B"}, {"o", "I"}, {"n", "U"}, {"m", "S"}, {"r", "↺"}};

	private final Access sign;
	private final String[] original;
	private final EditBox[] lines = new EditBox[4];
	private EditBox active;
	private boolean done;

	public SignEditorScreen(Access sign) {
		super(Component.translatable("loothub.sign.title"));
		this.sign = sign;
		this.original = sign.loothub$lines();
	}

	private int panelW() {
		return Math.min(280, width / 2 - 20);
	}

	private int panelX() {
		return width / 2 + 10;
	}

	private int panelY() {
		return height / 2 - 92;
	}

	private int maxWidth() {
		SignBlockEntity s = sign.loothub$sign();
		return s == null ? 90 : s.getMaxTextLineWidth();
	}

	/** So sieht die Zeile auf dem Schild aus (&amp;-Codes als Farben). */
	private static Component rendered(String raw) {
		return Component.literal(raw.replace('&', '§'));
	}

	@Override
	protected void init() {
		int w = panelW() - 20;
		int x = panelX() + 10;
		int y = panelY() + 26;
		for (int i = 0; i < 4; i++) {
			String keep = lines[i] != null ? lines[i].getValue() : original[i];
			EditBox box = new EditBox(font, x, y + i * 20, w, 16, Component.translatable("loothub.sign.line", i + 1));
			box.setMaxLength(90);
			box.setFilter(s -> font.width(rendered(s)) <= maxWidth()); // passt auf das Schild
			box.setValue(keep == null ? "" : keep);
			box.setHint(Component.translatable("loothub.sign.line", i + 1).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			lines[i] = addRenderableWidget(box);
		}
		active = lines[0];
		setFocused(active);
		int by = y + 84;
		int cw = (w - 7 * 3) / 8;
		for (int i = 0; i < 16; i++) {
			String code = CODES[i];
			addRenderableWidget(Button.builder(Component.empty(), b -> insert("&" + code))
					.bounds(x + (i % 8) * (cw + 3), by + (i / 8) * 16, cw, 13).build());
		}
		for (int i = 0; i < STYLES.length; i++) {
			String[] st = STYLES[i];
			addRenderableWidget(Button.builder(Component.literal(st[1]), b -> insert("&" + st[0]))
					.bounds(x + i * 26, by + 36, 22, 14).build());
		}
		int half = (w - 6) / 2;
		addRenderableWidget(Button.builder(Component.translatable("loothub.color.apply"), b -> finish(true))
				.bounds(x, by + 58, half, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.color.cancel"), b -> finish(false))
				.bounds(x + half + 6, by + 58, half, 20).build());
	}

	private void insert(String s) {
		if (active != null) {
			active.insertText(s);
			setFocused(active);
		}
	}

	@Override
	public void setFocused(net.minecraft.client.gui.components.events.GuiEventListener focused) {
		super.setFocused(focused);
		for (EditBox b : lines) {
			if (b == focused) {
				active = b;
			}
		}
	}

	/** Übernehmen (neue Zeilen) oder Abbrechen (Schild bleibt wie es war). */
	private void finish(boolean apply) {
		if (send(apply)) {
			minecraft.setScreen(null);
		}
	}

	/** Zeilen an den Server schicken (genau einmal). */
	private boolean send(boolean apply) {
		if (done) {
			return false;
		}
		done = true;
		String[] out = new String[4];
		for (int i = 0; i < 4; i++) {
			out[i] = apply && lines[i] != null ? lines[i].getValue() : (original[i] == null ? "" : original[i]);
		}
		sign.loothub$finish(out);
		return true;
	}

	@Override
	public void onClose() {
		finish(true); // wie im normalen Editor: Esc übernimmt
	}

	@Override
	public void removed() {
		send(true); // auch bei anderem Schließen den Server nicht warten lassen (ohne erneut den Bildschirm zu wechseln)
	}

	private static final net.minecraft.resources.Identifier BOARD =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "textures/gui/sign_board.png");
	private static final net.minecraft.resources.Identifier QUILL =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("loothub", "textures/gui/sign_quill.png");

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		int px = panelX(), py = panelY(), pw = panelW(), ph = 200;
		g.fill(px, py, px + pw, py + ph, de.loothub.client.Theme.bg(0xF0140B0D));
		Pulse.outline(g, px, py, pw, ph);
		g.fill(px + 4, py + 3, px + pw - 4, py + 4, LoothubScreen.RED);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, QUILL, px + 9, py + 7, 0f, 0f, 11, 14, 49, 64, 49, 64);
		g.drawString(font, getTitle(), px + 24, py + 10, LoothubScreen.RED, false);
		// Farbfelder auf die Knöpfe
		int w = pw - 20, x = px + 10, by = py + 26 + 84;
		int cw = (w - 7 * 3) / 8;
		for (int i = 0; i < 16; i++) {
			int bx = x + (i % 8) * (cw + 3);
			int bby = by + (i / 8) * 16;
			g.fill(bx + 3, bby + 3, bx + cw - 3, bby + 10, 0xFF000000 | COLORS[i]);
		}
		g.drawString(font, Component.translatable("loothub.rename.codes"), x + STYLES.length * 26 + 4, by + 39, LoothubScreen.FAINT, false);
		// Rechts neben jeder Zeile: wie viel Platz noch ist
		for (int i = 0; i < 4; i++) {
			int used = font.width(rendered(lines[i].getValue()));
			int c = used >= maxWidth() ? LoothubScreen.RED : LoothubScreen.FAINT;
			String left = Math.max(0, maxWidth() - used) + "";
			g.drawString(font, left, px + pw - 6 - font.width(left), py + 26 + i * 20 + 4, c, false);
		}
		// Links: Vorschau des Schildes
		drawPreview(g, px - 24);
	}

	/** Holzschild mit den vier Zeilen, wie es im Spiel aussieht. */
	private void drawPreview(GuiGraphics g, int right) {
		float scale = 1.6F;
		int bw = Math.round(104 * scale), bh = Math.round(bw * 171 / 256F);
		int bx = Math.max(6, right - bw), by = height / 2 - bh / 2 - 20;
		g.drawString(font, Component.translatable("loothub.rename.preview"), bx, by - 12, LoothubScreen.FAINT, false);
		// Pfosten, darüber das Holzschild (eigene Grafik)
		g.fill(bx + bw / 2 - 5, by + bh - 4, bx + bw / 2 + 5, by + bh + Math.round(26 * scale), 0xFF5A3A1E);
		g.fill(bx + bw / 2 - 5, by + bh - 4, bx + bw / 2 - 3, by + bh + Math.round(26 * scale), 0xFF7A5230);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, BOARD, bx, by, 0f, 0f, bw, bh, 256, 171, 256, 171);
		g.pose().pushMatrix();
		g.pose().translate(bx + bw / 2.0F, by + bh / 2.0F - 21 * scale);
		g.pose().scale(scale, scale);
		for (int i = 0; i < 4; i++) {
			Component line = rendered(lines[i].getValue());
			g.drawString(font, line, -font.width(line) / 2, i * 10 + 2, 0xFF000000, false);
		}
		g.pose().popMatrix();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
