package de.loothub.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Seite „Wertstoffhändler“: alle Items, die der Händler gerade annimmt, mit aktuellem Kurs,
 * Grundkurs und Abweichung. Daten aus der OPSUCHT-API, nur Anzeige.
 */
public class MerchantScreen extends LoothubScreen {
	private static final int ROW_H = 22;
	private static final int GREEN = 0xFF55E06A;
	private static final int GOLD = 0xFFFFC040;
	private static final Map<String, ItemStack> ICONS = new HashMap<>();

	private int scroll;

	public MerchantScreen() {
		super(Page.MERCHANT);
	}

	@Override
	protected void initPage() {
		MerchantRates.refreshIfStale();
	}

	@Override
	protected Component headerRight() {
		long t = MerchantRates.lastFetch();
		return t == 0 ? null : Component.translatable("loothub.merchant.updated", Fmt.time(t));
	}

	@Override
	public void tick() {
		super.tick();
		MerchantRates.refreshIfStale();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		List<MerchantRates.Rate> list = MerchantRates.rates();
		if (list.isEmpty()) {
			String key = MerchantRates.isLoading() || MerchantRates.lastFetch() == 0 ? "loothub.merchant.loading"
					: MerchantRates.failed() ? "loothub.merchant.error" : "loothub.merchant.empty";
			Component msg = Component.translatable(key);
			g.drawString(font, msg, cl + (cw - font.width(msg)) / 2, ct + 20, FAINT, false);
			return;
		}

		// Kopfzeile
		int colRate = cr - 8;
		int colBase = cr - 120;
		int colChange = cr - 200;
		int hy = ct + 2;
		g.drawString(font, Component.translatable("loothub.merchant.col.item"), cl + 28, hy, FAINT, false);
		rightText(g, Component.translatable("loothub.merchant.col.change").getString(), colChange, hy, FAINT);
		rightText(g, Component.translatable("loothub.merchant.col.base").getString(), colBase, hy, FAINT);
		rightText(g, Component.translatable("loothub.merchant.col.rate").getString(), colRate, hy, FAINT);
		g.fill(cl, ct + 12, cr, ct + 13, RED_DARK);

		int top = ct + 15;
		int visible = Math.max(1, (cb - top) / ROW_H);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - visible)));
		g.enableScissor(cl, top, cr, cb);
		for (int i = 0; i < visible + 1 && i + scroll < list.size(); i++) {
			MerchantRates.Rate r = list.get(i + scroll);
			int y = top + i * ROW_H;
			boolean hover = mouseX >= cl && mouseX < cr && mouseY >= y && mouseY < y + ROW_H - 2 && mouseY < cb;
			g.fill(cl, y, cr, y + ROW_H - 2, hover ? 0x30E23B3B : (i % 2 == 0 ? 0x14FFFFFF : 0x08FFFFFF));
			g.renderItem(icon(r.itemId()), cl + 5, y + 2);
			String name = r.name() != null ? r.name() : itemName(r.itemId());
			g.drawString(font, name, cl + 28, y + 7, r.name() != null ? GOLD : TEXT, false);

			double change = r.changePercent();
			int changeColor = change > 0.05 ? GREEN : change < -0.05 ? RED : FAINT;
			rightText(g, Fmt.percent(change), colChange, y + 7, changeColor);
			rightText(g, Fmt.compactPlain(r.base()), colBase, y + 7, MUTED);
			rightText(g, Fmt.exact(r.rate()) + " " + r.targetLabel(), colRate, y + 7, TEXT);
		}
		g.disableScissor();
	}

	private void rightText(GuiGraphics g, String s, int right, int y, int color) {
		g.drawString(font, s, right - font.width(s), y, color, false);
	}

	private static ItemStack icon(String id) {
		return ICONS.computeIfAbsent(id, k -> {
			Identifier rid = Identifier.tryParse(k);
			Item item = rid == null ? Items.BARRIER : BuiltInRegistries.ITEM.getOptional(rid).orElse(Items.BARRIER);
			return new ItemStack(item);
		});
	}

	private static String itemName(String id) {
		return icon(id).getHoverName().getString();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		if (mx >= cl && mx < cr && my >= ct && my < cb) {
			scroll -= (int) Math.signum(vertical) * 2;
			if (scroll < 0) {
				scroll = 0;
			}
			return true;
		}
		return super.mouseScrolled(mx, my, horizontal, vertical);
	}
}
