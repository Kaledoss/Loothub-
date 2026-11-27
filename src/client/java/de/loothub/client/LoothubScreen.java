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
	public static int RED = 0xFFE23B3B; // Akzentfarbe, wird von Theme gesetzt
	public static int RED_DARK = 0xFF8E1F26;
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
		FISHING("loothub.tab.fishing", Items.FISHING_ROD),
		MARKET("loothub.tab.market", Items.EMERALD),
		MERCHANT("loothub.tab.merchant", Items.COMPOSTER),
		STATS("loothub.tab.stats", Items.FILLED_MAP),
		POKEDEX("loothub.tab.pokedex", Items.KNOWLEDGE_BOOK),
		ITEMDEX("loothub.tab.itemdex", Items.NETHER_STAR),
		COSMETICS("loothub.tab.cosmetics", Items.ARMOR_STAND),
		ALLTAG("loothub.tab.alltag", Items.CLOCK),
		BUILD("loothub.tab.build", Items.BRICKS),
		STREAMER("loothub.tab.streamer", Items.AMETHYST_CLUSTER),
		QUESTS("loothub.tab.quests", Items.EXPERIENCE_BOTTLE),
		FRIENDS("loothub.tab.friends", Items.CAKE),
		CHANGELOG("loothub.tab.changelog", Items.BOOK),
		SETTINGS("loothub.tab.settings", Items.COMPARATOR);

		final String translationKey;
		final Item icon;
		/** Eigenes Pixel-Icon (Ludo) in der Kategorie-Leiste. */
		final Identifier sprite;

		Page(String translationKey, Item icon) {
			this.translationKey = translationKey;
			this.icon = icon;
			String key = translationKey.substring(translationKey.lastIndexOf('.') + 1);
			this.sprite = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "icons/" + key);
		}

		/** Seite in der Leiste zeigen? „Streamer“ nur bei eingeschaltetem Streamer-Modus. */
		boolean shown() {
			de.loothub.client.jobs.WidgetConfig c = de.loothub.client.jobs.WidgetConfig.get();
			return switch (this) {
				case STREAMER -> c.streamerMode;
				case POKEDEX -> c.pokedexPage;
				case BUILD -> de.loothub.client.bau.Build.present(); // nur mit Litematica
				default -> true;
			};
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
			case FISHING -> new de.loothub.client.jobs.FishingHistoryScreen();
			case MARKET -> new MarketScreen();
			case MERCHANT -> new MerchantScreen();
			case STATS -> new StatsScreen();
			case POKEDEX -> new PokedexScreen();
			case ITEMDEX -> new ItemdexScreen();
			case QUESTS -> new de.loothub.client.progress.ProgressScreen();
			case FRIENDS -> new de.loothub.client.friends.FriendsScreen();
			case COSMETICS -> new CosmeticsScreen();
			case ALLTAG -> new AlltagScreen();
			case BUILD -> new de.loothub.client.bau.BuildScreen();
			case STREAMER -> new de.loothub.client.streamer.StreamerScreen();
			case CHANGELOG -> new ChangelogScreen();
			case SETTINGS -> new SettingsScreen();
		});
	}

	// ------------------------------------------------------------------ Aufbau

	@Override
	protected final void init() {
		int margin = Mth.clamp(Math.min(width, height) / 24, MenuSkin.on() ? MenuSkin.FRAME_OUT + 3 : 4, 24);
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
		// Feedback melden (alle) und Meldungen ansehen (nur Admins) – oben rechts neben „Update“
		int fx = b.getX() - 4;
		Component fbl = Component.translatable("loothub.feedback.button");
		int fw = font.width(fbl) + 12;
		fx -= fw;
		addRenderableWidget(Button.builder(fbl, x -> minecraft.setScreen(new de.loothub.client.feedback.FeedbackScreen(this)))
				.bounds(fx, frameY + 2, fw, b.getHeight()).build());
		// Datensicherung & Umzug (Export/Import)
		Component bkl = Component.translatable("loothub.backup.button");
		int bkw = font.width(bkl) + 12;
		fx -= bkw + 4;
		addRenderableWidget(Button.builder(bkl, x -> minecraft.setScreen(new BackupScreen(this)))
				.bounds(fx, frameY + 2, bkw, b.getHeight()).build());
		if (de.loothub.client.progress.Progress.workerOutdated()) {
			// Admin-Hinweis: Server-Worker ist älter als diese Mod – neue Funktionen fehlen sonst still
			Component wl = Component.translatable("loothub.worker.outdated").withStyle(net.minecraft.ChatFormatting.GOLD);
			int ww = font.width(wl) + 12;
			Button wb = addRenderableWidget(Button.builder(wl, x -> { }).bounds(fx - 4 - ww, frameY + 2, ww, b.getHeight()).build());
			wb.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
					Component.translatable("loothub.worker.outdated.tooltip", de.loothub.client.progress.Progress.WORKER_NEEDED)));
			fx -= ww + 4;
		}
		if (de.loothub.client.progress.Progress.isAdmin()) {
			int unread = de.loothub.client.progress.Progress.feedbackUnread();
			Component al = Component.translatable("loothub.feedback.list_button");
			if (unread > 0) {
				al = al.copy().append(Component.literal(" +" + unread).withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
			}
			int aw = font.width(al) + 12;
			addRenderableWidget(Button.builder(al, x -> minecraft.setScreen(new de.loothub.client.feedback.FeedbackListScreen(this)))
					.bounds(fx - 4 - aw, frameY + 2, aw, b.getHeight()).build());
			Component ol = Component.translatable("loothub.admin.overview_button");
			int ow = font.width(ol) + 12;
			addRenderableWidget(Button.builder(ol, x -> minecraft.setScreen(new de.loothub.client.feedback.AdminOverviewScreen(this)))
					.bounds(fx - 8 - aw - ow, frameY + 2, ow, b.getHeight()).build());
		}

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

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		if (MenuSkin.on()) {
			// Knöpfe im Leder-Stil über die Vanilla-Knöpfe legen
			g.nextStratum();
			MenuSkin.skinButtons(g, font, children().stream()
					.filter(c -> c instanceof net.minecraft.client.gui.components.Renderable)
					.map(c -> (net.minecraft.client.gui.components.Renderable) c).toList());
		}
	}

	/** Fenster, Titelleiste, Kategorien und Kopfzeile – alles unter den Knöpfen. */
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.nextStratum();
		g.fill(0, 0, width, height, de.loothub.client.Theme.bg(0xA0000000));
		if (MenuSkin.on()) {
			renderSkinned(g, mouseX, mouseY);
			return;
		}

		// Rahmen (doppelt: außen dunkelrot, innen schwarz)
		g.fill(frameX - 1, frameY - 1, frameX + frameW + 1, frameY + frameH + 1, de.loothub.client.Theme.bg(RED_DARK));
		g.fill(frameX, frameY, frameX + frameW, frameY + frameH, de.loothub.client.Theme.bg(FRAME_BG));

		// Titelleiste
		g.fill(frameX, frameY, frameX + frameW, frameY + TITLE_H, de.loothub.client.Theme.bg(TITLE_BG));
		g.fill(frameX, frameY + TITLE_H - 1, frameX + frameW, frameY + TITLE_H, RED);
		Component title = Component.literal("LOOTHUB").withStyle(ChatFormatting.BOLD)
				.append(Component.literal("+").withStyle(ChatFormatting.BOLD))
				.withStyle(s -> s.withColor(RED & 0xFFFFFF))
				.append(Component.literal("  von Kaledoss").withStyle(s -> s.withColor(FAINT & 0xFFFFFF).withBold(false)));
		int titleW = 16 + font.width(title);
		int tx = frameX + (frameW - titleW) / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, ICON, tx, frameY + 3, 0f, 0f, 13, 13, 128, 128, 128, 128);
		g.drawString(font, title, tx + 16, frameY + 6, TEXT, true);

		// Kategorien
		int sx = frameX + 4;
		int sy = frameY + TITLE_H + 4;
		int sh = frameH - TITLE_H - 8;
		g.fill(sx, sy, sx + sidebarW, sy + sh, de.loothub.client.Theme.bg(SIDEBAR_BG));
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
			if (!p.shown()) {
				continue;
			}
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
			g.blitSprite(RenderPipelines.GUI_TEXTURED, p.sprite, sx + 7, ey + 3, 16, 16);
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
		g.fill(hx, frameY + TITLE_H + 4, cr + 4, cb + 4, de.loothub.client.Theme.bg(CONTENT_BG));
		Component pageTitle = Component.translatable(page.translationKey + ".header");
		// Überschrift oben, Trennlinie darunter (nicht durch die Schrift)
		g.drawString(font, pageTitle, cl, frameY + TITLE_H + 5, TEXT, false);
		g.fill(cl, ct - 3, cr, ct - 2, FRAME_EDGE);
		Component right = headerRight();
		if (right != null) {
			g.drawString(font, right, cr - font.width(right), frameY + TITLE_H + 5, FAINT, false);
		}
	}

	/** Verziertes Fenster: Ornament-Rahmen, Holz-Leiste, Karte für den Inhalt, goldene Trennlinien. */
	private void renderSkinned(GuiGraphics g, int mouseX, int mouseY) {
		MenuSkin.frame(g, frameX, frameY, frameW, frameH);

		// Titel und Trennlinie
		Component title = Component.literal("LOOTHUB").withStyle(ChatFormatting.BOLD)
				.append(Component.literal("+").withStyle(ChatFormatting.BOLD))
				.withStyle(s -> s.withColor(MenuSkin.GOLD & 0xFFFFFF))
				.append(Component.literal("  von Kaledoss").withStyle(s -> s.withColor(MUTED & 0xFFFFFF).withBold(false)));
		int titleW = 16 + font.width(title);
		int tx = frameX + (frameW - titleW) / 2;
		g.blit(RenderPipelines.GUI_TEXTURED, ICON, tx, frameY + 2, 0f, 0f, 13, 13, 128, 128, 128, 128);
		g.drawString(font, title, tx + 16, frameY + 5, TEXT, true);
		MenuSkin.divider(g, frameX + 8, frameX + frameW - 8, frameY + TITLE_H - 2);

		// Kategorien auf Holz
		int sx = frameX + 4;
		int sy = frameY + TITLE_H + 4;
		int sh = frameH - TITLE_H - 8;
		MenuSkin.wood(g, sx, sy, sidebarW, sh);
		g.fill(sx + sidebarW, sy, sx + sidebarW + 1, sy + sh, 0xFF2A1A12);
		boolean compact = sidebarW < 60;
		int ey = sy + 6;
		if (!compact) {
			Component cat = Component.translatable("loothub.menu.categories");
			g.drawString(font, cat, sx + (sidebarW - font.width(cat)) / 2, ey, MenuSkin.GOLD, true);
			ey += 16;
		}
		Page hoveredPage = null;
		for (Page p : Page.values()) {
			if (!p.shown()) {
				continue;
			}
			boolean selected = p == page;
			boolean hover = mouseX >= sx && mouseX < sx + sidebarW && mouseY >= ey && mouseY < ey + ENTRY_H;
			if (hover) {
				hoveredPage = p;
			}
			if (selected) {
				MenuSkin.button(g, sx + 2, ey, sidebarW - 4, ENTRY_H, true, false);
			} else if (hover) {
				g.fill(sx + 2, ey + 1, sx + sidebarW - 2, ey + ENTRY_H - 1, 0x30F2C14E);
			}
			g.blitSprite(RenderPipelines.GUI_TEXTURED, p.sprite, sx + 7, ey + 3, 16, 16);
			if (!compact) {
				g.drawString(font, Component.translatable(p.translationKey), sx + 28, ey + 7,
						selected ? TEXT : hover ? 0xFFF5E6C8 : 0xFFC9B79C, true);
			}
			ey += ENTRY_H + 2;
		}
		if (compact && hoveredPage != null) {
			g.setTooltipForNextFrame(font, Component.translatable(hoveredPage.translationKey), mouseX, mouseY);
		}

		// Inhalt in einer Karte mit Goldrand
		int hx = cl - 4;
		MenuSkin.card(g, hx - 2, frameY + TITLE_H + 2, cr + 6 - (hx - 2), cb + 6 - (frameY + TITLE_H + 2));
		Component pageTitle = Component.translatable(page.translationKey + ".header");
		g.drawString(font, pageTitle, cl, frameY + TITLE_H + 6, TEXT, true);
		g.fill(cl, ct - 3, cr, ct - 2, 0xFF6B4A22);
		Component right = headerRight();
		if (right != null) {
			g.drawString(font, right, cr - font.width(right), frameY + TITLE_H + 6, FAINT, false);
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
				if (!p.shown()) {
					continue;
				}
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
