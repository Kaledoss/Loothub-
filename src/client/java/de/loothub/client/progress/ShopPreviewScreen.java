package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;

import de.loothub.client.Fmt;

/**
 * Vorschau eines Shop-Artikels: der eigene Spieler auf dem Drehteller, darüber Titel bzw. Abzeichen vor dem Namen,
 * bei Fußspuren steigen die Spuren an den Füßen auf. Mit der Maus ziehen dreht den Spieler.
 */
public class ShopPreviewScreen extends Screen {
	private record Spark(float x, float y, float vx, float vy, long born, int color, String glyph) {
	}

	private final Screen parent;
	private final String id;
	private final String kind;
	private final int cost;
	private final List<Spark> sparks = new ArrayList<>();
	private final Random random = new Random();
	private float angle = 200.0F;
	/** Zoom per Mausrad (1 = ganzer Spieler). */
	private float zoom = 1.0F;
	private boolean dragging;
	private long lastFrame = System.currentTimeMillis();
	private long lastDrag;
	private long lastSpark;

	public ShopPreviewScreen(Screen parent, String id, String kind, int cost) {
		super(Component.translatable("loothub.lh.preview.title"));
		this.parent = parent;
		this.id = id;
		this.kind = kind;
		this.cost = cost;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width / 2 - 50, height - 28, 100, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		super.render(g, mouseX, mouseY, delta);
		long now = System.currentTimeMillis();
		float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
		lastFrame = now;
		if (!dragging && now - lastDrag > 1500L) {
			angle = (angle + dt * 25.0F) % 360.0F;
		}
		Component name = Shop.name(id);
		Component price = Component.translatable("loothub.lh.shop.cost", Fmt.whole(cost));
		g.drawCenteredString(font, Component.empty().append(name).append(" · ").append(price), width / 2, 10, 0xFFF2C14E);
		g.drawCenteredString(font, Component.translatable("loothub.lh.preview.hint." + kind), width / 2, 22, 0xFF9A9A9A);
		if (minecraft.player == null) {
			return;
		}
		int x0 = 20, x1 = width - 20, y0 = 56, y1 = height - 36;
		float size = Math.min((x1 - x0) / 1.8F, (y1 - y0) / 2.4F) * zoom;
		EntityRenderState st = minecraft.getEntityRenderDispatcher().getRenderer(minecraft.player)
				.createRenderState(minecraft.player, 1.0F);
		st.lightCoords = 0xF000F0;
		st.shadowPieces.clear();
		st.outlineColor = 0;
		st.nameTag = null;
		if (st instanceof LivingEntityRenderState l) {
			l.bodyRot = 180.0F + angle;
			l.yRot = 0.0F;
			l.xRot = 0.0F;
			l.boundingBoxWidth /= l.scale;
			l.boundingBoxHeight /= l.scale;
			l.scale = 1.0F;
		}
		Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(-8.0));
		Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(tilt);
		Vector3f translation = new Vector3f(0.0F, st.boundingBoxHeight / 2.0F + 0.05F, 0.0F);
		g.submitEntityRenderState(st, size, translation, rotation, tilt, x0, y0, x1, y1);

		int cx = (x0 + x1) / 2;
		int cy = (y0 + y1) / 2;
		int headTop = (int) (cy - size * 0.98F);
		int feet = (int) (cy + size * 0.9F);

		// Namensschild wie im Spiel: Titel darüber, Abzeichen vor dem Namen
		Component playerName = Component.literal(minecraft.getUser().getName());
		Component badge = kind.equals("badge") ? Shop.badge(id) : null;
		Component nameLine = badge == null ? playerName : Component.empty().append(badge).append(" ").append(playerName);
		int nameY = headTop - 14;
		plate(g, nameLine, cx, nameY);
		if (kind.equals("title")) {
			plate(g, Shop.titleText(id), cx, nameY - 12);
		}

		// Fußspuren
		if (kind.equals("steps")) {
			String style = Shop.STEPS.get(id);
			if (now - lastSpark > 90) {
				lastSpark = now;
				sparks.add(spark(style, cx + (random.nextFloat() - 0.5F) * size * 0.5F, feet, now));
			}
			sparks.removeIf(s -> now - s.born() > 1400);
			for (Spark s : sparks) {
				float t = (now - s.born()) / 1000.0F;
				float a = 1.0F - t / 1.4F;
				int alpha = Math.max(0, Math.min(255, (int) (a * 255)));
				int color = (alpha << 24) | (s.color() & 0xFFFFFF);
				g.drawString(font, s.glyph(), (int) (s.x() + s.vx() * t), (int) (s.y() + s.vy() * t), color, false);
			}
		}
	}

	private void plate(GuiGraphics g, Component text, int cx, int y) {
		int w = font.width(text);
		g.fill(cx - w / 2 - 2, y - 1, cx + w / 2 + 2, y + 9, 0x60000000);
		g.drawString(font, text, cx - w / 2, y, 0xFFFFFFFF, false);
	}

	private Spark spark(String style, float x, float y, long now) {
		float vx = (random.nextFloat() - 0.5F) * 10.0F;
		return switch (style) {
			case "hearts" -> new Spark(x, y, vx, -22.0F, now, 0xFF5577, "♥");
			case "notes" -> new Spark(x, y, vx, -26.0F, now, 0xFF000000 | java.awt.Color.HSBtoRGB(random.nextFloat(), 0.7F, 1.0F), "♪");
			case "snow" -> new Spark(x, y - 30, vx, 14.0F, now, 0xFFFFFF, "❄");
			case "magic" -> new Spark(x, y, vx, -20.0F, now, 0xB66BFF, "✦");
			case "soul" -> new Spark(x, y, vx * 0.5F, -24.0F, now, 0x5FD8FF, "▲");
			default -> new Spark(x, y, vx, -16.0F, now, 0xFFF2A6, "✧");
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true;
		}
		dragging = true;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging) {
			angle = (angle + (float) dx * 1.2F) % 360.0F;
			lastDrag = System.currentTimeMillis();
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		zoom = Math.max(0.6F, Math.min(3.0F, zoom * (vertical > 0 ? 1.15F : 1.0F / 1.15F)));
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		dragging = false;
		return super.mouseReleased(e);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
