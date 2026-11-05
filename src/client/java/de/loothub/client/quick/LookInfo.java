package de.loothub.client.quick;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import de.loothub.client.Fmt;
import de.loothub.client.MarketPrices;
import de.loothub.client.Theme;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Block-Info oben in der Mitte: was man gerade anschaut – Block mit Symbol und Namen, das passende Werkzeug (mit
 * Stufe, z. B. Eisen-Spitzhacke) samt Häkchen, ob das gehaltene Werkzeug taugt, und den Verkaufspreis am Marktplatz.
 * Bei Lebewesen nur der Name. Nur Anzeige.
 */
public final class LookInfo {
	private static final int PAD = 5;

	private LookInfo() {
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!WidgetConfig.get().lookInfo || mc.player == null || mc.level == null || mc.options.hideGui
				|| mc.screen != null) {
			return;
		}
		HitResult hit = mc.hitResult;
		if (hit instanceof EntityHitResult ehr) {
			entity(g, mc, ehr.getEntity());
		} else if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			BlockState st = mc.level.getBlockState(bhr.getBlockPos());
			if (!st.isAir()) {
				block(g, mc, st, bhr);
			}
		}
	}

	private static void block(GuiGraphics g, Minecraft mc, BlockState st, BlockHitResult bhr) {
		Font font = mc.font;
		ItemStack icon = st.getCloneItemStack(mc.level, bhr.getBlockPos(), false);
		if (icon.isEmpty()) {
			icon = new ItemStack(st.getBlock().asItem());
		}
		Component name = st.getBlock().getName();
		ItemStack tool = toolFor(st);
		boolean ok = tool.isEmpty() || !st.requiresCorrectToolForDrops()
				|| mc.player.getMainHandItem().isCorrectToolForDrops(st);
		String price = null;
		if (!icon.isEmpty()) {
			MarketPrices.Entry e = MarketPrices.get(BuiltInRegistries.ITEM.getKey(icon.getItem()).toString());
			if (e != null && e.sell().available()) {
				price = Fmt.exact(e.sell().price()) + " $";
			}
		}
		int nameW = font.width(name);
		int line2W = (tool.isEmpty() ? 0 : 18 + font.width(ok ? "✔" : "✖") + 6) + (price == null ? 0 : font.width(price));
		int w = PAD + 18 + Math.max(nameW, line2W) + PAD;
		int h = price == null && tool.isEmpty() ? 26 : 36;
		int x = (g.guiWidth() - w) / 2, y = 4;
		panel(g, x, y, w, h);
		if (!icon.isEmpty()) {
			g.renderItem(icon, x + PAD, y + (h - 16) / 2);
		}
		int tx = x + PAD + 20;
		g.drawString(font, name, tx, y + 6, 0xFFFFFFFF, true);
		int ly = y + 19;
		if (!tool.isEmpty()) {
			g.pose().pushMatrix();
			g.pose().translate(tx, ly - 2);
			g.pose().scale(0.75F, 0.75F);
			g.renderItem(tool, 0, 0);
			g.pose().popMatrix();
			g.drawString(font, ok ? "✔" : "✖", tx + 14, ly, ok ? 0xFF5BD68A : 0xFFE23B3B, true);
			tx += 14 + font.width("✔") + 8;
		}
		if (price != null) {
			g.drawString(font, price, tx, ly, 0xFF55E06A, true);
		}
	}

	private static void entity(GuiGraphics g, Minecraft mc, Entity e) {
		Font font = mc.font;
		Component name = e.getDisplayName();
		int w = PAD + font.width(name) + PAD;
		int h = 18;
		int x = (g.guiWidth() - w) / 2, y = 4;
		panel(g, x, y, w, h);
		g.drawString(font, name, x + PAD, y + 5, 0xFFFFFFFF, true);
	}

	private static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, Theme.bg(0xD0140B0D));
		g.fill(x, y, x + w, y + 1, Theme.bg(0xFFE23B3B));
	}

	/** Passendes Werkzeug (mit nötiger Stufe), oder leer, wenn man den Block mit der Hand abbaut. */
	private static ItemStack toolFor(BlockState st) {
		boolean diamond = st.is(BlockTags.NEEDS_DIAMOND_TOOL), iron = st.is(BlockTags.NEEDS_IRON_TOOL),
				stone = st.is(BlockTags.NEEDS_STONE_TOOL);
		if (st.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
			return new ItemStack(diamond ? Items.DIAMOND_PICKAXE : iron ? Items.IRON_PICKAXE
					: stone ? Items.STONE_PICKAXE : Items.WOODEN_PICKAXE);
		}
		if (st.is(BlockTags.MINEABLE_WITH_AXE)) {
			return new ItemStack(Items.IRON_AXE);
		}
		if (st.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
			return new ItemStack(Items.IRON_SHOVEL);
		}
		if (st.is(BlockTags.MINEABLE_WITH_HOE)) {
			return new ItemStack(Items.IRON_HOE);
		}
		if (st.is(BlockTags.LEAVES) || st.is(BlockTags.WOOL)) {
			return new ItemStack(Items.SHEARS);
		}
		return ItemStack.EMPTY;
	}
}
