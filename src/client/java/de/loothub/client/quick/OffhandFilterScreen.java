package de.loothub.client.quick;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/** Liste des Offhand-Filters: Items hinzufügen (aktuelles Offhand-Item oder Schnellwahl), entfernen, Modus. */
public class OffhandFilterScreen extends Screen {
	private static final Item[] QUICK = {Items.FISHING_ROD, Items.FIREWORK_ROCKET, Items.ENDER_PEARL, Items.SNOWBALL,
			Items.EGG, Items.BOW, Items.TRIDENT, Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.FLINT_AND_STEEL};
	private static final int ROW_H = 18;
	private final Screen parent;
	private int px, py, pw, ph, listTop, scroll;

	public OffhandFilterScreen(Screen parent) {
		super(Component.translatable("loothub.settings.offhand"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		WidgetConfig c = WidgetConfig.get();
		pw = Math.min(340, width - 20);
		ph = Math.min(300, height - 20);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		int x = px + 10, w = pw - 20;
		addRenderableWidget(Button.builder(modeLabel(c.offhandAllExcept), b -> {
			c.offhandAllExcept = !c.offhandAllExcept;
			b.setMessage(modeLabel(c.offhandAllExcept));
			c.save();
		}).bounds(x, py + 22, w, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.offhand.add_current"), b -> {
			if (minecraft.player != null && !minecraft.player.getOffhandItem().isEmpty()) {
				add(OffhandFilter.id(minecraft.player.getOffhandItem()));
			}
		}).bounds(x, py + 42, w, 16).build()).active = minecraft.player != null && !minecraft.player.getOffhandItem().isEmpty();
		// Schnellwahl als Item-Knöpfe
		int qx = x;
		for (Item it : QUICK) {
			String id = BuiltInRegistries.ITEM.getKey(it).toString();
			Button qb = addRenderableWidget(Button.builder(Component.empty(), b -> add(id)).bounds(qx, py + 62, 20, 20).build());
			qb.active = !OffhandFilter.items().contains(id);
			qb.setTooltip(net.minecraft.client.gui.components.Tooltip.create(new ItemStack(it).getHoverName()));
			qx += 22;
		}
		listTop = py + 92;
		List<String> list = OffhandFilter.items();
		int visible = Math.max(1, (py + ph - 28 - listTop) / ROW_H);
		scroll = Mth.clamp(scroll, 0, Math.max(0, list.size() - visible));
		for (int i = 0; i < visible && i + scroll < list.size(); i++) {
			String id = list.get(i + scroll);
			addRenderableWidget(Button.builder(Component.literal("✕").withStyle(ChatFormatting.RED), b -> {
				OffhandFilter.items().remove(id);
				WidgetConfig.get().save();
				rebuildWidgets();
			}).bounds(x + w - 16, listTop + i * ROW_H + 1, 16, 16).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("loothub.job.editor.done"), b -> onClose())
				.bounds(px + pw - 90, py + ph - 22, 80, 16).build());
	}

	private void add(String id) {
		if (!OffhandFilter.items().contains(id)) {
			OffhandFilter.items().add(id);
			WidgetConfig c = WidgetConfig.get();
			c.offhandFilter = true;
			c.save();
		}
		rebuildWidgets();
	}

	private static Component modeLabel(boolean allExcept) {
		return Component.translatable(allExcept ? "loothub.offhand.mode.all_except" : "loothub.offhand.mode.only");
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		g.fill(px, py, px + pw, py + ph, de.loothub.client.Theme.bg(0xF0140B0D));
		Pulse.outline(g, px, py, pw, ph);
		g.fill(px + 4, py + 3, px + pw - 4, py + 4, LoothubScreen.RED);
		g.drawString(font, getTitle(), px + 10, py + 9, LoothubScreen.RED, false);
		super.render(g, mx, my, delta);
		int qx = px + 10;
		for (Item it : QUICK) {
			g.renderItem(new ItemStack(it), qx + 2, py + 64);
			qx += 22;
		}
		int x = px + 10, w = pw - 20;
		g.drawString(font, Component.translatable(WidgetConfig.get().offhandAllExcept ? "loothub.offhand.list.allowed"
				: "loothub.offhand.list.blocked"), x, listTop - 9, LoothubScreen.FAINT, false);
		List<String> list = OffhandFilter.items();
		if (list.isEmpty()) {
			g.drawString(font, Component.translatable("loothub.offhand.empty"), x + 4, listTop + 4, LoothubScreen.FAINT, false);
		}
		int visible = Math.max(1, (py + ph - 28 - listTop) / ROW_H);
		for (int i = 0; i < visible && i + scroll < list.size(); i++) {
			int ry = listTop + i * ROW_H;
			g.fill(x, ry, x + w, ry + ROW_H - 1, 0x18FFFFFF);
			Identifier key = Identifier.tryParse(list.get(i + scroll));
			Item item = key == null ? Items.BARRIER : BuiltInRegistries.ITEM.getValue(key);
			ItemStack st = new ItemStack(item);
			g.renderItem(st, x + 2, ry + 1);
			g.drawString(font, st.getHoverName(), x + 22, ry + 5, LoothubScreen.TEXT, false);
		}
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0x80000000);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		scroll = Math.max(0, scroll - (int) Math.signum(v));
		rebuildWidgets();
		return true;
	}

	@Override
	public void onClose() {
		WidgetConfig.get().save();
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
