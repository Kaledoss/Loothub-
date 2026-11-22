package de.loothub.client.progress;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.sounds.SoundEvents;

/**
 * Schoko-Snake: Die Schlange sammelt Schokolade statt Äpfel. Ab und zu taucht kurz eine goldene Schoki auf (5 Punkte).
 * Steuerung mit Pfeiltasten oder WASD.
 */
final class SnakeScreen extends ArcadeScreen {
	private static final int COLS = 24, ROWS = 16;
	/** Goldene Schoki: so viele Schritte sichtbar, so viele Punkte. */
	private static final int GOLD_STEPS = 40, GOLD_POINTS = 5;

	private final Random random = new Random();
	private final Deque<int[]> snake = new ArrayDeque<>();
	private final Deque<int[]> turns = new ArrayDeque<>();
	private int dx = 1, dy = 0;
	private int foodX, foodY;
	private int goldX = -1, goldY = -1, goldLeft;
	/** Noch zu wachsende Glieder. */
	private int grow;
	private int stepIn;
	private int cell;
	private int flash;

	SnakeScreen(Screen parent) {
		super(parent, "snake");
		reset();
	}

	@Override
	protected int[] fieldSize(int maxW, int maxH) {
		cell = Math.max(6, Math.min(maxW / COLS, maxH / ROWS));
		return new int[]{COLS * cell, ROWS * cell};
	}

	@Override
	protected void reset() {
		snake.clear();
		turns.clear();
		for (int i = 0; i < 4; i++) {
			snake.addLast(new int[]{COLS / 2 - i, ROWS / 2});
		}
		dx = 1;
		dy = 0;
		stepIn = 0;
		grow = 0;
		goldX = -1;
		goldLeft = 0;
		int[] f = freeCell();
		foodX = f[0];
		foodY = f[1];
	}

	private int[] freeCell() {
		for (int tries = 0; tries < 2000; tries++) {
			int x = random.nextInt(COLS), y = random.nextInt(ROWS);
			if (!occupied(x, y, false) && !(x == foodX && y == foodY) && !(x == goldX && y == goldY)) {
				return new int[]{x, y};
			}
		}
		return new int[]{-1, -1};
	}

	private int interval() {
		return Math.max(2, 5 - score / 10);
	}

	@Override
	public void tick() {
		if (flash > 0) {
			flash--;
		}
		super.tick();
	}

	@Override
	protected void step() {
		if (--stepIn > 0) {
			return;
		}
		stepIn = interval();
		if (!turns.isEmpty()) {
			int[] t = turns.poll();
			dx = t[0];
			dy = t[1];
		}
		int[] head = snake.peekFirst();
		int nx = head[0] + dx, ny = head[1] + dy;
		boolean eat = nx == foodX && ny == foodY;
		boolean gold = nx == goldX && ny == goldY;
		if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS || occupied(nx, ny, grow == 0 && !eat && !gold)) {
			gameOver();
			return;
		}
		snake.addFirst(new int[]{nx, ny});
		if (eat) {
			score++;
			grow++;
			flash = 4;
			sound(1.2F);
			int[] f = freeCell();
			if (f[0] < 0) {
				gameOver();
				return;
			}
			foodX = f[0];
			foodY = f[1];
			// ab und zu eine goldene Schoki
			if (goldLeft <= 0 && random.nextInt(6) == 0) {
				int[] gc = freeCell();
				goldX = gc[0];
				goldY = gc[1];
				goldLeft = GOLD_STEPS;
			}
		} else if (gold) {
			score += GOLD_POINTS;
			grow++;
			flash = 8;
			sound(1.8F);
			goldX = -1;
			goldLeft = 0;
		}
		if (grow > 0) {
			grow--;
		} else {
			snake.removeLast();
		}
		if (goldLeft > 0 && --goldLeft == 0) {
			goldX = -1;
		}
	}

	private void sound(float pitch) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(SoundEvents.GENERIC_EAT.value(), 0.5F, pitch + random.nextFloat() * 0.15F);
		}
	}

	/** Liegt dort die Schlange? (Das Schwanzende rückt gleich weiter, wenn sie nicht wächst.) */
	private boolean occupied(int x, int y, boolean tailMoves) {
		int i = 0, last = snake.size() - 1;
		for (int[] s : snake) {
			if (s[0] == x && s[1] == y && !(tailMoves && i == last)) {
				return true;
			}
			i++;
		}
		return false;
	}

	@Override
	protected boolean control(int k, boolean down) {
		if (!down) {
			return false;
		}
		int[] d = switch (k) {
			case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> new int[]{0, -1};
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> new int[]{0, 1};
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> new int[]{-1, 0};
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> new int[]{1, 0};
			default -> null;
		};
		if (d == null) {
			return false;
		}
		int[] last = turns.isEmpty() ? new int[]{dx, dy} : turns.peekLast();
		if (turns.size() < 3 && !(d[0] == -last[0] && d[1] == -last[1]) && !(d[0] == last[0] && d[1] == last[1])) {
			turns.add(d);
		}
		return true;
	}

	@Override
	protected void drawField(GuiGraphics g, float partial) {
		for (int x = 0; x < COLS; x++) {
			for (int y = 0; y < ROWS; y++) {
				g.fill(fx + x * cell, fy + y * cell, fx + (x + 1) * cell, fy + (y + 1) * cell, (x + y) % 2 == 0 ? 0xFF1E1412 : 0xFF241816);
			}
		}
		if (flash > 0) {
			g.fill(fx, fy, fx + fw, fy + fh, 0x20F2C14E);
		}
		g.blit(RenderPipelines.GUI_TEXTURED, tex("choco"), fx + foodX * cell, fy + foodY * cell, 0f, 0f, cell, cell, 64, 64, 64, 64);
		// goldene Schoki blinkt kurz vor dem Verschwinden
		if (goldX >= 0 && (goldLeft > 10 || goldLeft % 2 == 0)) {
			int pad = (int) (Math.sin(System.currentTimeMillis() / 120.0) * 1.5);
			g.blit(RenderPipelines.GUI_TEXTURED, tex("choco_gold"), fx + goldX * cell - pad, fy + goldY * cell - pad, 0f, 0f,
					cell + 2 * pad, cell + 2 * pad, 64, 64, 64, 64);
		}
		int i = 0, n = snake.size();
		for (int[] s : snake) {
			int px = fx + s[0] * cell, py = fy + s[1] * cell;
			float t = n <= 1 ? 0 : i / (float) (n - 1);
			int col = i == 0 ? 0xFFFF6B6B : lerp(0xFFE23B3B, 0xFF7A1E24, t);
			g.fill(px + 1, py + 1, px + cell - 1, py + cell - 1, col);
			if (i == 0) {
				int q = Math.max(1, cell / 5);
				int e1x, e1y, e2x, e2y;
				if (dx != 0) {
					e1x = e2x = dx > 0 ? px + cell - 2 * q - 1 : px + q + 1;
					e1y = py + q + 1;
					e2y = py + cell - 2 * q - 1;
				} else {
					e1y = e2y = dy > 0 ? py + cell - 2 * q - 1 : py + q + 1;
					e1x = px + q + 1;
					e2x = px + cell - 2 * q - 1;
				}
				g.fill(e1x, e1y, e1x + q, e1y + q, 0xFF000000);
				g.fill(e2x, e2y, e2x + q, e2y + q, 0xFF000000);
			}
			i++;
		}
	}

	static int lerp(int a, int b, float t) {
		int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int gg = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return 0xFF000000 | (r << 16) | (gg << 8) | bl;
	}
}
