package de.loothub.client.quick;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import de.loothub.client.Fmt;
import de.loothub.client.MarketPrices;
import de.loothub.client.Theme;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Block-Info: was man gerade anschaut – Block mit Symbol und Namen, das passende Werkzeug (mit Stufe) samt Häkchen,
 * ob das gehaltene Werkzeug taugt, und den Verkaufspreis am Marktplatz; bei Lebewesen nur der Name. Werkzeug und Preis
 * einzeln abschaltbar, Größe einstellbar; im Chat mit der Maus verschiebbar (wie das Musik-Widget). Nur Anzeige.
 */
public final class LookInfo {
	private static final int PAD = 5;

	/** Was angezeigt wird (Block oder Lebewesen). */
	private record Info(ItemStack icon, Component name, ItemStack tool, boolean ok, String price, java.util.List<ItemStack> contents) {
		Info(ItemStack icon, Component name, ItemStack tool, boolean ok, String price) {
			this(icon, name, tool, ok, price, java.util.List.of());
		}
	}

	private static boolean dragging;
	private static double grabX, grabY;
	/** Zuletzt gezeichneter Rahmen (für Klicks im Chat). */
	private static int lastX, lastY, lastW, lastH;

	private LookInfo() {
	}

	public static void register() {
		// Für jeden Bildschirm anmelden; ob es der Chat ist, wird beim Zeichnen/Klicken geprüft (LabyMod-Chat)
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof de.loothub.client.LoothubScreen) {
				return;
			}
			ScreenEvents.afterRender(screen).register((s, g, mx, my, d) -> {
				if (isChat(s)) {
					renderInChat(s, g, mx, my);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !isChat(s) || !click(e));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !drag(s, e));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> {
				if (dragging) {
					dragging = false;
					WidgetConfig.get().save();
					return false;
				}
				return true;
			});
		});
	}

	public static boolean isChat(net.minecraft.client.gui.screens.Screen screen) {
		return de.loothub.client.quick.CustomChat.chatOpen(screen);
	}

	public static void renderHud(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!WidgetConfig.get().lookInfo || mc.player == null || mc.level == null || mc.options.hideGui
				|| mc.screen != null) {
			return;
		}
		Info info = current(mc);
		if (info != null) {
			draw(g, mc, info, g.guiWidth(), g.guiHeight());
		}
	}

	/** Im Chat: immer sichtbar (zur Not mit Beispiel-Block) und mit Rahmen zum Verschieben. */
	private static void renderInChat(Screen s, GuiGraphics g, int mx, int my) {
		Minecraft mc = Minecraft.getInstance();
		if (!WidgetConfig.get().lookInfo || mc.player == null || mc.level == null) {
			return;
		}
		Info info = current(mc);
		if (info == null) {
			BlockState sample = Blocks.DIAMOND_ORE.defaultBlockState();
			info = blockInfo(mc, sample, new ItemStack(Items.DIAMOND_ORE));
		}
		draw(g, mc, info, s.width, s.height);
		boolean hover = mx >= lastX && mx < lastX + lastW && my >= lastY && my < lastY + lastH;
		int c = dragging || hover ? 0xFFFFFFFF : 0x80E23B3B;
		g.fill(lastX - 1, lastY - 1, lastX + lastW + 1, lastY, c);
		g.fill(lastX - 1, lastY + lastH, lastX + lastW + 1, lastY + lastH + 1, c);
		g.fill(lastX - 1, lastY, lastX, lastY + lastH, c);
		g.fill(lastX + lastW, lastY, lastX + lastW + 1, lastY + lastH, c);
	}

	/** Für den Verschiebe-Bildschirm: Beispiel-Anzeige zeichnen (angeschauter Block oder Diamanterz). */
	public static void drawPreview(GuiGraphics g, int sw, int sh) {
		Minecraft mc = Minecraft.getInstance();
		Info info = mc.player != null && mc.level != null ? current(mc) : null;
		if (info == null) {
			info = mc.player != null
					? blockInfo(mc, Blocks.DIAMOND_ORE.defaultBlockState(), new ItemStack(Items.DIAMOND_ORE))
					: new Info(new ItemStack(Items.DIAMOND_ORE), Blocks.DIAMOND_ORE.getName(), new ItemStack(Items.IRON_PICKAXE), true, null);
		}
		draw(g, mc, info, sw, sh);
	}

	/** Zuletzt gezeichneter Rahmen: {x, y, Breite, Höhe}. */
	public static int[] bounds() {
		return new int[]{lastX, lastY, lastW, lastH};
	}

	/** Position setzen: Mitte bei {@code centerX}, Oberkante bei {@code top}. */
	public static void moveTo(double centerX, double top, int sw, int sh) {
		WidgetConfig c = WidgetConfig.get();
		c.lookInfoX = Math.max(0, Math.min(1, centerX / Math.max(1, sw)));
		c.lookInfoY = Math.max(0, Math.min(1, (top - 4) / Math.max(1, sh - lastH - 8)));
	}

	private static Info current(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit instanceof EntityHitResult ehr) {
			Entity e = ehr.getEntity();
			return new Info(ItemStack.EMPTY, e.getDisplayName(), ItemStack.EMPTY, true, null);
		}
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			BlockState st = mc.level.getBlockState(bhr.getBlockPos());
			if (!st.isAir()) {
				ItemStack icon = st.getCloneItemStack(mc.level, bhr.getBlockPos(), false);
				if (icon.isEmpty()) {
					icon = new ItemStack(st.getBlock().asItem());
				}
				Info info = blockInfo(mc, st, icon);
				// Regal: die (bis zu drei) Items darauf zeigen – der Inhalt wird ohnehin an den Client geschickt
				if (WidgetConfig.get().lookInfoContents
						&& mc.level.getBlockEntity(bhr.getBlockPos()) instanceof net.minecraft.world.level.block.entity.ShelfBlockEntity shelf) {
					java.util.List<ItemStack> items = new java.util.ArrayList<>();
					for (ItemStack it : shelf.getItems()) {
						items.add(it.copy());
					}
					info = new Info(info.icon(), info.name(), info.tool(), info.ok(), info.price(), items);
				}
				return info;
			}
		}
		return null;
	}

	private static Info blockInfo(Minecraft mc, BlockState st, ItemStack icon) {
		WidgetConfig c = WidgetConfig.get();
		ItemStack tool = c.lookInfoTool ? toolFor(st) : ItemStack.EMPTY;
		boolean ok = tool.isEmpty() || !st.requiresCorrectToolForDrops()
				|| mc.player.getMainHandItem().isCorrectToolForDrops(st);
		String price = null;
		if (c.lookInfoPrice && !icon.isEmpty()) {
			MarketPrices.Entry e = MarketPrices.get(BuiltInRegistries.ITEM.getKey(icon.getItem()).toString());
			if (e != null && e.sell().available()) {
				price = Fmt.exact(e.sell().price()) + " $";
			}
		}
		return new Info(icon, st.getBlock().getName(), tool, ok, price);
	}

	/** Zeichnet die Anzeige an der eingestellten Stelle und Größe. */
	private static void draw(GuiGraphics g, Minecraft mc, Info info, int sw, int sh) {
		WidgetConfig c = WidgetConfig.get();
		Font font = mc.font;
		boolean hasIcon = !info.icon().isEmpty();
		boolean line2 = !info.tool().isEmpty() || info.price() != null;
		int line2W = (info.tool().isEmpty() ? 0 : 14 + font.width("✔") + 8) + (info.price() == null ? 0 : font.width(info.price()));
		boolean line3 = !info.contents().isEmpty();
		String only = null; // genau ein Item auf dem Regal: Name dazuschreiben
		int filled = 0;
		for (ItemStack it : info.contents()) {
			if (!it.isEmpty()) {
				filled++;
				only = it.getHoverName().getString();
			}
		}
		if (filled != 1) {
			only = null;
		}
		String emptyText = Component.translatable("loothub.look_info.empty").getString();
		int line3W = line3 ? (filled == 0 ? font.width(emptyText) : info.contents().size() * 18 + (only == null ? 0 : 4 + font.width(only))) : 0;
		int w = PAD + (hasIcon ? 20 : 0) + Math.max(Math.max(font.width(info.name()), line2W), line3W) + PAD;
		int h = (line2 ? 32 : (hasIcon ? 26 : 18)) + (line3 ? 18 : 0);
		float scale = Math.max(0.5F, Math.min(2.0F, c.lookInfoScale));
		int sw2 = Math.round(w * scale), sh2 = Math.round(h * scale);
		int x = (int) Math.round(c.lookInfoX * sw - sw2 / 2.0);
		x = Math.max(2, Math.min(sw - sw2 - 2, x));
		int y = (int) Math.round(4 + c.lookInfoY * Math.max(0, sh - sh2 - 8));
		lastX = x;
		lastY = y;
		lastW = sw2;
		lastH = sh2;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.fill(0, 0, w, h, Theme.bg(0xD0140B0D));
		g.fill(0, 0, w, 1, Theme.bg(0xFFE23B3B));
		int tx = PAD;
		if (hasIcon) {
			g.renderItem(info.icon(), PAD, ((line3 ? h - 18 : h) - 16) / 2);
			tx += 20;
		}
		int top = tx;
		g.drawString(font, info.name(), tx, line2 ? 6 : ((line3 ? h - 18 : h) - 8) / 2, 0xFFFFFFFF, true);
		if (line2) {
			int ly = 19;
			if (!info.tool().isEmpty()) {
				g.pose().pushMatrix();
				g.pose().translate(tx, ly - 2);
				g.pose().scale(0.75F, 0.75F);
				g.renderItem(info.tool(), 0, 0);
				g.pose().popMatrix();
				g.drawString(font, info.ok() ? "✔" : "✖", tx + 14, ly, info.ok() ? 0xFF5BD68A : 0xFFE23B3B, true);
				tx += 14 + font.width("✔") + 8;
			}
			if (info.price() != null) {
				g.drawString(font, info.price(), tx, ly, 0xFF55E06A, true);
			}
		}
		if (line3) {
			int ry = h - 19;
			g.fill(top, ry - 1, w - PAD, ry, 0x40FFFFFF);
			if (filled == 0) {
				g.drawString(font, emptyText, top, ry + 5, 0xFF8A8A8A, true);
			} else {
				int ix = top;
				for (ItemStack it : info.contents()) {
					g.fill(ix, ry + 1, ix + 16, ry + 17, 0x30000000);
					if (!it.isEmpty()) {
						g.renderItem(it, ix, ry + 1);
						g.renderItemDecorations(font, it, ix, ry + 1);
					}
					ix += 18;
				}
				if (only != null) {
					g.drawString(font, only, ix + 2, ry + 5, 0xFFE0E0E0, true);
				}
			}
		}
		g.pose().popMatrix();
	}

	private static boolean click(MouseButtonEvent e) {
		if (e.button() != 0 || !WidgetConfig.get().lookInfo) {
			return false;
		}
		if (e.x() >= lastX && e.x() < lastX + lastW && e.y() >= lastY && e.y() < lastY + lastH) {
			dragging = true;
			grabX = e.x() - (lastX + lastW / 2.0);
			grabY = e.y() - lastY;
			return true;
		}
		return false;
	}

	private static boolean drag(Screen screen, MouseButtonEvent e) {
		if (!dragging) {
			return false;
		}
		WidgetConfig c = WidgetConfig.get();
		c.lookInfoX = Math.max(0, Math.min(1, (e.x() - grabX) / Math.max(1, screen.width)));
		c.lookInfoY = Math.max(0, Math.min(1, (e.y() - grabY - 4) / Math.max(1, screen.height - lastH - 8)));
		return true;
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
