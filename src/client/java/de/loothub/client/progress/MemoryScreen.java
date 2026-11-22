package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.loothub.client.Fmt;

/** Memory mit Minecraft-Items: 18 Paare (6 × 6 Karten). Wenige Züge sind gut; der Rekord ist die kleinste Zugzahl. */
final class MemoryScreen extends ArcadeScreen {
	private static final int COLS = 6, ROWS = 6;
	private static final Item[] POOL = {Items.DIAMOND, Items.EMERALD, Items.GOLDEN_APPLE, Items.ENDER_PEARL, Items.TOTEM_OF_UNDYING,
			Items.NETHER_STAR, Items.ELYTRA, Items.TRIDENT, Items.CAKE, Items.COOKIE, Items.CHEST, Items.BEACON, Items.HEART_OF_THE_SEA,
			Items.AMETHYST_SHARD, Items.BLAZE_ROD, Items.SLIME_BALL, Items.CREEPER_HEAD, Items.DRAGON_EGG, Items.SPYGLASS,
			Items.GOLDEN_CARROT, Items.LAVA_BUCKET, Items.COMPASS, Items.CLOCK, Items.FIREWORK_ROCKET, Items.PUFFERFISH,
			Items.HONEY_BOTTLE, Items.SWEET_BERRIES, Items.ECHO_SHARD, Items.NAUTILUS_SHELL, Items.MUSIC_DISC_CAT};

	private final Random random = new Random();
	private final List<ItemStack> cards = new ArrayList<>();
	private boolean[] open, found;
	private int first = -1, second = -1, hideIn, pairs, cell;
	private long startAt, endAt;

	MemoryScreen(Screen parent) {
		super(parent, "memory");
		reset();
	}

	@Override
	protected boolean higherIsBetter() {
		return false;
	}

	@Override
	protected String scoreText() {
		long ms = startAt == 0 ? 0 : (over ? endAt : System.currentTimeMillis()) - startAt;
		int best = best(game);
		return Component.translatable("loothub.lh.mg.memory.score", score, pairs, COLS * ROWS / 2, Fmt.duration(ms).replace("< 1", "0"),
				best == 0 ? "–" : String.valueOf(best)).getString();
	}

	@Override
	protected int[] fieldSize(int maxW, int maxH) {
		cell = Math.max(20, Math.min(36, Math.min(maxW / COLS, maxH / ROWS)));
		return new int[]{COLS * cell, ROWS * cell};
	}

	@Override
	protected void reset() {
		List<Item> pool = new ArrayList<>(List.of(POOL));
		Collections.shuffle(pool, random);
		cards.clear();
		for (int i = 0; i < COLS * ROWS / 2; i++) {
			cards.add(new ItemStack(pool.get(i)));
			cards.add(new ItemStack(pool.get(i)));
		}
		Collections.shuffle(cards, random);
		open = new boolean[cards.size()];
		found = new boolean[cards.size()];
		first = second = -1;
		hideIn = 0;
		pairs = 0;
		startAt = 0;
	}

	@Override
	protected void step() {
		if (hideIn > 0 && --hideIn == 0) {
			open[first] = false;
			open[second] = false;
			first = second = -1;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (over || e.button() != 0) {
			return super.mouseClicked(e, doubleClick);
		}
		int cx = (int) ((e.x() - fx) / cell), cy = (int) ((e.y() - fy) / cell);
		if (e.x() < fx || e.y() < fy || cx >= COLS || cy >= ROWS) {
			return super.mouseClicked(e, doubleClick);
		}
		begin();
		if (startAt == 0) {
			startAt = System.currentTimeMillis();
		}
		paused = false;
		int i = cy * COLS + cx;
		if (open[i] || found[i]) {
			return true;
		}
		if (hideIn > 0) {
			// zwei falsche noch offen: sofort zudecken und weiter
			open[first] = false;
			open[second] = false;
			first = second = -1;
			hideIn = 0;
		}
		open[i] = true;
		click(1.6F);
		if (first < 0) {
			first = i;
		} else {
			second = i;
			score++;
			if (ItemStack.isSameItem(cards.get(first), cards.get(second))) {
				found[first] = found[second] = true;
				pairs++;
				first = second = -1;
				click(2.0F);
				if (pairs == cards.size() / 2) {
					endAt = System.currentTimeMillis();
					gameOver();
				}
			} else {
				hideIn = 18;
			}
		}
		return true;
	}

	private void click(float pitch) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.25F, pitch);
		}
	}

	@Override
	protected void drawField(GuiGraphics g, float partial) {
		g.fill(fx, fy, fx + fw, fy + fh, 0xFF1A1214);
		for (int i = 0; i < cards.size(); i++) {
			int x = fx + (i % COLS) * cell, y = fy + (i / COLS) * cell;
			int c0 = found[i] ? 0xFF2E5A34 : open[i] ? 0xFF3A2A2C : 0xFF6E1A22;
			g.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, c0);
			if (open[i] || found[i]) {
				g.renderItem(cards.get(i), x + (cell - 16) / 2, y + (cell - 16) / 2);
			} else {
				g.fill(x + 3, y + 3, x + cell - 3, y + cell - 3, 0xFF8E1F26);
				g.drawCenteredString(font, "?", x + cell / 2, y + cell / 2 - 4, 0xFFF2C14E);
			}
		}
	}
}
