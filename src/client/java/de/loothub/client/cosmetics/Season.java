package de.loothub.client.cosmetics;

import java.time.LocalDateTime;
import java.time.Month;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import de.loothub.Loothub;
import de.loothub.client.jobs.WidgetConfig;

/**
 * Jahreszeiten für die Mini-Mes: vom 1. Dezember bis zum 6. Januar tragen sie Zipfelmützen (Würfel-Stil wie
 * Minecraft), an Silvester (31.12. ab 18 Uhr bis 1.1. um 3 Uhr) steigen über Spielern mit Mini-Mes kleine
 * Feuerwerks-Funken auf. Abschaltbar im Cosmetics-Menü. Eigenes Design.
 */
public final class Season {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath(Loothub.MOD_ID, "textures/entity/white.png");
	private static final float PX = 1.0F / 16.0F;
	private static final int RED = 0xFFC8202A, RED_DARK = 0xFFA5161F, FUR = 0xFFF4F4F4;
	private static long checkedAt;
	private static boolean winter, newYear;

	private Season() {
	}

	private static void refresh() {
		long now = System.currentTimeMillis();
		if (now - checkedAt < 30_000L) {
			return;
		}
		checkedAt = now;
		LocalDateTime d = LocalDateTime.now();
		winter = d.getMonth() == Month.DECEMBER || (d.getMonth() == Month.JANUARY && d.getDayOfMonth() <= 6);
		newYear = (d.getMonth() == Month.DECEMBER && d.getDayOfMonth() == 31 && d.getHour() >= 18)
				|| (d.getMonth() == Month.JANUARY && d.getDayOfMonth() == 1 && d.getHour() < 3);
	}

	/** Jahreszeiten eingeschaltet (unabhängig vom Datum). */
	public static boolean enabled() {
		return on();
	}

	private static boolean on() {
		WidgetConfig c = WidgetConfig.get();
		return c.cosmetics && c.seasonalCosmetics;
	}

	/** Zipfelmützen-Zeit? */
	public static boolean hats() {
		refresh();
		return winter && on();
	}

	public static boolean fireworks() {
		refresh();
		return newYear && on();
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(Season::tick);
	}

	// ------------------------------------------------------------------ Mütze

	/** Mütze auf den Kopf setzen; der PoseStack steht bereits im Kopf-Raum (Kopfwürfel −4..4, Höhe −8..0). */
	public static void submitHat(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(WHITE), (pose, vc) -> {
			// Fellrand
			box(pose, vc, light, -4.6F, -8.9F, -4.6F, 4.6F, -7.2F, 4.6F, FUR);
			// Mütze: nach oben schmaler, kippt nach hinten
			box(pose, vc, light, -3.9F, -10.4F, -3.7F, 3.9F, -8.9F, 4.0F, RED);
			box(pose, vc, light, -3.0F, -11.8F, -2.4F, 3.0F, -10.4F, 3.7F, RED_DARK);
			box(pose, vc, light, -2.1F, -12.9F, -0.8F, 2.1F, -11.8F, 3.5F, RED);
			box(pose, vc, light, -1.3F, -13.4F, 1.4F, 1.3F, -12.4F, 4.6F, RED_DARK);
			// Bommel hängt hinten herunter
			box(pose, vc, light, -1.4F, -13.3F, 4.2F, 1.4F, -10.6F, 6.8F, FUR);
		});
	}

	private static void box(PoseStack.Pose pose, VertexConsumer vc, int light, float x0, float y0, float z0, float x1,
			float y1, float z1, int color) {
		// sechs Flächen, je vier Ecken (gegen den Uhrzeigersinn von außen gesehen)
		quad(pose, vc, light, color, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1); // oben (y nach unten)
		quad(pose, vc, light, color, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0); // unten
		quad(pose, vc, light, color, 0, 0, -1, x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0); // vorn
		quad(pose, vc, light, color, 0, 0, 1, x1, y1, z1, x0, y1, z1, x0, y0, z1, x1, y0, z1); // hinten
		quad(pose, vc, light, color, -1, 0, 0, x0, y1, z1, x0, y1, z0, x0, y0, z0, x0, y0, z1); // links
		quad(pose, vc, light, color, 1, 0, 0, x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0); // rechts
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer vc, int light, int color, float nx, float ny, float nz,
			float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy,
			float dz) {
		v(pose, vc, light, color, nx, ny, nz, ax, ay, az, 0, 0);
		v(pose, vc, light, color, nx, ny, nz, bx, by, bz, 1, 0);
		v(pose, vc, light, color, nx, ny, nz, cx, cy, cz, 1, 1);
		v(pose, vc, light, color, nx, ny, nz, dx, dy, dz, 0, 1);
	}

	private static void v(PoseStack.Pose pose, VertexConsumer vc, int light, int color, float nx, float ny, float nz, float x,
			float y, float z, float u, float w) {
		vc.addVertex(pose, x * PX, y * PX, z * PX).setColor(color).setUv(u, w).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light).setNormal(pose, nx, ny, nz);
	}

	// ------------------------------------------------------------------ Silvester

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.isPaused() || mc.level.getGameTime() % 25 != 0 || !fireworks()) {
			return;
		}
		RandomSource r = mc.level.getRandom();
		for (Player p : mc.level.players()) {
			if (p.isInvisible() || p.isSpectator() || p.distanceToSqr(mc.player) > 32 * 32
					|| !Cosmetics.playerHas(p, Cosmetics.MINI_ME) || r.nextInt(3) != 0) {
				continue;
			}
			// kleiner Funkenregen schräg über dem Spieler
			double x = p.getX() + (r.nextDouble() - 0.5) * 1.6, y = p.getY() + 2.6 + r.nextDouble() * 0.6;
			double z = p.getZ() + (r.nextDouble() - 0.5) * 1.6;
			for (int i = 0; i < 14; i++) {
				double a = r.nextDouble() * Math.PI * 2, e = (r.nextDouble() - 0.3) * Math.PI / 2;
				double sp = 0.06 + r.nextDouble() * 0.05;
				mc.level.addParticle(ParticleTypes.FIREWORK, x, y, z, Math.cos(a) * Math.cos(e) * sp, Math.sin(e) * sp,
						Math.sin(a) * Math.cos(e) * sp);
			}
		}
	}
}
