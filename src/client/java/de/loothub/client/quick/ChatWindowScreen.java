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

/** Einstellungen eines Chat-Fensters: Farben, Deckkraft, Textgröße, Ausblendzeit und die Tabs mit ihren Regeln. */
public class ChatWindowScreen extends Screen {
	private static final int ROW_H = 24;
	private final CustomChat.Window win;
	private int px, py, pw, ph;
	private int scroll;

	public ChatWindowScreen(CustomChat.Window win) {
		super(Component.translatable("loothub.chatwin.title"));
		this.win = win;
	}

	@Override
	protected void init() {
		pw = Math.min(460, width - 20);
		ph = Math.min(320, height - 20);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		int x = px + 10, y = py + 24, w = pw - 20;
		int half = (w - 4) / 2;

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
		int third = (w - 8) / 3;
		addRenderableWidget(slider(x, y, third, "loothub.chatwin.opacity", win.opacity, 0, 100, "%", v -> win.opacity = v));
		addRenderableWidget(slider(x + third + 4, y, third, "loothub.chatwin.scale", win.textScale, 50, 200, "%", v -> win.textScale = v));
		addRenderableWidget(slider(x + 2 * (third + 4), y, third, "loothub.chatwin.fade", win.fadeSec, 1, 120, " s", v -> win.fadeSec = v));

		// Tabs
		y += 34;
		int listTop = y;
		int visible = Math.max(1, (py + ph - 30 - listTop) / ROW_H);
		List<CustomChat.Tab> tabs = win.tabs;
		scroll = Mth.clamp(scroll, 0, Math.max(0, tabs.size() - visible));
		int nameW = 70, privW = 60, delW = 16;
		int ruleW = (w - nameW - privW - delW - 12) / 2;
		for (int i = 0; i < visible && i + scroll < tabs.size(); i++) {
			CustomChat.Tab t = tabs.get(i + scroll);
			int ry = listTop + i * ROW_H;
			int cx = x;
			EditBox name = box(cx, ry, nameW, t.name, 20, "loothub.chatwin.tab.name", v -> t.name = v.isBlank() ? "Tab" : v);
			cx += nameW + 4;
			box(cx, ry, ruleW, t.include, 200, "loothub.chatwin.tab.include", v -> t.include = v);
			cx += ruleW + 4;
			box(cx, ry, ruleW, t.exclude, 200, "loothub.chatwin.tab.exclude", v -> t.exclude = v);
			cx += ruleW + 4;
			addRenderableWidget(Button.builder(privLabel(t.privateOnly), b -> {
				t.privateOnly = !t.privateOnly;
				b.setMessage(privLabel(t.privateOnly));
				changed();
			}).bounds(cx, ry, privW, 16).build());
			cx += privW;
			Button del = addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
				tabs.remove(t);
				changed();
				rebuildWidgets();
			}).bounds(cx, ry, delW, 16).build());
			del.active = tabs.size() > 1;
			if (i + scroll == win.active) {
				setInitialFocus(name);
			}
		}
		addRenderableWidget(Button.builder(Component.translatable("loothub.chatwin.tab.add"), b -> {
			CustomChat.Tab t = new CustomChat.Tab();
			t.name = "Tab " + (tabs.size() + 1);
			tabs.add(t);
			changed();
			scroll = tabs.size();
			rebuildWidgets();
		}).bounds(x, py + ph - 24, 90, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(px + pw - 100, py + ph - 24, 90, 16).build());
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

	private static Component privLabel(boolean on) {
		return Component.translatable("loothub.chatwin.tab.private").withStyle(on ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY);
	}

	private static Component colorLabel(String key, int rgb) {
		return Component.translatable(key).append(Component.literal("  ■").withStyle(s -> s.withColor(rgb & 0xFFFFFF)));
	}

	private AbstractSliderButton slider(int x, int y, int w, String key, int value, int min, int max, String unit,
			java.util.function.IntConsumer set) {
		return new AbstractSliderButton(x, y, w, 16, Component.empty(), (value - min) / (double) (max - min)) {
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
		int x = px + 10, w = pw - 20;
		int nameW = 70, privW = 60, delW = 16;
		int ruleW = (w - nameW - privW - delW - 12) / 2;
		int hy = py + 24 + 20 + 24;
		g.drawString(font, Component.translatable("loothub.chatwin.tabs"), x, hy - 2, LoothubScreen.TEXT, false);
		g.drawString(font, Component.translatable("loothub.chatwin.col.include"), x + nameW + 4, hy - 2, LoothubScreen.FAINT, false);
		g.drawString(font, Component.translatable("loothub.chatwin.col.exclude"), x + nameW + ruleW + 8, hy - 2, LoothubScreen.FAINT, false);
		super.render(g, mx, my, delta);
		Component hint = Component.translatable("loothub.chatwin.hint");
		g.drawString(font, hint, px + 104, py + ph - 20, LoothubScreen.FAINT, false);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		scroll = Math.max(0, scroll - (int) Math.signum(v));
		rebuildWidgets();
		return true;
	}

	@Override
	public void onClose() {
		changed();
		minecraft.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
