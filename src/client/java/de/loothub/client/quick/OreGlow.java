package de.loothub.client.quick;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Ressourcenpaket-Einstellung „Leuchtende Erze“: sichtbare Erze in der Nähe bekommen einen pulsierenden, leuchtenden
 * Rand in der Farbe des Erzes (Diamant türkis, Gold gelb, Redstone rot …). Nur Erze mit freier Sichtlinie und nur Flächen, die zur Luft zeigen, also
 * ohnehin sichtbar sind – mit Tiefentest, nichts scheint durch Wände (kein X-Ray). Nur Anzeige.
 */
public final class OreGlow {
	private record Ore(int x, int y, int z, int faces, int rgb) {
	}

	private static volatile List<Ore> ores = List.of();

	private OreGlow() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(OreGlow::tick);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(ctx -> render(ctx.matrices(), ctx.consumers()));
	}

	// Suche läuft verteilt über mehrere Ticks (ein paar Scheiben pro Tick) – auch bei großer Reichweite ohne Ruckler
	private static List<Ore> building = new ArrayList<>();
	private static BlockPos scanCenter;
	private static int scanX, scanR, scanRY;

	private static void tick(Minecraft mc) {
		WidgetConfig c = WidgetConfig.get();
		if (!c.glowingOres || mc.level == null || mc.player == null) {
			if (!ores.isEmpty()) {
				ores = List.of();
			}
			FADES.clear();
			scanCenter = null;
			return;
		}
		if (scanCenter == null) {
			scanR = Math.max(4, Math.min(32, c.oreGlowRange));
			scanRY = Math.min(scanR, 16); // in der Höhe etwas weniger weit
			scanCenter = mc.player.blockPosition();
			scanX = -scanR;
			building = new ArrayList<>();
		}
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		int budget = 15000; // Block-Abfragen pro Tick (ein Durchlauf bei 20 Blöcken ≈ 4 Ticks)
		while (scanX <= scanR && budget > 0) {
			int x = scanX++;
			for (int y = -scanRY; y <= scanRY; y++) {
				for (int z = -scanR; z <= scanR; z++) {
					budget--;
					p.set(scanCenter.getX() + x, scanCenter.getY() + y, scanCenter.getZ() + z);
					BlockState st = mc.level.getBlockState(p);
					int rgb = color(st);
					if (rgb < 0) {
						continue;
					}
					// nur Flächen, an denen Luft/Durchsichtiges grenzt – also wirklich sichtbare Seiten
					int faces = 0;
					for (Direction d : Direction.values()) {
						n.setWithOffset(p, d);
						BlockState ns = mc.level.getBlockState(n);
						if (ns.isAir() || (!ns.canOcclude() && !ns.getFluidState().isEmpty())) {
							faces |= 1 << d.ordinal();
						}
					}
					// nur echte, wirklich sichtbare Erze: Server mit Anti-X-Ray schicken versteckte Schein-Erze –
					// deshalb muss eine Sichtlinie vom Auge bis zum Erz frei sein
					if (faces != 0) {
						building.add(new Ore(p.getX(), p.getY(), p.getZ(), faces, rgb));
					}
				}
			}
		}
		if (scanX > scanR) {
			ores = building; // Durchlauf fertig → Kandidaten übernehmen, nächster beginnt
			scanCenter = null;
		}
		checkVisibility(mc);
	}

	/** Ein-/Ausblenden je Erz: Sichtbarkeit (Ziel 0/1) und aktuelle Deckkraft. */
	private static final class Fade {
		float alpha;
		boolean visible;
		Ore ore;
	}

	private static final java.util.Map<Long, Fade> FADES = new java.util.HashMap<>();
	private static int visCursor;
	private static long lastFrame;

	/** Jeden Tick einen Teil der Kandidaten auf freie Sicht prüfen (alle ~4 Ticks einmal rundum). */
	private static void checkVisibility(Minecraft mc) {
		List<Ore> list = ores;
		java.util.Set<Long> current = new java.util.HashSet<>();
		for (Ore o : list) {
			current.add(BlockPos.asLong(o.x(), o.y(), o.z()));
		}
		// verschwundene Kandidaten ausblenden lassen
		for (var e : FADES.entrySet()) {
			if (!current.contains(e.getKey())) {
				e.getValue().visible = false;
			}
		}
		if (list.isEmpty()) {
			return;
		}
		int per = Math.max(60, list.size() / 4 + 1);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int i = 0; i < per && i < list.size(); i++) {
			visCursor = (visCursor + 1) % list.size();
			Ore o = list.get(visCursor);
			p.set(o.x(), o.y(), o.z());
			boolean vis = color(mc.level.getBlockState(p)) >= 0 && visible(mc, p, o.faces());
			Fade f = FADES.computeIfAbsent(p.asLong(), k -> new Fade());
			f.ore = o;
			f.visible = vis;
		}
	}

	/** Freie Sichtlinie vom Auge zur Mitte oder zu einer offenen Seite des Erzes. */
	private static boolean visible(Minecraft mc, BlockPos pos, int faces) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		if (hits(mc, eye, center, pos)) {
			return true;
		}
		for (Direction d : Direction.values()) {
			if ((faces & (1 << d.ordinal())) != 0) {
				Vec3 face = center.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
				if (hits(mc, eye, face, pos)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hits(Minecraft mc, Vec3 from, Vec3 to, BlockPos pos) {
		var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to,
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
				mc.player));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	/** Farbe je Erz, oder −1 für kein Erz. */
	private static int color(BlockState st) {
		if (st.is(BlockTags.DIAMOND_ORES)) {
			return 0x4CE6D8;
		}
		if (st.is(BlockTags.EMERALD_ORES)) {
			return 0x2BD46C;
		}
		if (st.is(BlockTags.GOLD_ORES)) {
			return 0xF8D640;
		}
		if (st.is(BlockTags.REDSTONE_ORES)) {
			return 0xE81E1E;
		}
		if (st.is(BlockTags.LAPIS_ORES)) {
			return 0x2F56C8;
		}
		if (st.is(BlockTags.IRON_ORES)) {
			return 0xD9A27C;
		}
		if (st.is(BlockTags.COPPER_ORES)) {
			return 0xE0723C;
		}
		if (st.is(BlockTags.COAL_ORES)) {
			return 0x2B2B2B;
		}
		if (st.is(Blocks.NETHER_QUARTZ_ORE)) {
			return 0xEDE6DC;
		}
		if (st.is(Blocks.ANCIENT_DEBRIS)) {
			return 0x7A4E3A;
		}
		return -1;
	}

	private static void render(PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers) {
		if (FADES.isEmpty() || buffers == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1.0e9F);
		lastFrame = now;
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float strength = Math.max(0, Math.min(100, WidgetConfig.get().oreGlowStrength)) / 100.0F;
		// sanftes Pulsieren (1,6 s)
		float pulse = 0.55F + 0.45F * (float) Math.sin(System.currentTimeMillis() / 1600.0 * Math.PI * 2);
		// normale Mischung (keine Aufhellung): Farben bleiben wie die Erze; beidseitig, damit auch Oberseiten erscheinen
		VertexConsumer vc = buffers.getBuffer(RenderTypes.debugQuads());
		PoseStack.Pose pose = poseStack.last();
		var it = FADES.values().iterator();
		while (it.hasNext()) {
			Fade f = it.next();
			// weich ein- (≈0,25 s) und ausblenden (≈0,4 s)
			f.alpha = f.visible ? Math.min(1.0F, f.alpha + dt * 4.0F) : Math.max(0.0F, f.alpha - dt * 2.5F);
			if (f.alpha <= 0.0F && !f.visible) {
				it.remove();
				continue;
			}
			Ore o = f.ore;
			if (o == null) {
				continue;
			}
			float ease = f.alpha * f.alpha * (3.0F - 2.0F * f.alpha);
			float x = (float) (o.x() - cam.x), y = (float) (o.y() - cam.y), z = (float) (o.z() - cam.z);
			float r = ((o.rgb() >> 16) & 0xFF) / 255F, g = ((o.rgb() >> 8) & 0xFF) / 255F, b = (o.rgb() & 0xFF) / 255F;
			for (Direction d : Direction.values()) {
				if ((o.faces() & (1 << d.ordinal())) == 0) {
					continue;
				}
				face(pose, vc, x, y, z, d, r, g, b, strength * pulse * ease);
			}
		}
	}

	/** Leuchtender Rand (4 Streifen) und ein schwacher Schimmer auf einer Blockseite, knapp davor. */
	private static void face(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, Direction d, float r, float g,
			float b, float a) {
		float e = 0.004F; // knapp vor der Fläche, damit nichts flackert
		float[][] c = corners(x, y, z, d, e);
		float w = 0.09F; // Randbreite
		// innere Ecken (zur Mitte hin versetzt)
		float[][] in = new float[4][3];
		for (int i = 0; i < 4; i++) {
			for (int k = 0; k < 3; k++) {
				float mid = (c[0][k] + c[2][k]) / 2;
				in[i][k] = c[i][k] + (mid - c[i][k]) * (w * 2);
			}
		}
		float edge = Math.min(1.0F, a * 0.95F), fill = a * 0.18F;
		// dunkle Erze (Kohle): der Rand leuchtet heller, sonst geht er auf dunklem Gestein unter
		float lum = 0.3F * r + 0.59F * g + 0.11F * b;
		float er = r, eg = g, eb = b;
		if (lum < 0.4F) {
			float k = 0.62F;
			er = r + (1.0F - r) * k;
			eg = g + (1.0F - g) * k;
			eb = b + (1.0F - b) * k;
			edge = Math.min(1.0F, a * 1.1F);
		}
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			quad(pose, vc, c[i], c[j], in[j], in[i], er, eg, eb, edge);
		}
		quad(pose, vc, in[0], in[1], in[2], in[3], r, g, b, fill);
	}

	private static float[][] corners(float x, float y, float z, Direction d, float e) {
		return switch (d) {
			case UP -> new float[][]{{x, y + 1 + e, z}, {x + 1, y + 1 + e, z}, {x + 1, y + 1 + e, z + 1}, {x, y + 1 + e, z + 1}};
			case DOWN -> new float[][]{{x, y - e, z}, {x, y - e, z + 1}, {x + 1, y - e, z + 1}, {x + 1, y - e, z}};
			case NORTH -> new float[][]{{x, y, z - e}, {x, y + 1, z - e}, {x + 1, y + 1, z - e}, {x + 1, y, z - e}};
			case SOUTH -> new float[][]{{x, y, z + 1 + e}, {x + 1, y, z + 1 + e}, {x + 1, y + 1, z + 1 + e}, {x, y + 1, z + 1 + e}};
			case WEST -> new float[][]{{x - e, y, z}, {x - e, y, z + 1}, {x - e, y + 1, z + 1}, {x - e, y + 1, z}};
			case EAST -> new float[][]{{x + 1 + e, y, z}, {x + 1 + e, y + 1, z}, {x + 1 + e, y + 1, z + 1}, {x + 1 + e, y, z + 1}};
		};
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer vc, float[] p0, float[] p1, float[] p2, float[] p3, float r, float g,
			float b, float a) {
		vc.addVertex(pose, p0[0], p0[1], p0[2]).setColor(r, g, b, a);
		vc.addVertex(pose, p1[0], p1[1], p1[2]).setColor(r, g, b, a);
		vc.addVertex(pose, p2[0], p2[1], p2[2]).setColor(r, g, b, a);
		vc.addVertex(pose, p3[0], p3[1], p3[2]).setColor(r, g, b, a);
	}
}
