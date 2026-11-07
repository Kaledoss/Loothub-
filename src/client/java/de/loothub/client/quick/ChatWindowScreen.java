package de.loothub.client.quick;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import de.loothub.client.ColorPickerScreen;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Einstellungen eines Chat-Fensters: oben Aussehen (Farben, Deckkraft, Textgröße, Ausblenden, Aufhellen), darunter die
 * Tabs als Reiter und für den gewählten Tab: Name, Quellen zum Anklicken, „eigener Tab“ und optionale Wortregeln.
 */
public class ChatWindowScreen extends Screen {
	private final CustomChat.Window win;
	private int px, py, pw, ph;
	private int sourcesY;
	private int listY;
	private EditBox incBox, excBox;
	private static int incScroll, excScroll;
	private static final int ENTRY_H = 13;
	/** Gerade bearbeiteter Eintrag (Doppelklick): Spalte 0 = enthält, 1 = enthält nicht; −1 = keiner. */
	private int editCol = -1, editIndex = -1;
	private EditBox editBox;

	public ChatWindowScreen(CustomChat.Window win) {
		super(Component.translatable("loothub.chatwin.title"));
		this.win = win;
	}

	@Override
	protected void init() {
		editBox = null;
		pw = Math.min(480, width - 20);
		ph = Math.min(380, height - 16);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		int x = px + 10, y = py + 22, w = pw - 20;
		int half = (w - 4) / 2;

		// Aussehen
		addRenderableWidget(Button.builder(colorLabel("loothub.chatwin.bg", win.bg), b -> minecraft.setScreen(
				new ColorPickerScreen(this, Component.translatable("loothub.chatwin.bg"), win.bg, rgb -> {
					win.bg = rgb;
					WidgetConfig.get().save();
				}))).bounds(x, y, half, 16).build());
		addRenderableWidget(Button.builder(colorLabel("loothub.chatwin.accent", win.accent), b -> minecraft.setScreen(
				new ColorPickerScreen(this, Component.translatable("loothub.chatwin.accent"), win.accent, rgb -> {
					win.accent = rgb;
					WidgetConfig.get().save();
				}))).bounds(x + half + 4, y, half, 16).build());
		y += 20;
		addRenderableWidget(slider(x, y, half, "loothub.chatwin.opacity", win.opacity, 0, 100, "%", v -> win.opacity = v));
		addRenderableWidget(slider(x + half + 4, y, half, "loothub.chatwin.scale", win.textScale, 50, 200, "%", v -> win.textScale = v));
		y += 20;
		addRenderableWidget(slider(x, y, half, "loothub.chatwin.fade", win.fadeSec, 1, 120, " s", v -> win.fadeSec = v));
		addRenderableWidget(slider(x + half + 4, y, half, "loothub.chatwin.bright", win.minBright, 0, 100, "%", v -> win.minBright = v));

		// Tab-Reiter
		y += 28;
		List<CustomChat.Tab> tabs = win.tabs;
		CustomChat.Tab tab = win.tab();
		int tx = x;
		for (int i = 0; i < tabs.size(); i++) {
			final int idx = i;
			CustomChat.Tab t = tabs.get(i);
			int bw = Math.min(90, font.width(t.name) + 14);
			if (tx + bw > x + w - 50) {
				break;
			}
			Button b = addRenderableWidget(Button.builder(Component.literal(t.name), btn -> {
				win.active = idx;
				rebuildWidgets();
			}).bounds(tx, y, bw, 16).build());
			b.active = i != win.active;
			tx += bw + 2;
		}
		addRenderableWidget(Button.builder(Component.literal("+").withStyle(ChatFormatting.GREEN), b -> {
			CustomChat.Tab t = new CustomChat.Tab();
			t.name = "Tab " + (tabs.size() + 1);
			tabs.add(t);
			win.active = tabs.size() - 1;
			changed();
			rebuildWidgets();
		}).bounds(tx, y, 16, 16).build());

		// Gewählter Tab
		y += 22;
		EditBox name = box(x + 40, y, 120, tab.name, 20, "loothub.chatwin.tab.name", v -> tab.name = v.isBlank() ? "Tab" : v);
		addRenderableWidget(Button.builder(exclusiveLabel(tab.exclusive), b -> {
			tab.exclusive = !tab.exclusive;
			b.setMessage(exclusiveLabel(tab.exclusive));
			changed();
		}).bounds(x + 164, y, w - 164 - 70, 16).build());
		Button del = addRenderableWidget(Button.builder(Component.translatable("loothub.chatwin.tab.delete").withStyle(ChatFormatting.RED), b -> {
			tabs.remove(tab);
			win.active = Math.max(0, win.active - 1);
			changed();
			rebuildWidgets();
		}).bounds(x + w - 66, y, 66, 16).build());
		del.active = tabs.size() > 1;

		// Quellen als Schalter
		y += 22;
		sourcesY = y;
		y += 12;
		int sx = x;
		for (String src : CustomChat.knownSources()) {
			int bw = font.width(src) + 18;
			if (sx + bw > x + w) {
				sx = x;
				y += 18;
			}
			if (y > sourcesY + 12 + 18) {
				break; // höchstens zwei Reihen
			}
			addRenderableWidget(Button.builder(sourceLabel(tab, src), b -> {
				tab.toggleSource(src);
				b.setMessage(sourceLabel(tab, src));
				changed();
			}).bounds(sx, y, bw, 16).build());
			sx += bw + 3;
		}

		// Wortregeln: zwei Listen, jeder Eintrag sichtbar
		y = Math.min(y, sourcesY + 30) + 22;
		listY = y;
		int colW = (w - 6) / 2;
		incBox = box(x, y + 10, colW - 20, "", 100, "loothub.chatwin.tab.include", v -> {
		});
		addRenderableWidget(Button.builder(Component.literal("+").withStyle(ChatFormatting.GREEN), b -> addWord(true))
				.bounds(x + colW - 18, y + 10, 18, 16).build());
		excBox = box(x + colW + 6, y + 10, colW - 20, "", 100, "loothub.chatwin.tab.exclude", v -> {
		});
		addRenderableWidget(Button.builder(Component.literal("+").withStyle(ChatFormatting.GREEN), b -> addWord(false))
				.bounds(x + w - 18, y + 10, 18, 16).build());
		entryButtons(tab, true, x, colW);
		entryButtons(tab, false, x + colW + 6, colW);

		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(px + pw - 90, py + ph - 22, 80, 16).build());
		if (editBox == null) {
			setInitialFocus(name);
		}
	}

	private int entriesTop() {
		return listY + 30;
	}

	private int entriesVisible() {
		return Math.max(1, (py + ph - 26 - entriesTop()) / ENTRY_H);
	}

	static List<String> words(String s) {
		List<String> out = new java.util.ArrayList<>();
		if (s != null) {
			for (String w : s.split(",")) {
				if (!w.isBlank()) {
					out.add(w.strip());
				}
			}
		}
		return out;
	}

	private void entryButtons(CustomChat.Tab tab, boolean include, int x, int colW) {
		List<String> list = words(include ? tab.include : tab.exclude);
		int scroll = Mth.clamp(include ? incScroll : excScroll, 0, Math.max(0, list.size() - entriesVisible()));
		if (include) {
			incScroll = scroll;
		} else {
			excScroll = scroll;
		}
		for (int i = 0; i < entriesVisible() && i + scroll < list.size(); i++) {
			String word = list.get(i + scroll);
			if (editCol == (include ? 0 : 1) && editIndex == i + scroll) {
				editBox = addRenderableWidget(new EditBox(font, x + 4, entriesTop() + i * ENTRY_H + 2, colW - 22, 10, Component.empty()));
				editBox.setMaxLength(100);
				editBox.setBordered(false);
				editBox.setValue(word);
				setFocused(editBox);
				editBox.setFocused(true);
			}
			addRenderableWidget(Button.builder(Component.literal("✕").withStyle(ChatFormatting.RED), b -> {
				List<String> l = words(include ? tab.include : tab.exclude);
				l.remove(word);
				if (include) {
					tab.include = String.join(", ", l);
				} else {
					tab.exclude = String.join(", ", l);
				}
				changed();
				rebuildWidgets();
			}).bounds(x + colW - 14, entriesTop() + i * ENTRY_H, 14, 12).build());
		}
	}

	private void addWord(boolean include) {
		EditBox box = include ? incBox : excBox;
		String v = box.getValue().replace(",", " ").strip();
		if (v.isEmpty()) {
			return;
		}
		CustomChat.Tab tab = win.tab();
		List<String> l = words(include ? tab.include : tab.exclude);
		if (l.stream().noneMatch(x -> x.equalsIgnoreCase(v))) {
			l.add(v);
		}
		if (include) {
			tab.include = String.join(", ", l);
		} else {
			tab.exclude = String.join(", ", l);
		}
		changed();
		rebuildWidgets();
		setFocused(include ? incBox : excBox);
	}

	/** Bearbeiteten Eintrag übernehmen (leer = löschen). */
	private void commitEdit() {
		if (editCol < 0 || editBox == null) {
			editCol = -1;
			return;
		}
		CustomChat.Tab tab = win.tab();
		boolean include = editCol == 0;
		List<String> l = words(include ? tab.include : tab.exclude);
		if (editIndex >= 0 && editIndex < l.size()) {
			String v = editBox.getValue().replace(",", " ").strip();
			if (v.isEmpty()) {
				l.remove(editIndex);
			} else {
				l.set(editIndex, v);
			}
			if (include) {
				tab.include = String.join(", ", l);
			} else {
				tab.exclude = String.join(", ", l);
			}
			changed();
		}
		editCol = -1;
		editBox = null;
		rebuildWidgets();
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (editBox != null && !editBox.isMouseOver(event.x(), event.y())) {
			commitEdit(); // woanders hingeklickt: Änderung übernehmen
		}
		if (doubleClick && event.y() >= entriesTop() && event.y() < entriesTop() + entriesVisible() * ENTRY_H) {
			int x = px + 10, w = pw - 20, colW = (w - 6) / 2;
			int col = event.x() >= x && event.x() < x + colW - 16 ? 0
					: event.x() >= x + colW + 6 && event.x() < x + w - 16 ? 1 : -1;
			if (col >= 0) {
				CustomChat.Tab tab = win.tab();
				int idx = (int) ((event.y() - entriesTop()) / ENTRY_H) + (col == 0 ? incScroll : excScroll);
				if (idx < words(col == 0 ? tab.include : tab.exclude).size()) {
					editCol = col;
					editIndex = idx;
					rebuildWidgets();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int k = event.key();
		if (editBox != null && editCol >= 0) {
			if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || k == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
				commitEdit();
				return true;
			}
			if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
				editCol = -1;
				editBox = null;
				rebuildWidgets();
				return true;
			}
		}
		if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || k == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
			if (incBox != null && incBox.isFocused()) {
				addWord(true);
				return true;
			}
			if (excBox != null && excBox.isFocused()) {
				addWord(false);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		if (my >= entriesTop()) {
			if (mx < px + pw / 2.0) {
				incScroll = Math.max(0, incScroll - (int) Math.signum(v));
			} else {
				excScroll = Math.max(0, excScroll - (int) Math.signum(v));
			}
			rebuildWidgets();
			return true;
		}
		return super.mouseScrolled(mx, my, h, v);
	}

	private EditBox box(int x, int y, int w, String value, int max, String hintKey, java.util.function.Consumer<String> set) {
		EditBox e = addRenderableWidget(new EditBox(font, x, y, w, 16, Component.translatable(hintKey)));
		e.setMaxLength(max);
		e.setValue(value == null ? "" : value);
		e.setHint(Component.translatable(hintKey).withStyle(ChatFormatting.DARK_GRAY));
		e.setResponder(v -> {
			set.accept(v);
			changed();
		});
		return e;
	}

	private static void changed() {
		CustomChat.invalidate();
		WidgetConfig.get().save();
	}

	private static Component sourceLabel(CustomChat.Tab t, String src) {
		boolean on = t.hasSource(src);
		return Component.literal((on ? "✔ " : "") + src).withStyle(on ? ChatFormatting.GOLD : ChatFormatting.GRAY);
	}

	private static Component exclusiveLabel(boolean on) {
		return Component.translatable("loothub.chatwin.tab.exclusive").append(": ")
				.append(Component.translatable(on ? "options.on" : "options.off").withStyle(on ? ChatFormatting.GOLD : ChatFormatting.GRAY));
	}

	private static Component colorLabel(String key, int rgb) {
		return Component.translatable(key).append(Component.literal("  ■").withStyle(s -> s.withColor(rgb & 0xFFFFFF)));
	}

	private AbstractSliderButton slider(int x, int y, int w, String key, int value, int min, int max, String unit,
			java.util.function.IntConsumer set) {
		return new AbstractSliderButton(x, y, w, 16, Component.empty(), Mth.clamp((value - min) / (double) (max - min), 0, 1)) {
			{
				updateMessage();
			}

			private int current() {
				return (int) Math.round(min + this.value * (max - min));
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.translatable(key).append(": " + current() + unit));
			}

			@Override
			protected void applyValue() {
				set.accept(current());
				WidgetConfig.get().save();
			}
		};
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		g.fill(px, py, px + pw, py + ph, de.loothub.client.Theme.bg(0xF0140B0D));
		Pulse.outline(g, px, py, pw, ph);
		g.fill(px + 4, py + 3, px + pw - 4, py + 4, LoothubScreen.RED);
		g.drawString(font, getTitle(), px + 10, py + 9, LoothubScreen.RED, false);
		int x = px + 10;
		g.drawString(font, Component.translatable("loothub.chatwin.tabs"), x, py + 80, LoothubScreen.FAINT, false);
		g.drawString(font, Component.translatable("loothub.chatwin.tab.name"), x, py + 116, LoothubScreen.TEXT, false);
		CustomChat.Tab tab = win.tab();
		Component src = Component.translatable(tab.sources == null || tab.sources.isBlank()
				? "loothub.chatwin.sources.all" : "loothub.chatwin.sources");
		g.drawString(font, src, x, sourcesY, LoothubScreen.FAINT, false);
		int w = pw - 20, colW = (w - 6) / 2;
		g.drawString(font, Component.translatable("loothub.chatwin.col.include").withStyle(ChatFormatting.GREEN), x, listY, LoothubScreen.TEXT, false);
		g.drawString(font, Component.translatable("loothub.chatwin.col.exclude").withStyle(ChatFormatting.RED), x + colW + 6, listY, LoothubScreen.TEXT, false);
		for (int col = 0; col < 2; col++) {
			boolean include = col == 0;
			int cx = x + col * (colW + 6);
			List<String> list = words(include ? tab.include : tab.exclude);
			int scroll = include ? incScroll : excScroll;
			g.fill(cx, entriesTop() - 2, cx + colW, entriesTop() + entriesVisible() * ENTRY_H, 0x30000000);
			if (list.isEmpty()) {
				g.drawString(font, Component.translatable(include ? "loothub.chatwin.include.empty" : "loothub.chatwin.exclude.empty"),
						cx + 4, entriesTop() + 2, LoothubScreen.FAINT, false);
			}
			for (int i = 0; i < entriesVisible() && i + scroll < list.size(); i++) {
				int ry = entriesTop() + i * ENTRY_H;
				boolean editing = editCol == col && editIndex == i + scroll;
				g.fill(cx, ry, cx + colW, ry + ENTRY_H - 1, editing ? 0xFF000000 : 0x18FFFFFF);
				if (editing) {
					g.fill(cx, ry + ENTRY_H - 2, cx + colW - 16, ry + ENTRY_H - 1, LoothubScreen.RED);
				} else {
					g.drawString(font, font.plainSubstrByWidth(list.get(i + scroll), colW - 22), cx + 4, ry + 2, LoothubScreen.TEXT, false);
				}
			}
			if (list.size() > entriesVisible()) {
				String more = (scroll + entriesVisible()) + "/" + list.size();
				g.drawString(font, more, cx + colW - font.width(more), listY, LoothubScreen.FAINT, false);
			}
		}
		super.render(g, mx, my, delta);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
	}

	@Override
	public void onClose() {
		changed();
		minecraft.setScreen(null);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
