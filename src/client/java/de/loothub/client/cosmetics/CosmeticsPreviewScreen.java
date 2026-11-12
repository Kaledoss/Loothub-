package de.loothub.client.cosmetics;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;

/**
 * Vorschau auf dem Drehteller: der eigene Spieler mit allen Cosmetics, Mini-Mes und Haustieren, langsam drehend.
 * Mit der Maus ziehen dreht ihn von Hand. Die Begleiter stehen dabei fest an ihren Plätzen (kein Herumlaufen).
 */
public class CosmeticsPreviewScreen extends Screen {
	private static final Set<Object> MARKED = Collections.newSetFromMap(new WeakHashMap<>());
	private static boolean rendering;

	private final Screen parent;
	private float angle = 200.0F;
	private boolean dragging;
	private long lastFrame = System.currentTimeMillis();
	private long lastDrag;

	public CosmeticsPreviewScreen(Screen parent) {
		super(Component.translatable("loothub.cosmetics.preview"));
		this.parent = parent;
	}

	/** Wird gerade ein Vorschau-Zustand vorbereitet? */
	public static boolean rendering() {
		return rendering;
	}

	public static void mark(Object state) {
		MARKED.add(state);
	}

	/** Gehört dieser Zustand zur Vorschau (Begleiter fest am Platz)? */
	public static boolean is(Object state) {
		return state != null && MARKED.contains(state);
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
			angle = (angle + dt * 25.0F) % 360.0F; // dreht sich langsam von selbst
		}
		g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
		g.drawCenteredString(font, Component.translatable("loothub.cosmetics.preview.hint"), width / 2, 22, 0xFF9A9A9A);
		if (minecraft.player == null) {
			return;
		}
		int x0 = 20, x1 = width - 20, y0 = 36, y1 = height - 36;
		// Platz für Mini-Mes und Haustiere rundherum: etwa 4,5 Blöcke breit, 3 hoch
		float size = Math.min((x1 - x0) / 4.6F, (y1 - y0) / 3.0F);
		EntityRenderState st;
		rendering = true;
		try {
			st = minecraft.getEntityRenderDispatcher().getRenderer(minecraft.player).createRenderState(minecraft.player, 1.0F);
		} finally {
			rendering = false;
		}
		st.lightCoords = 0xF000F0;
		st.shadowPieces.clear();
		st.outlineColor = 0;
		if (st instanceof LivingEntityRenderState l) {
			l.bodyRot = 180.0F + angle;
			l.yRot = 0.0F;
			l.xRot = 0.0F;
			l.boundingBoxWidth /= l.scale;
			l.boundingBoxHeight /= l.scale;
			l.scale = 1.0F;
		}
		Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(-12.0));
		Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(tilt);
		Vector3f translation = new Vector3f(0.0F, st.boundingBoxHeight / 2.0F + 0.05F, 0.0F);
		g.submitEntityRenderState(st, size, translation, rotation, tilt, x0, y0, x1, y1);
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
	public boolean mouseReleased(MouseButtonEvent e) {
		dragging = false;
		return super.mouseReleased(e);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
