package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Knöpfe der Befehls-Leiste verwalten: eigene anlegen, Name/Befehl/Symbol ändern, löschen.
 * Als Symbol lässt sich jedes Minecraft-Item wählen.
 */
public class QuickButtonsScreen extends Screen {
	private static final int ROW_H = 22;

	private final Screen parent;
	private int scroll;
	private int listX, listY, listW, listH;

	public QuickButtonsScreen(Screen parent) {
		super(Component.translatable("loothub.quickedit.title"));
		this.parent = parent;
	}

	private static List<QuickCommand> commands() {
		WidgetConfig c = WidgetConfig.get();
		if (c.quickCommands == null) {
			c.quickCommands = new ArrayList<>();
		}
		return c.quickCommands;
	}

	@Override
	protected void init() {
		listW = Math.min(360, width - 40);
		listX = (width - listW) / 2;
		listY = 40;
		listH = height - listY - 40;
		addRenderableWidget(Button.builder(Component.translatable("loothub.quickedit.new"),
						b -> minecraft.setScreen(new Editor(this, null)))
				.bounds(width / 2 - 104, height - 30, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(width / 2 + 4, height - 30, 100, 20).build());
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta);
		g.drawString(font, getTitle(), (width - font.width(getTitle())) / 2, 14, 0xFFFFFFFF, true);
		Component hint = Component.translatable("loothub.quickedit.hint");
		g.drawString(font, hint, (width - font.width(hint)) / 2, 26, LoothubScreen.FAINT, false);
		List<QuickCommand> list = commands();
		int visible = listH / ROW_H;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - visible)));
		g.enableScissor(listX, listY, listX + listW, listY + listH);
		for (int i = 0; i < visible + 1 && i + scroll < list.size(); i++) {
			QuickCommand q = list.get(i + scroll);
			int y = listY + i * ROW_H;
			boolean hover = mx >= listX && mx < listX + listW && my >= y && my < y + ROW_H - 2;
			g.fill(listX, y, listX + listW, y + ROW_H - 2, hover ? 0x60301A1E : 0x40181012);
			QuickCommandPanel.drawIcon(g, q.iconOrDefault(), listX + 3, y + 2);
			String label = q.label == null || q.label.isBlank() ? "–" : q.label;
			g.drawString(font, label, listX + 24, y + 2, q.hidden ? LoothubScreen.FAINT : 0xFFFFFFFF, false);
			g.drawString(font, "/" + (q.command == null ? "" : q.command.replaceFirst("^/", "")), listX + 24, y + 11, LoothubScreen.FAINT, false);
			// rechts: Bearbeiten / Löschen
			drawSmall(g, "✎", listX + listW - 40, y + 3, mx, my);
			drawSmall(g, "✖", listX + listW - 20, y + 3, mx, my);
		}
		g.disableScissor();
	}

	private void drawSmall(GuiGraphics g, String s, int x, int y, int mx, int my) {
		boolean hover = mx >= x && mx < x + 16 && my >= y && my < y + 14;
		g.fill(x, y, x + 16, y + 14, hover ? LoothubScreen.RED : 0xFF3A2A2C);
		g.drawString(font, s, x + (16 - font.width(s)) / 2, y + 3, 0xFFFFFFFF, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		List<QuickCommand> list = commands();
		if (e.button() != 0 || e.x() < listX || e.x() >= listX + listW || e.y() < listY || e.y() >= listY + listH) {
			return false;
		}
		int i = (int) ((e.y() - listY) / ROW_H) + scroll;
		if (i < 0 || i >= list.size()) {
			return false;
		}
		int y = listY + (i - scroll) * ROW_H;
		if (e.y() >= y + 3 && e.y() < y + 17) {
			if (e.x() >= listX + listW - 40 && e.x() < listX + listW - 24) {
				minecraft.setScreen(new Editor(this, list.get(i)));
				return true;
			}
			if (e.x() >= listX + listW - 20 && e.x() < listX + listW - 4) {
				list.remove(i);
				WidgetConfig.get().save();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		scroll = Math.max(0, scroll - (int) Math.signum(v));
		return true;
	}

	@Override
	public void onClose() {
		WidgetConfig.get().save();
		minecraft.setScreen(parent);
	}

	// ====================================================================== Bearbeiten

	/** Name, Befehl und Symbol eines Knopfs; Symbol aus allen Minecraft-Items mit Suche. */
	public static class Editor extends Screen {
		private static final int CELL = 20;

		private final Screen parent;
		private final QuickCommand editing;
		private EditBox nameBox, cmdBox, searchBox;
		private String icon;
		private List<Item> items = List.of();
		private int scroll;
		private int gridX, gridY, gridCols, gridRows;

		Editor(Screen parent, QuickCommand editing) {
			super(Component.translatable(editing == null ? "loothub.quickedit.new" : "loothub.quickedit.edit"));
			this.parent = parent;
			this.editing = editing;
			this.icon = editing == null ? "minecraft:command_block" : editing.iconOrDefault();
		}

		@Override
		protected void init() {
			int w = Math.min(340, width - 40);
			int x = (width - w) / 2;
			int y = 36;
			nameBox = new EditBox(font, x, y + 10, w / 2 - 4, 16, Component.translatable("loothub.quickedit.name"));
			nameBox.setMaxLength(32);
			nameBox.setValue(editing == null ? "" : nullToEmpty(editing.label));
			addRenderableWidget(nameBox);
			cmdBox = new EditBox(font, x + w / 2 + 4, y + 10, w / 2 - 4, 16, Component.translatable("loothub.quickedit.command"));
			cmdBox.setMaxLength(100);
			cmdBox.setValue(editing == null ? "" : "/" + nullToEmpty(editing.command).replaceFirst("^/", ""));
			addRenderableWidget(cmdBox);
			searchBox = new EditBox(font, x, y + 44, w, 16, Component.translatable("loothub.quickedit.search"));
			searchBox.setHint(Component.translatable("loothub.quickedit.search").withStyle(ChatFormatting.DARK_GRAY));
			searchBox.setResponder(t -> {
				scroll = 0;
				filter();
			});
			addRenderableWidget(searchBox);
			gridX = x;
			gridY = y + 66;
			gridCols = Math.max(1, w / CELL);
			gridRows = Math.max(1, (height - 40 - gridY) / CELL);
			filter();
			addRenderableWidget(Button.builder(Component.translatable("loothub.color.apply"), b -> save())
					.bounds(width / 2 - 104, height - 30, 100, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("loothub.color.cancel"), b -> onClose())
					.bounds(width / 2 + 4, height - 30, 100, 20).build());
		}

		private static String nullToEmpty(String s) {
			return s == null ? "" : s;
		}

		private void filter() {
			String q = searchBox.getValue().strip().toLowerCase(Locale.ROOT);
			List<Item> out = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item == Items.AIR) {
					continue;
				}
				if (!q.isEmpty()) {
					String id = BuiltInRegistries.ITEM.getKey(item).getPath();
					String name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
					if (!id.contains(q.replace(' ', '_')) && !name.contains(q)) {
						continue;
					}
				}
				out.add(item);
			}
			items = out;
		}

		private void save() {
			String cmd = cmdBox.getValue().strip().replaceFirst("^/", "");
			if (cmd.isEmpty()) {
				return; // ohne Befehl kein Knopf
			}
			String name = nameBox.getValue().strip();
			QuickCommand q = editing != null ? editing : new QuickCommand();
			q.label = name.isEmpty() ? "/" + cmd : name;
			q.command = cmd;
			q.icon = icon;
			if (editing == null) {
				commands().add(q);
			}
			WidgetConfig.get().save();
			minecraft.setScreen(parent);
		}

		@Override
		public void render(GuiGraphics g, int mx, int my, float delta) {
			super.render(g, mx, my, delta);
			g.drawString(font, getTitle(), (width - font.width(getTitle())) / 2, 12, 0xFFFFFFFF, true);
			g.drawString(font, Component.translatable("loothub.quickedit.name"), nameBox.getX(), nameBox.getY() - 10, LoothubScreen.MUTED, false);
			g.drawString(font, Component.translatable("loothub.quickedit.command"), cmdBox.getX(), cmdBox.getY() - 10, LoothubScreen.MUTED, false);
			g.drawString(font, Component.translatable("loothub.quickedit.icon"), searchBox.getX(), searchBox.getY() - 12, LoothubScreen.MUTED, false);
			// aktuelles Symbol als Vorschau neben „Symbol“
			QuickCommandPanel.drawIcon(g, icon, searchBox.getX() + font.width(Component.translatable("loothub.quickedit.icon")) + 6, searchBox.getY() - 17);

			int totalRows = (items.size() + gridCols - 1) / gridCols;
			scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - gridRows)));
			Item hovered = null;
			for (int r = 0; r < gridRows; r++) {
				for (int col = 0; col < gridCols; col++) {
					int idx = (r + scroll) * gridCols + col;
					if (idx >= items.size()) {
						break;
					}
					Item item = items.get(idx);
					int cx = gridX + col * CELL;
					int cy = gridY + r * CELL;
					String id = BuiltInRegistries.ITEM.getKey(item).toString();
					boolean sel = id.equals(icon);
					boolean hover = mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
					g.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, sel ? LoothubScreen.RED : hover ? 0xFF3A1C22 : 0xFF24161A);
					g.renderItem(new ItemStack(item), cx + 1, cy + 1);
					if (hover) {
						hovered = item;
					}
				}
			}
			if (hovered != null) {
				g.setTooltipForNextFrame(font, new ItemStack(hovered).getHoverName(), mx, my);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
			if (super.mouseClicked(e, doubleClick)) {
				return true;
			}
			if (e.button() == 0 && e.x() >= gridX && e.y() >= gridY) {
				int col = (int) ((e.x() - gridX) / CELL);
				int r = (int) ((e.y() - gridY) / CELL);
				int idx = (r + scroll) * gridCols + col;
				if (col < gridCols && r < gridRows && idx < items.size()) {
					Identifier id = BuiltInRegistries.ITEM.getKey(items.get(idx));
					icon = id.toString();
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double h, double v) {
			scroll = Math.max(0, scroll - (int) Math.signum(v) * 2);
			return true;
		}

		@Override
		public void onClose() {
			minecraft.setScreen(parent);
		}
	}
}
