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

	public ChatWindowScreen(CustomChat.Window win) {
		super(Component.translatable("loothub.chatwin.title"));
		this.win = win;
	}

	@Override
	protected void init() {
		pw = Math.min(480, width - 20);
		ph = Math.min(330, height - 20);
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
			if (y > py + ph - 70) {
				break;
			}
			addRenderableWidget(Button.builder(sourceLabel(tab, src), b -> {
				tab.toggleSource(src);
				b.setMessage(sourceLabel(tab, src));
				changed();
			}).bounds(sx, y, bw, 16).build());
			sx += bw + 3;
		}

		// Wortregeln (optional)
		y = py + ph - 46;
		int ruleW = (w - 4) / 2;
		box(x, y, ruleW, tab.include, 200, "loothub.chatwin.tab.include", v -> tab.include = v);
		box(x + ruleW + 4, y, ruleW, tab.exclude, 200, "loothub.chatwin.tab.exclude", v -> tab.exclude = v);

		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(px + pw - 90, py + ph - 22, 80, 16).build());
		setInitialFocus(name);
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
		g.drawString(font, Component.translatable("loothub.chatwin.rules"), x, py + ph - 58, LoothubScreen.FAINT, false);
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
