package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/** Wörter des globalen Chat-Filters (gilt für jeden Chat): Nachrichten damit werden gar nicht angezeigt. */
public class ChatFilterScreen extends Screen {
	private static final int ROW_H = 14;
	private final Screen parent;
	private EditBox box, editBox;
	private int editIndex = -1;
	private int px, py, pw, ph, listTop, scroll;

	public ChatFilterScreen(Screen parent) {
		super(Component.translatable("loothub.settings.chat_filter"));
		this.parent = parent;
	}

	private static List<String> words() {
		WidgetConfig c = WidgetConfig.get();
		if (c.chatFilterWords == null) {
			c.chatFilterWords = new ArrayList<>();
		}
		return c.chatFilterWords;
	}

	@Override
	protected void init() {
		pw = Math.min(320, width - 20);
		ph = Math.min(260, height - 20);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		int x = px + 10, w = pw - 20;
		editBox = null;
		String old = box != null ? box.getValue() : "";
		box = addRenderableWidget(new EditBox(font, x, py + 34, w - 24, 16, Component.translatable("loothub.chatwin.tab.exclude")));
		box.setMaxLength(100);
		box.setHint(Component.translatable("loothub.chatwin.tab.exclude").withStyle(ChatFormatting.DARK_GRAY));
		box.setValue(old);
		addRenderableWidget(Button.builder(Component.literal("+").withStyle(ChatFormatting.GREEN), b -> add())
				.bounds(x + w - 20, py + 34, 20, 16).build());
		listTop = py + 56;
		List<String> list = words();
		int visible = Math.max(1, (py + ph - 28 - listTop) / ROW_H);
		scroll = Mth.clamp(scroll, 0, Math.max(0, list.size() - visible));
		for (int i = 0; i < visible && i + scroll < list.size(); i++) {
			String word = list.get(i + scroll);
			if (editIndex == i + scroll) {
				editBox = addRenderableWidget(new EditBox(font, x + 4, listTop + i * ROW_H + 2, w - 24, 10, Component.empty()));
				editBox.setMaxLength(100);
				editBox.setBordered(false);
				editBox.setValue(word);
			}
			addRenderableWidget(Button.builder(Component.literal("✕").withStyle(ChatFormatting.RED), b -> {
				words().remove(word);
				WidgetConfig.get().save();
				rebuildWidgets();
			}).bounds(x + w - 14, listTop + i * ROW_H, 14, 12).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(px + pw - 90, py + ph - 22, 80, 16).build());
		if (editBox != null) {
			setInitialFocus(editBox);
		} else {
			setInitialFocus(box);
		}
	}

	private void add() {
		String v = box.getValue().strip();
		if (!v.isEmpty() && words().stream().noneMatch(x -> x.equalsIgnoreCase(v))) {
			words().add(v);
			WidgetConfig c = WidgetConfig.get();
			c.chatFilter = true;
			c.save();
		}
		box.setValue("");
		rebuildWidgets();
		setFocused(box);
	}

	private void commitEdit() {
		if (editBox != null && editIndex >= 0 && editIndex < words().size()) {
			String v = editBox.getValue().strip();
			if (v.isEmpty()) {
				words().remove(editIndex);
			} else {
				words().set(editIndex, v);
			}
			WidgetConfig.get().save();
		}
		editIndex = -1;
		rebuildWidgets();
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (editBox != null && !editBox.isMouseOver(event.x(), event.y())) {
			commitEdit();
		}
		int x = px + 10, w = pw - 20;
		if (doubleClick && event.x() >= x && event.x() < x + w - 16 && event.y() >= listTop) {
			int idx = (int) ((event.y() - listTop) / ROW_H) + scroll;
			if (idx < words().size()) {
				editIndex = idx;
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (editBox != null) {
			int k = event.key();
			if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || k == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
				commitEdit();
				return true;
			}
			if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
				editIndex = -1;
				rebuildWidgets();
				return true;
			}
		}
		if (box != null && box.isFocused()
				&& (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
			add();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		scroll = Math.max(0, scroll - (int) Math.signum(v));
		rebuildWidgets();
		return true;
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		g.fill(px, py, px + pw, py + ph, de.loothub.client.Theme.bg(0xF0140B0D));
		Pulse.outline(g, px, py, pw, ph);
		g.fill(px + 4, py + 3, px + pw - 4, py + 4, LoothubScreen.RED);
		g.drawString(font, getTitle(), px + 10, py + 9, LoothubScreen.RED, false);
		g.drawString(font, Component.translatable("loothub.chatpage.filter.hint"), px + 10, py + 21, LoothubScreen.FAINT, false);
		List<String> list = words();
		int x = px + 10, w = pw - 20;
		int visible = Math.max(1, (py + ph - 28 - listTop) / ROW_H);
		if (list.isEmpty()) {
			g.drawString(font, Component.translatable("loothub.chatpage.filter.empty"), x + 4, listTop + 3, LoothubScreen.FAINT, false);
		}
		for (int i = 0; i < visible && i + scroll < list.size(); i++) {
			int ry = listTop + i * ROW_H;
			boolean editing = editIndex == i + scroll;
			g.fill(x, ry, x + w, ry + ROW_H - 2, editing ? 0xFF000000 : 0x18FFFFFF);
			if (editing) {
				g.fill(x, ry + ROW_H - 3, x + w - 16, ry + ROW_H - 2, LoothubScreen.RED);
			} else {
				g.drawString(font, font.plainSubstrByWidth(list.get(i + scroll), w - 24), x + 4, ry + 2, LoothubScreen.TEXT, false);
			}
		}
		super.render(g, mx, my, delta);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0x80000000);
	}

	@Override
	public void onClose() {
		CustomChat.invalidate();
		WidgetConfig.get().save();
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
