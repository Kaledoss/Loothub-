package de.loothub.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Texturen für das Loothub-Fenster (Taste L): goldener Ornament-Rahmen, Holz in der Kategorie-Leiste, Karten mit
 * Goldrand im Inhalt, Knöpfe aus rotem Leder mit Goldkante und Trennlinien mit Edelstein. Rahmen und Karten liegen
 * in doppelter Auflösung vor und werden halb so groß gezeichnet, damit die Verzierungen fein bleiben.
 */
public final class MenuSkin {
	/** Menü-Themen: "" = Dunkel mit Goldrand, sonst Holz, Eis, Nether, Ende (Bilder von Ludo). */
	public static final String[] THEMES = {"", "wood", "ice", "nether", "end"};
	private static String theme = null;
	private static Identifier FRAME, CARD, WOOD, BUTTON, BUTTON_HOVER, BUTTON_OFF, GEM;

	/** Bilder des eingestellten Themas laden (bei Wechsel neu). */
	private static void sprites() {
		String t = de.loothub.client.jobs.WidgetConfig.get().menuTheme;
		if (t == null) {
			t = "";
		}
		if (t.equals(theme)) {
			return;
		}
		theme = t;
		String dir = t.isEmpty() ? "menu/" : "menu_" + t + "/";
		FRAME = id(dir + "frame");
		CARD = id(dir + "card");
		WOOD = id(dir + "wood");
		BUTTON = id(dir + "button");
		BUTTON_HOVER = id(dir + "button_hover");
		BUTTON_OFF = id(dir + "button_off");
		GEM = id(dir + "gem");
	}
	public static final int GOLD = 0xFFF2C14E;
	private static final int LINE_LIGHT = 0xFFE2BC84;
	private static final int LINE_DARK = 0xFF422111;
	/** Wie weit der Ornament-Rahmen über das Fenster hinausragt. */
	public static final int FRAME_OUT = 9;

	private MenuSkin() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(Loothub.MOD_ID, path);
	}

	/** Texturen eingeschaltet (Einstellung „Verziertes Menü“)? */
	public static boolean on() {
		return WidgetConfig.get().menuSkin;
	}

	private static void half(GuiGraphics g, Identifier sprite, int x, int y, int w, int h) {
		g.pose().pushMatrix();
		g.pose().scale(0.5F, 0.5F);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x * 2, y * 2, w * 2, h * 2);
		g.pose().popMatrix();
	}

	/** Ornament-Rahmen samt Leder-Hintergrund um das ganze Fenster. */
	public static void frame(GuiGraphics g, int x, int y, int w, int h) {
		sprites();
		half(g, FRAME, x - FRAME_OUT, y - FRAME_OUT, w + 2 * FRAME_OUT, h + 2 * FRAME_OUT);
	}

	/** Kasten mit dünnem Goldrand (Inhalt, Abschnitte). */
	public static void card(GuiGraphics g, int x, int y, int w, int h) {
		sprites();
		half(g, CARD, x, y, w, h);
	}

	/** Dunkles Holz, gekachelt (Kategorie-Leiste). */
	public static void wood(GuiGraphics g, int x, int y, int w, int h) {
		sprites();
		g.blitSprite(RenderPipelines.GUI_TEXTURED, WOOD, x, y, w, h);
	}

	/** Knopf aus rotem Leder mit Goldkante. */
	public static void button(GuiGraphics g, int x, int y, int w, int h, boolean active, boolean hover) {
		sprites();
		g.blitSprite(RenderPipelines.GUI_TEXTURED, !active ? BUTTON_OFF : hover ? BUTTON_HOVER : BUTTON, x, y, w, h);
	}

	/** Goldene Trennlinie mit Edelstein in der Mitte. */
	public static void divider(GuiGraphics g, int x1, int x2, int y) {
		g.fill(x1, y, x2, y + 1, LINE_LIGHT);
		g.fill(x1, y + 1, x2, y + 2, LINE_DARK);
		int cx = (x1 + x2) / 2;
		sprites();
		g.blitSprite(RenderPipelines.GUI_TEXTURED, GEM, cx - 5, y - 4, 10, 9);
	}

	/** Vanilla-Knöpfe des Fensters mit den Leder-Knöpfen übermalen (Beschriftung neu darüber). */
	public static void skinButtons(GuiGraphics g, Font font, Iterable<? extends net.minecraft.client.gui.components.Renderable> widgets) {
		for (var r : widgets) {
			if (!(r instanceof Button b) || !b.visible || b.getHeight() < 12) {
				continue;
			}
			button(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), b.active, b.isHoveredOrFocused());
			label(g, font, b, b.getMessage());
		}
	}

	private static void label(GuiGraphics g, Font font, AbstractWidget b, Component message) {
		int max = b.getWidth() - 8;
		String text = message.getString();
		Component shown = message;
		if (font.width(message) > max) {
			shown = Component.literal(font.plainSubstrByWidth(text, Math.max(0, max - font.width("…"))) + "…");
		}
		int color = b.active ? 0xFFFFFFFF : 0xFF8E8284;
		g.drawCenteredString(font, shown, b.getX() + b.getWidth() / 2, b.getY() + (b.getHeight() - 8) / 2, color);
	}
}
