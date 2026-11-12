package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import de.loothub.client.ColorPickerScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * „/chat“: Nachricht in Ruhe vorbereiten – mit Farbcodes (&a, &l …, auch &#RRGGBB) und Live-Vorschau, wie sie im
 * Chat aussehen wird. Gesendet wird nur auf Knopfdruck (oder kopieren und selbst einfügen). Die letzten Entwürfe
 * bleiben erhalten. Im Notizblock lassen sich häufige Sätze speichern und per Klick wieder einsetzen.
 */
public class ChatComposerScreen extends Screen {
	private static final String CODES = "0123456789abcdef";
	private static final int MAX = 256;

	private final String initial;
	private EditBox box;
	private int previewY;
	private int notesY;
	private int notesScroll;
	private static final int NOTE_H = 14;

	public ChatComposerScreen(String initial) {
		super(Component.translatable("loothub.composer.title"));
		this.initial = initial == null ? "" : initial;
	}

	@Override
	protected void init() {
		int w = Math.min(420, width - 40);
		int x = (width - w) / 2;
		int y = Math.max(30, height / 2 - 90);
		String old = box != null ? box.getValue() : initial.isEmpty() ? WidgetConfig.get().chatDraft : initial;
		box = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.translatable("loothub.composer.title")));
		box.setMaxLength(MAX);
		box.setValue(old == null ? "" : old);
		box.setHint(Component.translatable("loothub.composer.hint"));
		setInitialFocus(box);

		// Farben: 16 Felder
		int cw = (w - 15 * 2) / 16;
		for (int i = 0; i < 16; i++) {
			char code = CODES.charAt(i);
			ChatFormatting f = ChatFormatting.getByCode(code);
			int bx = x + i * (cw + 2);
			addRenderableWidget(Button.builder(Component.literal("■").withStyle(f), b -> insert("&" + code))
					.bounds(bx, y + 24, cw, 16).tooltip(net.minecraft.client.gui.components.Tooltip.create(
							Component.literal("&" + code + "  ").append(Component.translatable("loothub.composer.color." + code))))
					.build());
		}
		// Formate + eigene Farbe
		String[][] fmt = {{"l", "B"}, {"o", "I"}, {"n", "U"}, {"m", "S"}, {"k", "?"}, {"r", "↺"}};
		int fw = 26;
		for (int i = 0; i < fmt.length; i++) {
			String code = fmt[i][0];
			ChatFormatting f = ChatFormatting.getByCode(code.charAt(0));
			MutableComponent label = Component.literal(fmt[i][1]);
			if (f != null && f != ChatFormatting.RESET && f != ChatFormatting.OBFUSCATED) {
				label = label.withStyle(f);
			}
			addRenderableWidget(Button.builder(label, b -> insert("&" + code)).bounds(x + i * (fw + 2), y + 44, fw, 16)
					.tooltip(net.minecraft.client.gui.components.Tooltip.create(
							Component.literal("&" + code + "  ").append(Component.translatable("loothub.composer.format." + code))))
					.build());
		}
		addRenderableWidget(Button.builder(Component.translatable("loothub.composer.hex"), b ->
				minecraft.setScreen(new ColorPickerScreen(this, Component.translatable("loothub.composer.hex"), 0xE23B3B,
						rgb -> insert(String.format("&#%06X", rgb & 0xFFFFFF)))))
				.bounds(x + fmt.length * (fw + 2) + 4, y + 44, 90, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.composer.note.save"), b -> saveNote())
				.bounds(x + w - 130, y + 44, 130, 16)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("loothub.composer.note.save.tooltip")))
				.build());

		previewY = y + 72;
		int by = previewY + 44;
		int bw = (w - 8) / 3;
		addRenderableWidget(Button.builder(Component.translatable("loothub.composer.send"), b -> send())
				.bounds(x, by, bw, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.composer.copy"), b -> {
			minecraft.keyboardHandler.setClipboard(box.getValue());
			saveDraft();
		}).bounds(x + bw + 4, by, bw, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(x + 2 * (bw + 4), by, bw, 18).build());
		notesY = by + 28;
	}

	private List<String> notes() {
		WidgetConfig c = WidgetConfig.get();
		if (c.chatNotes == null) {
			c.chatNotes = new ArrayList<>();
		}
		return c.chatNotes;
	}

	private void saveNote() {
		String text = box.getValue().strip();
		if (text.isEmpty()) {
			return;
		}
		List<String> n = notes();
		n.remove(text); // doppelte vermeiden, neu gespeichert steht oben
		n.add(0, text);
		WidgetConfig.get().save();
		notesScroll = 0;
	}

	/** Wie viele Notizzeilen auf den Bildschirm passen. */
	private int visibleNotes() {
		return Math.max(1, (height - notesY - 22) / NOTE_H);
	}

	private void insert(String s) {
		String v = box.getValue();
		int cur = box.getCursorPosition();
		if (v.length() + s.length() > MAX) {
			return;
		}
		box.setValue(v.substring(0, cur) + s + v.substring(cur));
		box.setCursorPosition(cur + s.length());
		setFocused(box);
	}

	private void saveDraft() {
		WidgetConfig c = WidgetConfig.get();
		c.chatDraft = box.getValue();
		c.save();
	}

	/** Absenden auf Knopfdruck – wie selbst getippt (Befehle mit „/“ gehen als Befehl). */
	private void send() {
		String text = box.getValue().strip();
		if (text.isEmpty() || minecraft.getConnection() == null) {
			return;
		}
		WidgetConfig.get().chatDraft = "";
		WidgetConfig.get().save();
		if (text.startsWith("/")) {
			// über die Befehlsbremse: höchstens ein Befehl je 1,2 s, und jeder Befehl steht mit Quelle im Log
			CommandGuard.send(text, "Nachrichten-Editor");
		} else {
			de.loothub.Loothub.LOGGER.info("Loothub: sende Chatnachricht (Nachrichten-Editor)");
			minecraft.getConnection().sendChat(text);
		}
		onClose();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int w = Math.min(420, width - 40);
		int x = (width - w) / 2;
		g.drawCenteredString(font, title, width / 2, box.getY() - 14, 0xFFFFFFFF);
		// Vorschau wie im Chat
		g.fill(x, previewY, x + w, previewY + 36, 0x90000000);
		g.drawString(font, Component.translatable("loothub.composer.preview"), x + 4, previewY + 3, 0xFF9A9A9A, false);
		List<net.minecraft.util.FormattedCharSequence> lines = font.split(parse(box.getValue()), w - 8);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			g.drawString(font, lines.get(i), x + 4, previewY + 14 + i * 10, 0xFFFFFFFF, true);
		}
		renderNotes(g, x, w, mouseX, mouseY);
		String count = box.getValue().length() + " / " + MAX;
		g.drawString(font, count, x + w - font.width(count), box.getY() - 12, 0xFF9A9A9A, false);
	}

	private void renderNotes(GuiGraphics g, int x, int w, int mouseX, int mouseY) {
		List<String> n = notes();
		g.drawString(font, Component.translatable("loothub.composer.notes", n.size()), x, notesY, 0xFFFFFFFF, true);
		int top = notesY + 12;
		if (n.isEmpty()) {
			g.drawString(font, Component.translatable("loothub.composer.notes.empty"), x + 4, top + 3, 0xFF9A9A9A, false);
			return;
		}
		int vis = Math.min(visibleNotes(), n.size());
		notesScroll = Math.max(0, Math.min(notesScroll, n.size() - vis));
		g.fill(x, top, x + w, top + vis * NOTE_H, 0x90000000);
		for (int i = 0; i < vis; i++) {
			int idx = i + notesScroll;
			int ry = top + i * NOTE_H;
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + NOTE_H;
			if (hover) {
				g.fill(x, ry, x + w, ry + NOTE_H, 0x40FFFFFF);
			}
			Component line = parse(n.get(idx));
			String shown = font.plainSubstrByWidth(line.getString(), w - 70);
			List<net.minecraft.util.FormattedCharSequence> cut = font.split(line, w - 70);
			if (!cut.isEmpty()) {
				g.drawString(font, cut.get(0), x + 4, ry + 3, 0xFFFFFFFF, true);
			}
			if (cut.size() > 1 || shown.length() < line.getString().length()) {
				g.drawString(font, "…", x + w - 64, ry + 3, 0xFF9A9A9A, false);
			}
			// Aktionen rechts: senden ▶, löschen ✕
			boolean overSend = hover && mouseX >= x + w - 40 && mouseX < x + w - 22;
			boolean overDel = hover && mouseX >= x + w - 20;
			g.drawString(font, "▶", x + w - 34, ry + 3, overSend ? 0xFF55FF55 : 0xFFAAAAAA, false);
			g.drawString(font, "✕", x + w - 14, ry + 3, overDel ? 0xFFFF5555 : 0xFFAAAAAA, false);
		}
		if (n.size() > vis) {
			String more = (notesScroll + 1) + "–" + (notesScroll + vis) + " / " + n.size();
			g.drawString(font, more, x + w - font.width(more), notesY, 0xFF9A9A9A, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		int w = Math.min(420, width - 40);
		int x = (width - w) / 2;
		int top = notesY + 12;
		List<String> n = notes();
		int vis = Math.min(visibleNotes(), n.size());
		if (e.button() != 0 || e.x() < x || e.x() >= x + w || e.y() < top || e.y() >= top + vis * NOTE_H) {
			return false;
		}
		int idx = (int) ((e.y() - top) / NOTE_H) + notesScroll;
		if (idx < 0 || idx >= n.size()) {
			return false;
		}
		String text = n.get(idx);
		if (e.x() >= x + w - 20) {
			n.remove(idx);
			WidgetConfig.get().save();
		} else if (e.x() >= x + w - 40 && e.x() < x + w - 22) {
			box.setValue(text);
			send();
		} else {
			box.setValue(text); // in das Feld übernehmen, dort weiter bearbeiten
			box.moveCursorToEnd(false);
			setFocused(box);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (my >= notesY) {
			notesScroll -= (int) Math.signum(vertical);
			notesScroll = Math.max(0, notesScroll);
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}

	/** Text mit &-Codes (und &#RRGGBB) in eine farbige Vorschau umwandeln. */
	public static Component parse(String s) {
		MutableComponent out = Component.empty();
		Style style = Style.EMPTY;
		StringBuilder buf = new StringBuilder();
		List<Object[]> parts = new ArrayList<>();
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if ((ch == '&' || ch == '§') && i + 1 < s.length()) {
				char n = Character.toLowerCase(s.charAt(i + 1));
				if (n == '#' && i + 7 < s.length() && s.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
					parts.add(new Object[]{buf.toString(), style});
					buf.setLength(0);
					style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(s.substring(i + 2, i + 8), 16)));
					i += 7;
					continue;
				}
				ChatFormatting f = ChatFormatting.getByCode(n);
				if (f != null) {
					parts.add(new Object[]{buf.toString(), style});
					buf.setLength(0);
					style = f == ChatFormatting.RESET ? Style.EMPTY : f.isColor() ? Style.EMPTY.applyFormat(f) : style.applyFormat(f);
					i++;
					continue;
				}
			}
			buf.append(ch);
		}
		parts.add(new Object[]{buf.toString(), style});
		for (Object[] p : parts) {
			if (!((String) p[0]).isEmpty()) {
				out.append(Component.literal((String) p[0]).withStyle((Style) p[1]));
			}
		}
		return out;
	}

	@Override
	public void onClose() {
		saveDraft();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
