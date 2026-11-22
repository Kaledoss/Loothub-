package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import de.loothub.client.Fmt;

/**
 * Wortsuche: 10 Minecraft-Begriffe in einem 12×12-Buchstabenfeld (waagrecht, senkrecht, diagonal). Erstes und letztes
 * Feld eines Wortes nacheinander anklicken (oder ziehen). Gewertet wird die Zeit in Sekunden – weniger ist besser.
 */
final class WordScreen extends ArcadeScreen {
	private static final int N = 12, WORDS = 10;
	private static final String[] DE = {"CREEPER", "DIAMANT", "SPITZHACKE", "ENDERMAN", "NETHER", "LOHE", "REDSTONE", "OBSIDIAN",
			"SMARAGD", "ZOMBIE", "SKELETT", "TRUHE", "WERKBANK", "FACKEL", "ELYTRA", "DREIZACK", "LEUCHTFEUER", "AMETHYST", "KUPFER",
			"SCHLEIM", "GHAST", "WITHER", "DRACHE", "ANGEL", "BOGEN", "SCHILD", "LAVA", "AMBOSS", "SPAWNER", "BRUCHSTEIN", "KOMPASS",
			"PILGER", "ERZ", "AXT", "BEACON", "TOTEM", "FACKEL", "SCHAF", "PFERD", "PANDA"};
	private static final String[] EN = {"CREEPER", "DIAMOND", "PICKAXE", "ENDERMAN", "NETHER", "BLAZE", "REDSTONE", "OBSIDIAN",
			"EMERALD", "ZOMBIE", "SKELETON", "VILLAGER", "CHEST", "TORCH", "PUMPKIN", "ELYTRA", "TRIDENT", "BEACON", "AMETHYST", "COPPER",
			"SLIME", "GHAST", "WITHER", "DRAGON", "SHIELD", "LAVA", "ANVIL", "SPAWNER", "COMPASS", "TOTEM", "SHEEP", "HORSE", "PANDA",
			"ORE", "AXE", "BOW"};
	private static final int[][] DIRS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

	private record Placed(String word, int x, int y, int dx, int dy) {
	}

	private final Random random = new Random();
	private final char[][] grid = new char[N][N];
	private final List<Placed> words = new ArrayList<>();
	private final List<Placed> found = new ArrayList<>();
	private int cell;
	private int selX = -1, selY = -1;
	private long startAt;
	private int seconds;

	WordScreen(Screen parent) {
		super(parent, "word");
		reset();
	}

	@Override
	protected boolean higherIsBetter() {
		return false;
	}

	@Override
	protected int[] fieldSize(int maxW, int maxH) {
		cell = Math.max(12, Math.min(18, Math.min((maxW - 120) / N, maxH / N)));
		return new int[]{N * cell + 110, N * cell};
	}

	@Override
	protected void reset() {
		boolean german = net.minecraft.client.Minecraft.getInstance().options.languageCode.startsWith("de");
		List<String> pool = new ArrayList<>(new java.util.LinkedHashSet<>(List.of(german ? DE : EN)));
		pool.removeIf(w -> w.length() > N);
		Collections.shuffle(pool, random);
		while (true) {
			for (char[] row : grid) {
				java.util.Arrays.fill(row, '\0');
			}
			words.clear();
			for (String w : pool) {
				if (words.size() >= WORDS) {
					break;
				}
				place(w);
			}
			if (words.size() >= WORDS) {
				break;
			}
		}
		String letters = "ABCDEFGHIJKLMNOPRSTUWZ";
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				if (grid[y][x] == '\0') {
					grid[y][x] = letters.charAt(random.nextInt(letters.length()));
				}
			}
		}
		found.clear();
		selX = selY = -1;
		startAt = 0;
		seconds = 0;
	}

	private void place(String w) {
		for (int tries = 0; tries < 200; tries++) {
			int[] d = DIRS[random.nextInt(DIRS.length)];
			boolean back = random.nextInt(4) == 0; // ab und zu rückwärts
			int dx = back ? -d[0] : d[0], dy = back ? -d[1] : d[1];
			int x = random.nextInt(N), y = random.nextInt(N);
			int ex = x + dx * (w.length() - 1), ey = y + dy * (w.length() - 1);
			if (ex < 0 || ey < 0 || ex >= N || ey >= N) {
				continue;
			}
			boolean ok = true;
			for (int i = 0; i < w.length(); i++) {
				char c = grid[y + dy * i][x + dx * i];
				if (c != '\0' && c != w.charAt(i)) {
					ok = false;
					break;
				}
			}
			if (!ok) {
				continue;
			}
			for (int i = 0; i < w.length(); i++) {
				grid[y + dy * i][x + dx * i] = w.charAt(i);
			}
			words.add(new Placed(w, x, y, dx, dy));
			return;
		}
	}

	@Override
	protected void step() {
		if (startAt > 0) {
			seconds = (int) ((System.currentTimeMillis() - startAt) / 1000);
		}
	}

	@Override
	protected String scoreText() {
		int best = best(game);
		return Component.translatable("loothub.lh.mg.word.score", found.size(), words.size(), seconds,
				best == 0 ? "–" : Fmt.whole(best)).getString();
	}

	private int[] cellAt(double mx, double my) {
		int x = (int) Math.floor((mx - fx) / cell), y = (int) Math.floor((my - fy) / cell);
		return x >= 0 && y >= 0 && x < N && y < N && mx >= fx && my >= fy ? new int[]{x, y} : null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		int[] c = over ? null : cellAt(e.x(), e.y());
		if (c == null) {
			return super.mouseClicked(e, doubleClick);
		}
		begin();
		if (startAt == 0) {
			startAt = System.currentTimeMillis();
		}
		if (selX < 0) {
			selX = c[0];
			selY = c[1];
		} else {
			tryWord(selX, selY, c[0], c[1]);
			selX = selY = -1;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		// Ziehen: beim Loslassen auf einem anderen Feld gilt das als Auswahl
		int[] c = over ? null : cellAt(e.x(), e.y());
		if (c != null && selX >= 0 && (c[0] != selX || c[1] != selY)) {
			tryWord(selX, selY, c[0], c[1]);
			selX = selY = -1;
			return true;
		}
		return super.mouseReleased(e);
	}

	private void tryWord(int x0, int y0, int x1, int y1) {
		for (Placed p : words) {
			if (found.contains(p)) {
				continue;
			}
			int ex = p.x + p.dx * (p.word.length() - 1), ey = p.y + p.dy * (p.word.length() - 1);
			if ((p.x == x0 && p.y == y0 && ex == x1 && ey == y1) || (p.x == x1 && p.y == y1 && ex == x0 && ey == y0)) {
				found.add(p);
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.4F, 1.2F + found.size() * 0.05F);
				}
				if (found.size() == words.size()) {
					seconds = Math.max(1, (int) ((System.currentTimeMillis() - startAt) / 1000));
					score = seconds;
					gameOver();
				}
				return;
			}
		}
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.2F, 0.6F);
		}
	}

	@Override
	protected void drawField(GuiGraphics g, float partial) {
		// Pergament (Ludo)
		for (int x = 0; x < fw; x += 128) {
			for (int y = 0; y < fh; y += 128) {
				g.blit(RenderPipelines.GUI_TEXTURED, tex("mg_parchment"), fx + x, fy + y, 0f, 0f, 128, 128, 128, 128, 128, 128);
			}
		}
		// gefundene Wörter markieren
		int[] colors = {0x6055E06A, 0x606BD5FF, 0x60F2C14E, 0x60FF8A65, 0x60B48CC8};
		for (int k = 0; k < found.size(); k++) {
			Placed p = found.get(k);
			for (int i = 0; i < p.word.length(); i++) {
				int x = fx + (p.x + p.dx * i) * cell, y = fy + (p.y + p.dy * i) * cell;
				g.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, colors[k % colors.length]);
			}
		}
		if (selX >= 0) {
			int x = fx + selX * cell, y = fy + selY * cell;
			g.fill(x, y, x + cell, y + cell, 0x80E23B3B);
		}
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				String ch = String.valueOf(grid[y][x]);
				g.drawString(font, ch, fx + x * cell + (cell - font.width(ch)) / 2 + 1, fy + y * cell + (cell - 8) / 2 + 1, 0xFF3A2414, false);
			}
		}
		// Wortliste rechts
		int lx = fx + N * cell + 10, ly = fy + 6;
		g.fill(lx - 6, fy, lx - 5, fy + fh, 0x603A2414);
		for (Placed p : words) {
			boolean done = found.contains(p);
			g.drawString(font, p.word, lx, ly, done ? 0xFF8A7A6A : 0xFF3A2414, false);
			if (done) {
				g.fill(lx, ly + 4, lx + font.width(p.word), ly + 5, 0xFF8A7A6A);
			}
			ly += 12;
		}
	}
}
