package de.loothub.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.loothub.Loothub;
import de.loothub.client.jobs.JobHistoryScreen;
import de.loothub.client.stats.StatsScreen;

/**
 * Gemeinsames Fenster aller Loothub+-Seiten (schwarz-rot): Titelleiste mit Krone, links die Kategorien,
 * rechts der Inhalt der Seite. Unterklassen bauen ihren Inhalt in {@link #initPage()} im Bereich
 * {@code cl/ct/cr/cb} (links/oben/rechts/unten) auf.
 */
public abstract class LoothubScreen extends Screen {
	// Farbschema schwarz-rot
	public static final int RED = 0xFFE23B3B;
	public static final int RED_DARK = 0xFF8E1F26;
	public static final int FRAME_BG = 0xF60F0A0B;
	public static final int FRAME_EDGE = 0xFF4A161B;
	public static final int TITLE_BG = 0xFF170C0E;
	public static final int SIDEBAR_BG = 0xFF130C0E;
	public static final int CONTENT_BG = 0xFF110B0C;
	public static final int TEXT = 0xFFFFFFFF;
	public static final int MUTED = 0xFFB9A9AB;
	public static final int FAINT = 0xFF7A6A6C;

	private static final Identifier ICON = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "icon.png");
	private static final int TITLE_H = 20;
	private static final int ENTRY_H = 22;

	/** Die Seiten in der Kategorie-Leiste. */
	public enum Page {
		HISTORY("loothub.tab.history", Items.WRITABLE_BOOK),
		MARKET("loothub.tab.market", Items.EMERALD),
		STATS("loothub.tab.stats", Items.FILLED_MAP),
		SETTINGS("loothub.tab.settings", Items.COMPARATOR);

		final String translationKey;
		final Item icon;

		Page(String translationKey, Item icon) {
			this.translationKey = translationKey;
			this.icon = icon;
		}
	}

	protected final Page page;
	protected Button updateButton;

	protected int frameX, frameY, frameW, frameH;
	protected int sidebarW;
	/** Inhaltsbereich der Seite. */
	protected int cl, ct, cr, cb, cw, ch;

	protected LoothubScreen(Page page) {
		super(Component.translatable(page.translationKey));
		this.page = page;
	}

	public static void open(Page page) {
		Minecraft.getInstance().setScreen(switch (page) {
			case HISTORY -> new JobHistoryScreen();
			case MARKET -> new MarketScreen();
			case STATS -> new StatsScreen();
			case SETTINGS -> new SettingsScreen();
		});
	}

	// ------------------------------------------------------------------ Aufbau

	@Override
	protected final void init() {
		int margin = Mth.clamp(Math.min(width, height) / 24, 4, 24);
		frameX = margin;
		frameY = margin;
		frameW = width - 2 * margin;
		frameH = height - 2 * margin;
		// Schmale Fenster: Kategorien nur als Icons
		sidebarW = frameW >= 520 ? Mth.clamp(frameW / 6, 104, 140) : 30;

		cl = frameX + sidebarW + 10;
		cr = frameX + frameW - 8;
		ct = frameY + TITLE_H + 18;
		cb = frameY + frameH - 8;
		cw = cr - cl;
		ch = cb - ct;

		Button b = Tabs.updateButton(frameY + 2);
		b.setX(frameX + frameW - 4 - b.getWidth());
		updateButton = addRenderableWidget(b);

		initPage();
	}

	/** Inhalt der Seite aufbauen (Knöpfe usw. im Bereich cl/ct/cr/cb). */
	protected abstract void initPage();

	/** Text rechts in der Kopfzeile der Seite (z. B. Stand der Daten), oder null. */
	protected Component headerRight() {
		return null;
	}

	@Override
	public void tick() {
		super.tick();
		Tabs.refreshUpdateButton(updateButton);
	}

	// ------------------------------------------------------------------ Zeichnen

	/** Fenster, Titelleiste, Kategorien und Kopfzeile – alles unter den Knöpfen. */
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.nextStratum();
		g.fill(0, 0, width, height, 0xA0000000);

		// Rahmen (doppelt: außen dunkelrot, innen schwarz)
		g.fill(frameX - 1, frameY - 1, frameX + frameW + 1, frameY + frameH + 1, RED_DARK);
		g.fill(frameX, frameY, frameX + frameW, frameY + frameH, FRAME_BG);

		// Titelleiste
		g.fill(frameX, frameY, frameX + frameW, frameY + TITLE_H, TITLE_BG);
		g.fill(frameX, frameY + TITLE_H - 1, frameX + frameW, frameY + TITLE_H, RED);
		Component title = Component.literal("LOOTHUB").withStyle(ChatFormatting.BOLD)
				.append(Component.literal("+").withStyle(ChatFormatting.BOLD))
				.withStyle(s -> s.withColor(RED & 0xFFFFFF))
				.append(Component.literal("  by Kaledoss").withStyle(s -> s.withColor(FAINT & 0xFFFFFF).withBold(false)));
		int titleW = 16 + font.width(title);
		int tx = frameX + (frameW - titleW) / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, ICON, tx, frameY + 3, 0f, 0f, 13, 13, 128, 128, 128, 128);
		g.drawString(font, title, tx + 16, frameY + 6, TEXT, true);

		// Kategorien
		int sx = frameX + 4;
		int sy = frameY + TITLE_H + 4;
		int sh = frameH - TITLE_H - 8;
		g.fill(sx, sy, sx + sidebarW, sy + sh, SIDEBAR_BG);
		g.fill(sx + sidebarW, sy, sx + sidebarW + 1, sy + sh, FRAME_EDGE);
		boolean compact = sidebarW < 60;
		int ey = sy + 6;
		if (!compact) {
			Component cat = Component.translatable("loothub.menu.categories");
			g.drawString(font, cat, sx + (sidebarW - font.width(cat)) / 2, ey, RED, false);
			ey += 16;
		}
		Page hoveredPage = null;
		for (Page p : Page.values()) {
			boolean selected = p == page;
			boolean hover = mouseX >= sx && mouseX < sx + sidebarW && mouseY >= ey && mouseY < ey + ENTRY_H;
			if (hover) {
				hoveredPage = p;
			}
			if (selected) {
				g.fill(sx, ey, sx + sidebarW, ey + ENTRY_H, 0x40E23B3B);
				g.fill(sx, ey, sx + 2, ey + ENTRY_H, RED);
			} else if (hover) {
				g.fill(sx, ey, sx + sidebarW, ey + ENTRY_H, 0x18FFFFFF);
			}
			g.renderItem(new ItemStack(p.icon), sx + 7, ey + 3);
			if (!compact) {
				g.drawString(font, Component.translatable(p.translationKey), sx + 28, ey + 7,
						selected ? TEXT : hover ? 0xFFE8D8DA : MUTED, false);
			}
			ey += ENTRY_H + 2;
		}
		if (compact && hoveredPage != null) {
			g.setTooltipForNextFrame(font, Component.translatable(hoveredPage.translationKey), mouseX, mouseY);
		}

		// Kopfzeile der Seite
		int hx = cl - 4;
		g.fill(hx, frameY + TITLE_H + 4, cr + 4, cb + 4, CONTENT_BG);
		Component pageTitle = Component.translatable(page.translationKey + ".header");
		g.drawString(font, pageTitle, cl, frameY + TITLE_H + 8, TEXT, false);
		g.fill(cl, ct - 5, cr, ct - 4, FRAME_EDGE);
		Component right = headerRight();
		if (right != null) {
			g.drawString(font, right, cr - font.width(right), frameY + TITLE_H + 8, FAINT, false);
		}
	}

	// ------------------------------------------------------------------ Eingabe

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == 0) {
			int sx = frameX + 4;
			int ey = frameY + TITLE_H + 4 + 6 + (sidebarW < 60 ? 0 : 16);
			for (Page p : Page.values()) {
				if (event.x() >= sx && event.x() < sx + sidebarW && event.y() >= ey && event.y() < ey + ENTRY_H) {
					if (p != page) {
						open(p);
					}
					return true;
				}
				ey += ENTRY_H + 2;
			}
		}
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
