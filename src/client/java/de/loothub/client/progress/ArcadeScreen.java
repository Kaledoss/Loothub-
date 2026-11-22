package de.loothub.client.progress;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import de.loothub.client.Fmt;
import de.loothub.client.LoothubScreen;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Grundgerüst der kleinen Spiele: Spielfeld in der Bildschirmmitte, Punkte und Rekord oben, Start/Pause/Vorbei-Hinweise,
 * Rekord lokal speichern und – bei Spielen mit Rangliste – den Punktestand an den Loothub-Server schicken.
 */
abstract class ArcadeScreen extends Screen {
	protected final Screen parent;
	protected final String game;
	protected int score;
	protected boolean started, paused, over;
	/** Spielfeld (Pixel). */
	protected int fx, fy, fw, fh;
	private long startedAt;
	private long playedMs;
	private String submitMsg = "";

	ArcadeScreen(Screen parent, String game) {
		super(Component.translatable("loothub.lh.mg." + game));
		this.parent = parent;
		this.game = game;
	}

	static Identifier tex(String name) {
		return Identifier.fromNamespaceAndPath("loothub", "textures/gui/" + name + ".png");
	}

	/** Rekord dieses Spiels (lokal). */
	static int best(String game) {
		WidgetConfig c = WidgetConfig.get();
		int b = c.mgBest.getOrDefault(game, 0);
		return game.equals("snake") ? Math.max(b, c.snakeBest) : b;
	}

	/** Größe des Spielfelds (Breite, Höhe) bei gegebenem Platz. */
	protected abstract int[] fieldSize(int maxW, int maxH);

	protected abstract void reset();

	protected abstract void step();

	protected abstract void drawField(GuiGraphics g, float partial);

	/** Steuerung; true = verbraucht. */
	protected boolean control(int key, boolean down) {
		return false;
	}

	/** Höhere Werte sind besser? (Memory: weniger Züge sind besser.) */
	protected boolean higherIsBetter() {
		return true;
	}

	/** Spiel mit Online-Rangliste? */
	protected boolean ranked() {
		return true;
	}

	protected String scoreText() {
		return Component.translatable("loothub.lh.mg.score", Fmt.whole(score), Fmt.whole(best(game))).getString();
	}

	@Override
	protected void init() {
		int[] s = fieldSize(width - 40, height - 70);
		fw = s[0];
		fh = s[1];
		fx = (width - fw) / 2;
		fy = (height - fh) / 2 + 8;
	}

	protected void restart() {
		score = 0;
		over = false;
		paused = false;
		started = false;
		submitMsg = "";
		playedMs = 0;
		reset();
	}

	protected void begin() {
		if (!started) {
			started = true;
			startedAt = System.currentTimeMillis();
		}
	}

	@Override
	public void tick() {
		if (started && !paused && !over) {
			step();
		}
	}

	protected void gameOver() {
		if (over) {
			return;
		}
		over = true;
		playedMs = System.currentTimeMillis() - startedAt;
		WidgetConfig c = WidgetConfig.get();
		int old = best(game);
		boolean better = higherIsBetter() ? score > old : score > 0 && (old == 0 || score < old);
		if (better) {
			c.mgBest.put(game, score);
			c.save();
		}
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(better ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F,
					better ? 1.2F : 0.6F);
		}
		if (ranked() && score > 0 && WidgetConfig.get().progress) {
			submitMsg = Component.translatable("loothub.lh.mg.sending").getString();
			Progress.mgSubmit(game, score, playedMs, err -> submitMsg = err == null
					? Component.translatable("loothub.lh.mg.sent").getString()
					: Component.translatable("loothub.lh.error." + err).getString());
		}
	}

	protected boolean newBest() {
		return score > 0 && score == best(game);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (k == GLFW.GLFW_KEY_ESCAPE) {
			return super.keyPressed(e);
		}
		if (over) {
			if (k == GLFW.GLFW_KEY_SPACE || k == GLFW.GLFW_KEY_ENTER) {
				restart();
			}
			return true;
		}
		if (k == GLFW.GLFW_KEY_P && started) {
			paused = !paused;
			return true;
		}
		if (control(k, true)) {
			if (!paused) {
				begin();
			}
			return true;
		}
		if (k == GLFW.GLFW_KEY_SPACE || k == GLFW.GLFW_KEY_ENTER) {
			begin();
			paused = false;
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean keyReleased(KeyEvent e) {
		return control(e.key(), false) || super.keyReleased(e);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		g.drawCenteredString(font, getTitle(), width / 2, fy - 26, 0xFFF2C14E);
		g.drawCenteredString(font, scoreText(), width / 2, fy - 14, 0xFFFFFFFF);
		g.fill(fx - 2, fy - 2, fx + fw + 2, fy + fh + 2, LoothubScreen.RED);
		g.enableScissor(fx, fy, fx + fw, fy + fh);
		drawField(g, delta);
		g.disableScissor();
		int cy = fy + fh / 2;
		if (over) {
			g.fill(fx, cy - 18, fx + fw, cy + (submitMsg.isEmpty() ? 18 : 28), 0xC0000000);
			boolean best = newBest();
			g.drawCenteredString(font, Component.translatable((best ? "loothub.lh.mg.best." : "loothub.lh.mg.over.") + game, Fmt.whole(score)),
					width / 2, cy - 12, best ? 0xFFF2C14E : 0xFFFF6B6B);
			g.drawCenteredString(font, Component.translatable("loothub.lh.mg.again"), width / 2, cy + 2, 0xFFB9A9AB);
			if (!submitMsg.isEmpty()) {
				g.drawCenteredString(font, submitMsg, width / 2, cy + 14, 0xFF7A6A6C);
			}
		} else if (!started) {
			g.fill(fx, cy - 8, fx + fw, cy + 8, 0x90000000);
			g.drawCenteredString(font, Component.translatable("loothub.lh.mg.start." + game), width / 2, cy - 4, 0xFFFFFFFF);
		} else if (paused) {
			g.fill(fx, cy - 8, fx + fw, cy + 8, 0x90000000);
			g.drawCenteredString(font, Component.translatable("loothub.lh.mg.paused"), width / 2, cy - 4, 0xFFFFFFFF);
		}
		g.drawCenteredString(font, Component.translatable("loothub.lh.mg.keys." + game), width / 2, fy + fh + 8, 0xFF7A6A6C);
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
