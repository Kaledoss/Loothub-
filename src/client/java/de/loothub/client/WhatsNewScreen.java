package de.loothub.client;

import java.util.ArrayList;
import java.util.List;

import de.loothub.client.jobs.WidgetConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * „Was ist neu?“: nach einem Update einmal beim Betreten einer Welt die wichtigsten Neuerungen der Version (aus dem
 * geschriebenen Changelog), dazu wie viele Fehler behoben wurden. Testversionen zeigen nichts.
 */
public class WhatsNewScreen extends Screen {
	private static final int MAX_ITEMS = 6;
	private final String version;
	private final List<String[]> changes;

	private WhatsNewScreen(String version, List<String[]> changes) {
		super(Component.translatable("loothub.whatsnew.title", version));
		this.version = version;
		this.changes = changes;
	}

	public static void register() {
		int[] wait = {0};
		de.loothub.client.util.Guard.tick("WhatsNew", mc -> {
			WidgetConfig c = WidgetConfig.get();
			String now = Updater.currentVersion();
			if (now.equals(c.lastSeenVersion) || mc.player == null || mc.level == null) {
				wait[0] = 0;
				return;
			}
			if (c.lastSeenVersion.isEmpty() || !c.welcomeDone || now.contains("-") || now.equals("dev")) {
				// Neuinstallation (dafür gibt es den Willkommens-Assistenten) oder Testversion: nur merken
				if (c.welcomeDone || now.contains("-")) {
					c.lastSeenVersion = now;
					c.save();
				}
				return;
			}
			ChangelogScreen.ensureLoaded();
			if (mc.screen != null || ++wait[0] < 120) {
				return; // erst ankommen lassen
			}
			List<String[]> ch = ChangelogScreen.changesFor(now);
			if (ch == null) {
				return; // lädt noch
			}
			c.lastSeenVersion = now;
			c.save();
			if (!ch.isEmpty()) {
				mc.setScreen(new WhatsNewScreen(now, ch));
			}
		});
	}

	private int pw() {
		return Math.min(380, width - 40);
	}

	private int px() {
		return (width - pw()) / 2;
	}

	private List<FormattedCharSequence[]> lines;
	private int fixes, more, ph;

	@Override
	protected void init() {
		int w = pw() - 32;
		lines = new ArrayList<>();
		fixes = 0;
		int adds = 0;
		for (String[] c : changes) {
			if (c[0].equals("Fix")) {
				fixes++;
			} else if (c[0].equals("Add")) {
				if (adds++ < MAX_ITEMS) {
					lines.add(font.split(Component.literal(c[1]), w).toArray(new FormattedCharSequence[0]));
				}
			}
		}
		more = Math.max(0, adds - MAX_ITEMS);
		int h = 40;
		for (FormattedCharSequence[] l : lines) {
			h += l.length * 10 + 4;
		}
		h += 46;
		ph = Math.min(height - 20, h);
		int py = (height - ph) / 2;
		int x = px() + 12, bw = (pw() - 30) / 2;
		addRenderableWidget(Button.builder(Component.translatable("loothub.whatsnew.full"), b -> minecraft.setScreen(new ChangelogScreen()))
				.bounds(x, py + ph - 26, bw, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("loothub.whatsnew.ok"), b -> onClose())
				.bounds(x + bw + 6, py + ph - 26, bw, 18).build());
	}

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		super.renderBackground(g, mx, my, delta);
		int x = px(), w = pw(), py = (height - ph) / 2;
		g.fill(x, py, x + w, py + ph, LoothubScreen.FRAME_BG);
		g.fill(x, py, x + w, py + 2, LoothubScreen.RED);
		g.drawCenteredString(font, Component.literal("✨ ").append(title).withStyle(ChatFormatting.BOLD), x + w / 2, py + 10, LoothubScreen.RED);
		int y = py + 28;
		for (FormattedCharSequence[] l : lines) {
			g.drawString(font, "+", x + 12, y, 0xFF55E06A, false);
			for (FormattedCharSequence s : l) {
				g.drawString(font, s, x + 22, y, LoothubScreen.TEXT, false);
				y += 10;
			}
			y += 4;
		}
		String foot = (more > 0 ? Component.translatable("loothub.whatsnew.more", more).getString() + "  " : "")
				+ (fixes > 0 ? Component.translatable("loothub.whatsnew.fixes", fixes).getString() : "");
		if (!foot.isBlank()) {
			g.drawCenteredString(font, foot, x + w / 2, y + 2, LoothubScreen.FAINT);
		}
	}
}
