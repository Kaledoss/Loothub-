package de.loothub.client.progress;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Schoko-Snake: Die Schlange sammelt Schokolade statt Äpfel. Steuerung mit Pfeiltasten oder WASD, Leertaste startet
 * neu, P pausiert. Bestwert wird lokal gespeichert.
 */
final class SnakeScreen extends Screen {
	private static final Identifier CHOCO = Identifier.fromNamespaceAndPath("loothub", "textures/gui/choco.png");
	private static final int COLS = 24, ROWS = 16;

	private final Screen parent;
	private final Random random = new Random();
	private final Deque<int[]> snake = new ArrayDeque<>();
	private final Deque<int[]> turns = new ArrayDeque<>();
	private int dx = 1, dy = 0;
	private int foodX, foodY;
	private int score;
	private int stepIn;
	private boolean over, paused, started;
	private int cell, ox, oy;
	/** Kurzes Aufleuchten beim Einsammeln. */
	private int flash;

	SnakeScreen(Screen parent) {
		super(Component.translatable("loothub.lh.snake.title"));
		this.parent = parent;
		reset();
	}

	private void reset() {
		snake.clear();
		turns.clear();
		for (int i = 0; i < 4; i++) {
			snake.addLast(new int[]{COLS / 2 - i, ROWS / 2});
		}
		dx = 1;
		dy = 0;
		score = 0;
		over = false;
		paused = false;
		started = false;
		stepIn = 0;
		placeFood();
	}

	private void placeFood() {
		while (true) {
			int x = random.nextInt(COLS), y = random.nextInt(ROWS);
			boolean free = true;
			for (int[] s : snake) {
				if (s[0] == x && s[1] == y) {
					free = false;
					break;
				}
			}
			if (free) {
				foodX = x;
				foodY = y;
				return;
			}
		}
	}

	@Override
	protected void init() {
		cell = Math.max(6, Math.min((width - 40) / COLS, (height - 70) / ROWS));
		ox = (width - COLS * cell) / 2;
		oy = (height - ROWS * cell) / 2 + 8;
	}

	/** Schritte je Sekunde steigen mit der Länge (Spiel-Ticks zwischen zwei Schritten). */
	private int interval() {
		return Math.max(2, 5 - score / 8);
	}

	@Override
	public void tick() {
		if (flash > 0) {
			flash--;
		}
		if (over || paused || !started) {
			return;
		}
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
		if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS || hits(nx, ny, !eat)) {
			gameOver();
			return;
		}
		snake.addFirst(new int[]{nx, ny});
		if (eat) {
			score++;
			flash = 4;
			if (minecraft != null && minecraft.player != null) {
				minecraft.player.playSound(SoundEvents.GENERIC_EAT.value(), 0.5F, 1.2F + random.nextFloat() * 0.2F);
			}
			if (snake.size() >= COLS * ROWS) {
				gameOver();
				return;
			}
			placeFood();
		} else {
			snake.removeLast();
		}
	}

	/** Läuft die Schlange in sich selbst? (Das Schwanzende rückt gleich weiter, außer sie frisst.) */
	private boolean hits(int x, int y, boolean tailMoves) {
		int i = 0, last = snake.size() - 1;
		for (int[] s : snake) {
			if (s[0] == x && s[1] == y && !(tailMoves && i == last)) {
				return true;
			}
			i++;
		}
		return false;
	}

	private void gameOver() {
		over = true;
		WidgetConfig c = WidgetConfig.get();
		if (score > c.snakeBest) {
			c.snakeBest = score;
			c.save();
		}
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 0.6F);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (k == GLFW.GLFW_KEY_SPACE || k == GLFW.GLFW_KEY_ENTER) {
			if (over) {
				reset();
			}
			started = true;
			paused = false;
			return true;
		}
		if (k == GLFW.GLFW_KEY_P && started && !over) {
			paused = !paused;
			return true;
		}
		int[] d = switch (k) {
			case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> new int[]{0, -1};
			case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> new int[]{0, 1};
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> new int[]{-1, 0};
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> new int[]{1, 0};
			default -> null;
		};
		if (d != null) {
			if (over) {
				return true;
			}
			started = true;
			// nicht direkt umdrehen; mehrere schnelle Tasten werden nacheinander ausgeführt
			int[] last = turns.isEmpty() ? new int[]{dx, dy} : turns.peekLast();
			if (turns.size() < 3 && !(d[0] == -last[0] && d[1] == -last[1]) && !(d[0] == last[0] && d[1] == last[1])) {
				turns.add(d);
			}
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		int w = COLS * cell, h = ROWS * cell;
		// Titel und Punkte
		g.drawCenteredString(font, Component.translatable("loothub.lh.snake.title"), width / 2, oy - 26, 0xFFF2C14E);
		String info = Component.translatable("loothub.lh.snake.score", score, Fmt.whole(WidgetConfig.get().snakeBest)).getString();
		g.drawCenteredString(font, info, width / 2, oy - 14, 0xFFFFFFFF);
		// Spielfeld: Schachbrett in dunklem Braun
		g.fill(ox - 2, oy - 2, ox + w + 2, oy + h + 2, flash > 0 ? 0xFFF2C14E : LoothubScreen.RED);
		for (int x = 0; x < COLS; x++) {
			for (int y = 0; y < ROWS; y++) {
				g.fill(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell,
						(x + y) % 2 == 0 ? 0xFF1E1412 : 0xFF241816);
			}
		}
		// Schokolade
		g.blit(RenderPipelines.GUI_TEXTURED, CHOCO, ox + foodX * cell, oy + foodY * cell, 0f, 0f, cell, cell, 64, 64, 64, 64);
		// Schlange: Kopf heller, Körper verläuft von Rot nach Dunkelrot
		int i = 0, n = snake.size();
		for (int[] s : snake) {
			int px = ox + s[0] * cell, py = oy + s[1] * cell;
			float t = n <= 1 ? 0 : i / (float) (n - 1);
			int col = i == 0 ? 0xFFFF6B6B : lerp(0xFFE23B3B, 0xFF7A1E24, t);
			g.fill(px + 1, py + 1, px + cell - 1, py + cell - 1, col);
			if (i == 0) {
				// Augen in Laufrichtung
				int e1x, e1y, e2x, e2y, q = Math.max(1, cell / 5);
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
		// Hinweise
		String hint = null;
		if (over) {
			g.fill(ox, oy + h / 2 - 16, ox + w, oy + h / 2 + 18, 0xC0000000);
			boolean best = score > 0 && score >= WidgetConfig.get().snakeBest;
			g.drawCenteredString(font, Component.translatable(best ? "loothub.lh.snake.best" : "loothub.lh.snake.over", score),
					width / 2, oy + h / 2 - 10, best ? 0xFFF2C14E : 0xFFFF6B6B);
			hint = Component.translatable("loothub.lh.snake.again").getString();
			g.drawCenteredString(font, hint, width / 2, oy + h / 2 + 4, 0xFFB9A9AB);
		} else if (!started) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.snake.start"), width / 2, oy + h / 2 + cell, 0xFFFFFFFF);
		} else if (paused) {
			g.drawCenteredString(font, Component.translatable("loothub.lh.snake.paused"), width / 2, oy + h / 2 + cell, 0xFFFFFFFF);
		}
		g.drawCenteredString(font, Component.translatable("loothub.lh.snake.keys"), width / 2, oy + h + 8, 0xFF7A6A6C);
	}

	private static int lerp(int a, int b, float t) {
		int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int gg = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return 0xFF000000 | (r << 16) | (gg << 8) | bl;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
