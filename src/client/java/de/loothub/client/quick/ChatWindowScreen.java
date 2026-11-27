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

		// Zwei Seiten: Aussehen | Tabs & Filter – damit nicht alles auf einmal auf dem Bildschirm steht
		Button p0 = addRenderableWidget(Button.builder(Component.translatable("loothub.chatwin.page.look"), b -> {
			page = 0;
			rebuildWidgets();
		}).bounds(x, y, half, 18).build());
		Button p1 = addRenderableWidget(Button.builder(Component.translatable("loothub.chatwin.page.tabs"), b -> {
			page = 1;
			rebuildWidgets();
		}).bounds(x + half + 4, y, half, 18).build());
		p0.active = page != 0;
		p1.active = page != 1;
		y += 26;
		if (page == 0) {
			initLook(x, y, w, half);
			addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
					.bounds(px + pw - 90, py + ph - 22, 80, 16).build());
			return;
		}
		initTabs(x, y, w);
	}

	/** Fertige Stile: Hintergrund, Akzent, Deckkraft. */
	private static final Object[][] PRESETS = {
			{"loothub", 0x140B0D, 0xE23B3B, 70}, {"glass", 0x000000, 0xFFFFFF, 25}, {"night", 0x0A0F1E, 0x4DA3FF, 75},
			{"gold", 0x1A1408, 0xF2C14E, 70}, {"forest", 0x0B140D, 0x55E06A, 65}};
	private static int page;
	private int previewY;

	private void initLook(int x, int y, int w, int half) {
		// Stil-Vorlagen mit einem Klick
		int n = PRESETS.length, bw = (w - (n - 1) * 3) / n;
		for (int i = 0; i < n; i++) {
			Object[] pr = PRESETS[i];
			int accent = (int) pr[2];
			addRenderableWidget(Button.builder(Component.literal("■ ").withStyle(st -> st.withColor(accent))
					.append(Component.translatable("loothub.chatwin.preset." + pr[0]).withStyle(ChatFormatting.WHITE)), b -> {
						win.bg = (int) pr[1];
						win.accent = (int) pr[2];
						win.opacity = (int) pr[3];
						changed();
						rebuildWidgets();
					}).bounds(x + i * (bw + 3), y + 10, bw, 18).build());
		}
		y += 36;
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
		previewY = y + 30;
	}

	private int tabsLabelY, nameY;

	private void moveTab(CustomChat.Tab tab, int dir) {
		int i = win.tabs.indexOf(tab), j = i + dir;
		if (i < 0 || j < 0 || j >= win.tabs.size()) {
			return;
		}
		win.tabs.remove(i);
		win.tabs.add(j, tab);
		win.active = j;
		changed();
		rebuildWidgets();
	}

	/** Neuen Tab anlegen; mit Quelle als „eigener Tab“ (nimmt seine Nachrichten den anderen Tabs weg). */
	private void addTab(String name, String source, boolean exclusive) {
		CustomChat.Tab t = new CustomChat.Tab();
		t.name = name;
		t.sources = source;
		t.exclusive = exclusive;
		win.tabs.add(t);
		win.active = win.tabs.size() - 1;
		changed();
		minecraft.setScreen(this);
	}

	private void initTabs(int x, int y, int w) {
		// Tab-Reiter
		tabsLabelY = y;
		y += 10;
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
		final int plusX = tx, plusY = y + 18;
		addRenderableWidget(Button.builder(Component.literal("+").withStyle(ChatFormatting.GREEN), b -> {
			// Neuer Tab: leer oder fertig eingestellt (Clan, Privat, Handel …) – mit einem Klick
			List<de.loothub.client.MoreMenuScreen.Entry> items = new java.util.ArrayList<>();
			items.add(new de.loothub.client.MoreMenuScreen.Entry(Component.translatable("loothub.chatwin.preset.empty"),
					() -> addTab("Tab " + (tabs.size() + 1), "", false)));
			String[][] presets = {{"Clan", CustomChat.SRC_CLAN}, {"Privat", CustomChat.SRC_PM}, {"Handel", CustomChat.SRC_TRADE},
					{"Freunde", "FREUNDE"}, {"Server", "OPSUCHT"}, {"Twitch", "TWITCH"}};
			for (String[] pr : presets) {
				items.add(new de.loothub.client.MoreMenuScreen.Entry(Component.translatable("loothub.chatwin.preset", pr[0]),
						() -> addTab(pr[0], pr[1], true)));
			}
			minecraft.setScreen(new de.loothub.client.MoreMenuScreen(this, plusX, plusY, items, true));
		}).bounds(tx, y, 16, 16).tooltip(net.minecraft.client.gui.components.Tooltip.create(
				Component.translatable("loothub.chatwin.preset.tip"))).build());

		// Gewählter Tab
		y += 22;
		nameY = y;
		EditBox name = box(x + 40, y, 120, tab.name, 20, "loothub.chatwin.tab.name", v -> tab.name = v.isBlank() ? "Tab" : v);
		addRenderableWidget(Button.builder(exclusiveLabel(tab.exclusive), b -> {
			tab.exclusive = !tab.exclusive;
			b.setMessage(exclusiveLabel(tab.exclusive));
			changed();
		}).bounds(x + 164, y, w - 164 - 70 - 22 - 40, 16).build());
		// Tab nach links/rechts schieben (im Chat geht das auch per Ziehen mit der Maus)
		int ai = tabs.indexOf(tab);
		Button left = addRenderableWidget(Button.builder(Component.literal("◀"), b -> moveTab(tab, -1))
				.bounds(x + w - 66 - 22 - 40, y, 18, 16).build());
		left.active = ai > 0;
		Button right = addRenderableWidget(Button.builder(Component.literal("▶"), b -> moveTab(tab, 1))
				.bounds(x + w - 66 - 22 - 20, y, 18, 16).build());
		right.active = ai < tabs.size() - 1;
		left.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("loothub.chatwin.tab.move")));
		right.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("loothub.chatwin.tab.move")));
		Button snd = addRenderableWidget(Button.builder(soundLabel(tab.sound), b -> {
			tab.sound = !tab.sound;
			b.setMessage(soundLabel(tab.sound));
			if (tab.sound) {
				de.loothub.client.Sounds.chat(); // Vorschau
			}
			WidgetConfig.get().save();
		}).bounds(x + w - 66 - 22, y, 20, 16).build());
		snd.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("loothub.chatwin.tab.sound")));
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
			if (y > sourcesY + 12 + 36) {
				break; // höchstens drei Reihen
			}
			Button chip = addRenderableWidget(Button.builder(sourceLabel(tab, src), b -> {
				tab.toggleSource(src);
				b.setMessage(sourceLabel(tab, src));
				changed();
			}).bounds(sx, y, bw, 16).build());
			String tipKey = "loothub.chatwin.src." + src.toLowerCase(java.util.Locale.ROOT).replace(' ', '_').replace("-", "_");
			if (net.minecraft.client.resources.language.I18n.exists(tipKey)) {
				chip.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(tipKey)));
			}
			sx += bw + 3;
		}

		// Wortregeln: zwei Listen, jeder Eintrag sichtbar
		y = Math.min(y, sourcesY + 48) + 22;
		listY = y;
		int colW = (w - 6) / 2;
		incBox = box(x, y + 10, colW - 20, "", 256, "loothub.chatwin.tab.include", v -> {
		});
		addRenderableWidget(Button.builder(Component.literal("+").withStyle(ChatFormatting.GREEN), b -> addWord(true))
				.bounds(x + colW - 18, y + 10, 18, 16).build());
		excBox = box(x + colW + 6, y + 10, colW - 20, "", 256, "loothub.chatwin.tab.exclude", v -> {
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
			for (String w : CustomChat.splitWords(s)) {
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
				editBox.setMaxLength(256);
				editBox.setBordered(false);
				editBox.setValue(word);
				setFocused(editBox);
				editBox.setFocused(true);
			}
			addRenderableWidget(Button.builder(Component.literal("✕").withStyle(ChatFormatting.RED), b -> {
				List<String> l = words(include ? tab.include : tab.exclude);
				l.remove(word);
				if (include) {
					tab.include = CustomChat.joinWords(l);
				} else {
					tab.exclude = CustomChat.joinWords(l);
				}
				changed();
				rebuildWidgets();
			}).bounds(x + colW - 14, entriesTop() + i * ENTRY_H, 14, 12).build());
		}
	}

	private void addWord(boolean include) {
		EditBox box = include ? incBox : excBox;
		String v = box.getValue().strip();
		if (v.isEmpty()) {
			return;
		}
		CustomChat.Tab tab = win.tab();
		List<String> l = words(include ? tab.include : tab.exclude);
		if (l.stream().noneMatch(x -> x.equalsIgnoreCase(v))) {
			l.add(v);
		}
		if (include) {
			tab.include = CustomChat.joinWords(l);
		} else {
			tab.exclude = CustomChat.joinWords(l);
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
			String v = editBox.getValue().strip();
			if (v.isEmpty()) {
				l.remove(editIndex);
			} else {
				l.set(editIndex, v);
			}
			if (include) {
				tab.include = CustomChat.joinWords(l);
			} else {
				tab.exclude = CustomChat.joinWords(l);
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
		if (page == 1 && doubleClick && event.y() >= entriesTop() && event.y() < entriesTop() + entriesVisible() * ENTRY_H) {
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
		if (page == 1 && my >= entriesTop()) {
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

	private static Component soundLabel(boolean on) {
		return Component.literal("♪").withStyle(on ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY);
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
		if (page == 0) {
			g.drawString(font, Component.translatable("loothub.chatwin.presets"), x, py + 48, LoothubScreen.FAINT, false);
			drawPreview(g, x, previewY, pw - 20, py + ph - 28 - previewY);
			super.render(g, mx, my, delta);
			return;
		}
		g.drawString(font, Component.translatable("loothub.chatwin.tabs"), x, tabsLabelY, LoothubScreen.FAINT, false);
		g.drawString(font, Component.translatable("loothub.chatwin.tab.name"), x, nameY + 4, LoothubScreen.TEXT, false);
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

	/** Live-Vorschau des Fensters mit den gewählten Farben, Deckkraft und Schriftgröße. */
	private void drawPreview(GuiGraphics g, int x, int y, int w, int h) {
		if (h < 40) {
			return;
		}
		g.drawString(font, Component.translatable("loothub.chatwin.preview"), x, y - 10, LoothubScreen.FAINT, false);
		int accent = 0xFF000000 | (win.accent & 0xFFFFFF);
		int bgA = Mth.clamp(win.opacity, 0, 100) * 255 / 100;
		// Kopfzeile mit Tabs
		g.fill(x, y, x + w, y + 12, 0xE0100A0C);
		g.fill(x, y + 11, x + w, y + 12, accent);
		int tx = x + 2;
		for (int i = 0; i < win.tabs.size() && tx < x + w - 40; i++) {
			String name = win.tabs.get(i).name;
			int tw = font.width(name) + 8;
			if (i == win.active) {
				g.fill(tx, y + 1, tx + tw, y + 11, accent & 0x90FFFFFF);
			}
			g.drawString(font, name, tx + 4, y + 2, i == win.active ? 0xFFFFFFFF : 0xFFB0B0B0, false);
			tx += tw + 2;
		}
		// Nachrichten
		int top = y + 12;
		g.fill(x, top, x + w, y + h, (bgA << 24) | (win.bg & 0xFFFFFF));
		float s = Mth.clamp(win.textScale, 50, 200) / 100F;
		String[][] lines = {{"§b[Angelverein] §fMisskowalski §7» §fPetri Heil!", ""}, {"§6[Anführer] §fKaledoss §7» §fMoin zusammen", ""},
				{"§d@CLAN §7| §ffarm-5 ist frei", ""}, {"§a+1.250 $ §7Marktverkauf", ""}};
		g.enableScissor(x, top, x + w, y + h);
		g.pose().pushMatrix();
		g.pose().translate(x + 3, top + 3);
		g.pose().scale(s, s);
		for (int i = 0; i < lines.length; i++) {
			g.drawString(font, lines[i][0], 0, i * 10, 0xFFFFFFFF, false);
		}
		g.pose().popMatrix();
		g.disableScissor();
		// Akzent-Ecken wie im Spiel
		g.fill(x, y + h - 1, x + 8, y + h, accent);
		g.fill(x + w - 8, y + h - 1, x + w, y + h, accent);
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
