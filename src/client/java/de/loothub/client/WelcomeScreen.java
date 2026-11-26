package de.loothub.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.loothub.client.jobs.WidgetConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Willkommens-Assistent: Bei einer Neuinstallation ist alles aus. Beim ersten Betreten einer Welt fragt Loothub, wie
 * man spielt (Kacheln zum Anklicken) und schaltet die passenden Funktions-Pakete ein. Jederzeit erneut über die
 * Einstellungen (Allgemein) aufrufbar; dort lässt sich danach alles einzeln ändern.
 */
public class WelcomeScreen extends Screen {
	private record Pack(String key, String icon, boolean preselected, Consumer<WidgetConfig> apply) {
	}

	private static final List<Pack> PACKS = List.of(
			new Pack("basics", "⭐", true, c -> {
				c.tooltipPrices = c.inventoryDesign = c.lookInfo = c.signWindow = c.rarityFrames = true;
				c.durabilityNumbers = c.dropProtection = c.toolProtection = c.pickupWarning = c.foodHud = true;
				c.amountShortcut = c.renameWindow = c.containerValue = c.sellHighlight = c.compressorIcon = true;
			}),
			new Pack("money", "💰", false, c -> {
				c.jobWidgetEnabled = c.marketSales = c.statsEnabled = c.timersEnabled = c.afkView = true;
				c.villagerTrade = c.auctionAlarm = c.itemdexEnabled = c.glowingOres = true;
			}),
			new Pack("fishing", "🎣", false, c -> {
				c.jobWidgetEnabled = c.fishAlert = c.baitWarning = true;
			}),
			new Pack("build", "🏗", false, c -> {
				c.buildHud = c.buildWarn = c.invHudEnabled = true;
			}),
			new Pack("chat", "💬", false, c -> {
				c.customChat = c.chatTimestamps = c.chatCopy = c.chatMention = c.chatMentionSound = true;
			}),
			new Pack("fun", "🎮", false, c -> {
				c.progress = c.statsEnabled = c.cosmetics = c.loothubCrown = true;
			}),
			new Pack("look", "✨", false, c -> {
				c.themePackWanted = c.connectedTextures = c.pulseBorders = true;
			}),
			new Pack("stream", "📺", false, c -> {
				c.streamerMode = c.streamerChat = true;
			}));

	private final Screen parent;
	private final boolean[] chosen = new boolean[PACKS.size()];
	private int cols, cardW, cardH, gridX, gridY;

	public WelcomeScreen(Screen parent) {
		super(Component.translatable("loothub.welcome.title"));
		this.parent = parent;
		for (int i = 0; i < PACKS.size(); i++) {
			chosen[i] = PACKS.get(i).preselected();
		}
	}

	/** Beim ersten Betreten einer Welt nach einer Neuinstallation einmal öffnen. */
	public static void register() {
		int[] wait = {0};
		de.loothub.client.util.Guard.tick("Welcome", mc -> {
			if (WidgetConfig.get().welcomeDone || mc.player == null || mc.level == null) {
				wait[0] = 0;
				return;
			}
			if (mc.screen != null || ++wait[0] < 100) {
				return; // erst ankommen lassen (5 Sekunden, kein anderes Fenster offen)
			}
			mc.setScreen(new WelcomeScreen(null));
		});
	}

	@Override
	protected void init() {
		cols = width >= 520 ? 4 : 2;
		cardW = Math.min(150, (width - 40 - (cols - 1) * 8) / cols);
		cardH = 58;
		int rows = (PACKS.size() + cols - 1) / cols;
		gridX = (width - (cols * cardW + (cols - 1) * 8)) / 2;
		gridY = Math.max(48, (height - rows * (cardH + 8) - 40) / 2 + 10);
		int by = gridY + rows * (cardH + 8) + 6;
		addRenderableWidget(Button.builder(Component.translatable("loothub.welcome.apply"), b -> apply())
				.bounds(width / 2 - 154, by, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.welcome.later"), b -> close())
				.bounds(width / 2 + 4, by, 150, 20).build());
	}

	private void apply() {
		WidgetConfig c = WidgetConfig.get();
		for (int i = 0; i < PACKS.size(); i++) {
			if (chosen[i]) {
				PACKS.get(i).apply().accept(c);
			}
		}
		c.welcomeDone = true;
		c.save();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.translatable("loothub.welcome.done").withStyle(ChatFormatting.GREEN), false);
		}
		minecraft.setScreen(parent);
	}

	private void close() {
		WidgetConfig c = WidgetConfig.get();
		c.welcomeDone = true; // „Später“: nicht erneut aufdrängen – in den Einstellungen jederzeit erreichbar
		c.save();
		minecraft.setScreen(parent);
	}

	@Override
	public void onClose() {
		close();
	}

	private int cardAt(double mx, double my) {
		for (int i = 0; i < PACKS.size(); i++) {
			int x = gridX + (i % cols) * (cardW + 8), y = gridY + (i / cols) * (cardH + 8);
			if (mx >= x && mx < x + cardW && my >= y && my < y + cardH) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		int i = cardAt(e.x(), e.y());
		if (i >= 0 && e.button() == 0) {
			chosen[i] = !chosen[i];
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		super.renderBackground(g, mx, my, delta);
		g.fill(0, 0, width, height, 0xE00F0A0B);
		int accent = LoothubScreen.RED;
		g.drawCenteredString(font, Component.literal("👑 ").append(title).withStyle(ChatFormatting.BOLD), width / 2, gridY - 34, accent);
		g.drawCenteredString(font, Component.translatable("loothub.welcome.sub"), width / 2, gridY - 20, LoothubScreen.MUTED);
		int hover = cardAt(mx, my);
		for (int i = 0; i < PACKS.size(); i++) {
			Pack p = PACKS.get(i);
			int x = gridX + (i % cols) * (cardW + 8), y = gridY + (i / cols) * (cardH + 8);
			boolean on = chosen[i];
			g.fill(x, y, x + cardW, y + cardH, on ? 0xFF2A1416 : 0xFF161011);
			int edge = on ? accent : (i == hover ? 0xFF6A4A4C : LoothubScreen.FRAME_EDGE);
			g.fill(x, y, x + cardW, y + 1, edge);
			g.fill(x, y + cardH - 1, x + cardW, y + cardH, edge);
			g.fill(x, y, x + 1, y + cardH, edge);
			g.fill(x + cardW - 1, y, x + cardW, y + cardH, edge);
			g.drawString(font, p.icon() + " " + Component.translatable("loothub.welcome.pack." + p.key()).getString(), x + 6, y + 6,
					on ? 0xFFFFFFFF : LoothubScreen.MUTED, false);
			g.drawString(font, on ? "✔" : "○", x + cardW - 12, y + 6, on ? 0xFF55E06A : LoothubScreen.FAINT, false);
			List<FormattedCharSequence> lines = font.split(Component.translatable("loothub.welcome.pack." + p.key() + ".desc"), cardW - 12);
			for (int l = 0; l < Math.min(4, lines.size()); l++) {
				g.drawString(font, lines.get(l), x + 6, y + 19 + l * 9, LoothubScreen.FAINT, false);
			}
		}
		g.drawCenteredString(font, Component.translatable("loothub.welcome.hint"), width / 2,
				gridY + ((PACKS.size() + cols - 1) / cols) * (cardH + 8) + 32, LoothubScreen.FAINT);
	}

	static List<String> keys() {
		List<String> k = new ArrayList<>();
		PACKS.forEach(p -> k.add(p.key()));
		return k;
	}
}
