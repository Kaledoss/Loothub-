package de.loothub.client.bau;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import de.loothub.client.Fmt;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Einkaufsliste im HUD: für jede Schematic, bei der sie eingeschaltet ist, was noch zu besorgen ist (fehlt laut
 * Litematica, abzüglich Inventar und Lager-Fässern). Was man dabei hat, verschwindet von der Liste. Nur Anzeige.
 */
public final class ShoppingList {
	/** Höchstens so viele Zeilen je Schematic. */
	private static final int MAX_ROWS = 10;
	private static final int ROW_H = 18;
	private static final int MIN_W = 150;

	record Entry(ItemStack icon, String name, long amount) {
	}

	record Section(String title, List<Entry> rows, int more, boolean done) {
	}

	private static List<Section> sections = List.of();
	private static int width = MIN_W;
	private static int refreshIn;

	private ShoppingList() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (--refreshIn <= 0) {
				refreshIn = 20;
				sections = compute();
			}
		});
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(de.loothub.Loothub.MOD_ID, "build_shopping"), ShoppingList::render);
	}

	/** Schlüssel einer Schematic für die Einstellung (bleibt gleich, auch wenn Litematica die Platzierung neu lädt). */
	static String key(Plan p) {
		return p.name + "|" + p.file;
	}

	public static boolean on(Plan p) {
		return WidgetConfig.get().buildShopping.contains(key(p));
	}

	static void toggle(Plan p) {
		WidgetConfig c = WidgetConfig.get();
		if (!c.buildShopping.remove(key(p))) {
			c.buildShopping.add(key(p));
		}
		c.save();
		refreshIn = 0;
	}

	private static List<Section> compute() {
		WidgetConfig c = WidgetConfig.get();
		if (c.buildShopping.isEmpty() || !Build.present() || Minecraft.getInstance().player == null) {
			return List.of();
		}
		List<Plan> plans = Build.plans();
		Map<String, Map<String, Long>> needs = new HashMap<>();
		for (Plan p : plans) {
			Map<String, Long> m = p.missing != null ? p.missing : p.totals;
			needs.put(p.id, m == null ? Map.of() : m);
		}
		Map<String, Map<String, Long>> stock = BuildStorage.allocate(plans, needs);
		// Das Inventar wird der Reihe nach verteilt, damit nichts doppelt abgehakt wird
		Map<String, Long> inv = new HashMap<>(Build.inventory());
		List<Section> out = new ArrayList<>();
		for (Plan p : plans) {
			if (!on(p)) {
				continue;
			}
			Map<String, Long> st = stock.getOrDefault(p.id, Map.of());
			List<Entry> rows = new ArrayList<>();
			for (Map.Entry<String, Long> e : needs.get(p.id).entrySet()) {
				long n = e.getValue() - st.getOrDefault(e.getKey(), 0L);
				long have = Math.min(Math.max(0, n), inv.getOrDefault(e.getKey(), 0L));
				if (have > 0) {
					inv.merge(e.getKey(), -have, Long::sum);
				}
				long buy = n - have;
				if (buy <= 0) {
					continue;
				}
				Identifier id = Identifier.tryParse(e.getKey());
				Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
				ItemStack icon = new ItemStack(item == null || item == Items.AIR ? Items.BARRIER : item);
				rows.add(new Entry(icon, icon.getHoverName().getString(), buy));
			}
			rows.sort((a, b) -> Long.compare(b.amount(), a.amount()));
			int more = Math.max(0, rows.size() - MAX_ROWS);
			boolean done = rows.isEmpty() && p.missing != null;
			out.add(new Section(p.name, rows.size() > MAX_ROWS ? List.copyOf(rows.subList(0, MAX_ROWS)) : rows, more, done));
		}
		return out;
	}

	/** Größe für „HUD-Positionen“ (Beispiel, falls gerade nichts angezeigt wird). */
	public static int[] size() {
		int h = height(sections);
		return new int[]{width, h > 0 ? h : 14 + 3 * ROW_H};
	}

	private static int height(List<Section> list) {
		int h = 0;
		for (Section s : list) {
			h += 14 + Math.max(1, s.rows().size()) * ROW_H + (s.more() > 0 ? 11 : 0) + 4;
		}
		return h;
	}

	private static void render(GuiGraphics g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		List<Section> list = sections;
		if (list.isEmpty() || mc.options.hideGui || mc.screen instanceof de.loothub.client.HudLayoutScreen) {
			return;
		}
		Font font = mc.font;
		int w = MIN_W;
		for (Section s : list) {
			w = Math.max(w, font.width("🛒 " + s.title()) + 12);
			for (Entry e : s.rows()) {
				w = Math.max(w, 26 + font.width(e.name()) + 10 + font.width(Fmt.whole(e.amount())) + 6);
			}
		}
		width = w = Math.min(260, w);
		int h = height(list);
		WidgetConfig c = WidgetConfig.get();
		int x = (int) Math.round(c.buildListX * Math.max(0, g.guiWidth() - w - 8)) + 4;
		int y = (int) Math.round(c.buildListY * Math.max(0, g.guiHeight() - h - 8)) + 4;
		for (Section s : list) {
			int sh = 14 + Math.max(1, s.rows().size()) * ROW_H + (s.more() > 0 ? 11 : 0);
			g.fill(x, y, x + w, y + sh, de.loothub.client.Theme.bg(0xC0120A0C));
			g.fill(x, y, x + w, y + 1, de.loothub.client.Theme.bg(0xFF3A1F23));
			String title = Component.translatable("loothub.build.shopping.title", s.title()).getString();
			g.drawString(font, font.plainSubstrByWidth(title, w - 10), x + 5, y + 4, 0xFFFFD66B, true);
			int ry = y + 14;
			if (s.rows().isEmpty()) {
				Component msg = Component.translatable(s.done() ? "loothub.build.shopping.done" : "loothub.build.counting");
				g.drawString(font, msg, x + 6, ry + 5, s.done() ? 0xFF7BD88F : 0xFFB9A9AB, true);
			}
			for (Entry e : s.rows()) {
				g.renderItem(e.icon(), x + 4, ry + 1);
				String amount = Fmt.whole(e.amount());
				int aw = font.width(amount);
				g.drawString(font, font.plainSubstrByWidth(e.name(), w - 32 - aw - 6), x + 24, ry + 5, 0xFFE8D8DA, true);
				g.drawString(font, amount, x + w - 6 - aw, ry + 5, 0xFFFFFFFF, true);
				ry += ROW_H;
			}
			if (s.more() > 0) {
				g.drawString(font, Component.translatable("loothub.build.shopping.more", s.more()), x + 6, ry + 1, 0xFFB9A9AB,
						true);
			}
			y += sh + 4;
		}
	}
}
