package de.loothub.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import de.loothub.client.cosmetics.AuraTextures;
import de.loothub.client.cosmetics.Cosmetics;
import de.loothub.client.cosmetics.SkinLookup;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Seite „Cosmetics“: Anzeige an/aus, bis zu 6 Mini-Mes (je mit beliebigem Spieler-Skin), Aura in jeder Farbe und
 * der fertige Eintrag für {@code cosmetics.json}, damit alle mit Loothub+ die Cosmetics sehen.
 */
public class CosmeticsScreen extends LoothubScreen {
	private static final int CARD = 0xE01C1315;
	private static final int CARD_EDGE = 0xFF3A1F23;
	private static final int GREEN = 0xFF5BD68A;
	private static final int GAP = 6;

	private record Section(int y, int h, String title, String desc) {
	}

	private final List<Section> sections = new ArrayList<>();
	private final int[][] skinStatus = new int[Cosmetics.MAX_MINI_MES][];
	private int auraSwatchX, auraSwatchY;
	private int jsonY;
	private int descX;

	public CosmeticsScreen() {
		super(Page.COSMETICS);
	}

	@Override
	protected void initPage() {
		sections.clear();
		WidgetConfig c = WidgetConfig.get();
		int labelW = Math.min(130, cw / 3);
		descX = cl + labelW + 12;
		int y = ct;

		// 1 · Anzeige
		addRenderableWidget(Button.builder(onOff(c.cosmetics), b -> {
			c.cosmetics = !c.cosmetics;
			b.setMessage(onOff(c.cosmetics));
			c.save();
		}).bounds(cl + 8, y + 20, labelW - 16, 16).build());
		sections.add(new Section(y, 42, "loothub.cosmetics.show", "loothub.cosmetics.show.desc"));
		y += 42 + GAP;

		// 2 · Mini-Mes: Reihe 1 = Schultern + schwebend (1–4), Reihe 2 = krabbelnd (5–6) + Übernehmen
		int fw = Math.max(60, Math.min(110, (cr - 8 - descX - 3 * 16) / 4));
		List<EditBox> boxes = new ArrayList<>();
		for (int i = 0; i < Cosmetics.MAX_MINI_MES; i++) {
			int col = i < 4 ? i : i - 4;
			int row = i < 4 ? 0 : 1;
			int bx = descX + col * (fw + 16);
			int by = y + 30 + row * 20;
			EditBox box = new EditBox(font, bx, by, fw, 16, Component.translatable("loothub.settings.minime"));
			box.setMaxLength(16);
			box.setHint(Component.translatable("loothub.cosmetics.minime.slot" + (i + 1)).withStyle(ChatFormatting.DARK_GRAY));
			box.setValue(i < c.miniMes.size() && c.miniMes.get(i) != null ? c.miniMes.get(i) : "");
			boxes.add(addRenderableWidget(box));
			skinStatus[i] = new int[]{bx + fw + 4, by + 4};
		}
		addRenderableWidget(Button.builder(Component.translatable("loothub.settings.minime.apply"), b -> {
			c.miniMes.clear();
			for (EditBox box : boxes) {
				String v = box.getValue().strip();
				c.miniMes.add(v); // Platz bleibt erhalten, leer = frei
				if (!v.isEmpty()) {
					SkinLookup.forget(v);
				}
			}
			c.save();
		}).bounds(descX + 2 * (fw + 16), y + 50, 90, 16).build());
		sections.add(new Section(y, 74, "loothub.settings.minime", "loothub.settings.minime.tooltip"));
		y += 74 + GAP;

		// 3 · Aura
		boolean auraOn = c.auraColor != null && !c.auraColor.isBlank();
		addRenderableWidget(Button.builder(onOff(auraOn), b -> {
			c.auraColor = c.auraColor == null || c.auraColor.isBlank() ? lastAura(c) : "";
			c.save();
			rebuildWidgets();
		}).bounds(cl + 8, y + 20, labelW - 16, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.cosmetics.aura.pick"), b ->
				minecraft.setScreen(new ColorPickerScreen(this, Component.translatable("loothub.settings.aura"),
						AuraTextures.parse(lastAura(c)), rgb -> {
							c.auraColor = String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
							c.auraLast = c.auraColor;
							c.save();
						})))
				.bounds(descX, y + 30, 120, 16).build());
		auraSwatchX = descX + 126;
		auraSwatchY = y + 33;
		sections.add(new Section(y, 54, "loothub.settings.aura", "loothub.settings.aura.tooltip"));
		y += 54 + GAP;

		// 4 · Für alle sichtbar
		addRenderableWidget(Button.builder(Component.translatable("loothub.cosmetics.copy"), b -> {
			minecraft.keyboardHandler.setClipboard(jsonLine());
			b.setMessage(Component.translatable("loothub.cosmetics.copied").withStyle(ChatFormatting.GREEN));
		}).bounds(cl + 8, y + 20, labelW - 16, 16).build());
		jsonY = y + 34;
		sections.add(new Section(y, 64, "loothub.cosmetics.share", "loothub.cosmetics.share.desc"));
	}

	private static String lastAura(WidgetConfig c) {
		return c.auraLast == null || c.auraLast.isBlank() ? "#FFCD3C" : c.auraLast;
	}

	private static Component onOff(boolean on) {
		return Component.translatable(on ? "loothub.settings.on" : "loothub.settings.off")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
	}

	/** Eintrag für cosmetics.json mit den eigenen Einstellungen. */
	private String jsonLine() {
		WidgetConfig c = WidgetConfig.get();
		String me = minecraft.player != null ? minecraft.player.getGameProfile().name() : "DeinName";
		List<String> parts = new ArrayList<>();
		int last = -1;
		for (int i = 0; i < c.miniMes.size(); i++) {
			if (c.miniMes.get(i) != null && !c.miniMes.get(i).isBlank()) {
				last = i;
			}
		}
		if (last >= 0) {
			List<String> q = new ArrayList<>();
			for (int i = 0; i <= last; i++) {
				String s = c.miniMes.get(i) == null ? "" : c.miniMes.get(i).strip();
				q.add(s.isEmpty() ? "null" : "\"" + (s.equalsIgnoreCase(me) ? "" : s) + "\"");
			}
			parts.add("\"minime\": {\"" + me + "\": [" + String.join(", ", q) + "]}");
		}
		if (c.auraColor != null && !c.auraColor.isBlank()) {
			parts.add("\"aura\": {\"" + me + "\": \"" + c.auraColor + "\"}");
		}
		return parts.isEmpty() ? "–" : String.join(", ", parts);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.renderBackground(g, mouseX, mouseY, delta);
		int labelW = descX - 12 - cl;
		for (Section s : sections) {
			g.fill(cl, s.y(), cl + cw, s.y() + s.h(), Theme.bg(CARD));
			g.fill(cl, s.y() + s.h() - 1, cl + cw, s.y() + s.h(), Theme.bg(CARD_EDGE));
			g.fill(cl + labelW + 6, s.y() + 5, cl + labelW + 7, s.y() + s.h() - 5, Theme.bg(CARD_EDGE));
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int labelW = descX - 12 - cl;
		for (Section s : sections) {
			Component title = Component.translatable(s.title());
			g.drawString(font, title, cl + 8 + (labelW - 16 - font.width(title)) / 2, s.y() + 6, TEXT, false);
			int ty = s.y() + 6;
			for (FormattedCharSequence line : font.split(Component.translatable(s.desc()), cr - 8 - descX)) {
				g.drawString(font, line, descX, ty, MUTED, false);
				ty += 10;
				if (ty > s.y() + 24) {
					break; // Platz für die Bedienelemente darunter
				}
			}
		}
		// Status je Mini-Me
		WidgetConfig c = WidgetConfig.get();
		for (int i = 0; i < skinStatus.length && i < c.miniMes.size(); i++) {
			if (skinStatus[i] == null || c.miniMes.get(i) == null || c.miniMes.get(i).isBlank()) {
				continue;
			}
			SkinLookup.State st = SkinLookup.state(c.miniMes.get(i));
			String mark = st == SkinLookup.State.READY ? "✔" : st == SkinLookup.State.NOT_FOUND ? "✖" : "…";
			int color = st == SkinLookup.State.READY ? GREEN : st == SkinLookup.State.NOT_FOUND ? RED : FAINT;
			g.drawString(font, mark, skinStatus[i][0], skinStatus[i][1], color, false);
		}
		// Aura-Farbe
		boolean auraOn = c.auraColor != null && !c.auraColor.isBlank();
		int rgb = AuraTextures.parse(auraOn ? c.auraColor : lastAura(c));
		g.fill(auraSwatchX - 1, auraSwatchY - 1, auraSwatchX + 11, auraSwatchY + 11, 0xFF000000);
		g.fill(auraSwatchX, auraSwatchY, auraSwatchX + 10, auraSwatchY + 10, 0xFF000000 | rgb);
		g.drawString(font, String.format(Locale.ROOT, "#%06X", rgb) + (auraOn ? "" : " · " + Component.translatable("loothub.settings.off").getString()),
				auraSwatchX + 16, auraSwatchY + 1, auraOn ? TEXT : FAINT, false);
		// fertiger Eintrag für cosmetics.json
		String json = jsonLine();
		int y = jsonY;
		for (FormattedCharSequence line : font.split(Component.literal(json), cr - 8 - descX)) {
			g.drawString(font, line, descX, y, 0xFFFFD66B, false);
			y += 10;
		}
	}
}
