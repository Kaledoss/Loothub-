package de.loothub.client.quick;

import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Verbundene Texturen: Gleiche Blöcke nebeneinander (Glas in allen Farben) zeigen keine Ränder dazwischen, eine
 * Glaswand wirkt wie eine große Scheibe. Ohne eigenes Ressourcenpaket: Jede Blockseite wird in vier Viertel geteilt;
 * grenzt ein Viertel an einen gleichen Block, bekommt es einen Ausschnitt aus der Mitte der Textur (ohne Rand),
 * sonst den normalen Ausschnitt mit Rand. Funktioniert dadurch auch mit fremden Ressourcenpaketen. Nur Anzeige.
 */
public final class ConnectedTextures {
	private static final float EPS = 0.001f;

	/** Blöcke mit verbundenen Texturen. */
	private static Set<Block> blocks() {
		return Set.of(Blocks.GLASS, Blocks.TINTED_GLASS,
				Blocks.WHITE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS,
				Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.LIME_STAINED_GLASS,
				Blocks.PINK_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS, Blocks.LIGHT_GRAY_STAINED_GLASS,
				Blocks.CYAN_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS, Blocks.BLUE_STAINED_GLASS,
				Blocks.BROWN_STAINED_GLASS, Blocks.GREEN_STAINED_GLASS, Blocks.RED_STAINED_GLASS,
				Blocks.BLACK_STAINED_GLASS);
	}

	private static final ThreadLocal<MutableMesh> BUFFER = ThreadLocal.withInitial(() -> Renderer.get().mutableMesh());

	private ConnectedTextures() {
	}

	public static void register() {
		Set<Block> targets = blocks();
		ModelLoadingPlugin.register(ctx -> ctx.modifyBlockModelAfterBake().register((model, context) ->
				targets.contains(context.state().getBlock()) ? new Model(model) : model));
	}

	static boolean enabled() {
		return WidgetConfig.get().connectedTextures;
	}

	/** Nach dem Umschalten: alle Chunks neu zeichnen. */
	public static void refresh() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.levelRenderer != null) {
			mc.levelRenderer.allChanged();
		}
	}

	/** Umhüllt das normale Modell und teilt volle Blockseiten in vier passende Viertel. */
	private static final class Model extends WrapperBlockStateModel {
		Model(BlockStateModel wrapped) {
			super(wrapped);
		}

		@Override
		public Object createGeometryKey(BlockAndTintGetter view, BlockPos pos, BlockState state, RandomSource random) {
			return enabled() ? null : super.createGeometryKey(view, pos, state, random); // hängt von den Nachbarn ab
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter view, BlockPos pos, BlockState state, RandomSource random,
				Predicate<Direction> cullTest) {
			if (!enabled()) {
				super.emitQuads(emitter, view, pos, state, random, cullTest);
				return;
			}
			MutableMesh buf = BUFFER.get();
			buf.clear();
			wrapped.emitQuads(buf.emitter(), view, pos, state, random, cullTest);
			Block block = state.getBlock();
			BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
			buf.forEach(q -> emit(emitter, q, view, pos, block, probe));
			buf.clear();
		}
	}

	private static void emit(QuadEmitter out, QuadView q, BlockAndTintGetter view, BlockPos pos, Block block,
			BlockPos.MutableBlockPos probe) {
		Direction face = q.cullFace();
		if (face == null || !fullFace(q, face)) {
			out.copyFrom(q).emit();
			return;
		}
		// Ecken 0..3: P(s,t) = mix(mix(p0,p3,s), mix(p1,p2,s), t) – Kanten s=0 (p0-p1), s=1 (p3-p2), t=0 (p0-p3), t=1 (p1-p2)
		float[][] p = new float[4][3];
		float[][] uv = new float[4][2];
		for (int i = 0; i < 4; i++) {
			p[i][0] = q.x(i);
			p[i][1] = q.y(i);
			p[i][2] = q.z(i);
			uv[i][0] = q.u(i);
			uv[i][1] = q.v(i);
		}
		boolean s0 = connected(view, pos, block, face, edgeDir(p, 0f, 0.5f), probe);
		boolean s1 = connected(view, pos, block, face, edgeDir(p, 1f, 0.5f), probe);
		boolean t0 = connected(view, pos, block, face, edgeDir(p, 0.5f, 0f), probe);
		boolean t1 = connected(view, pos, block, face, edgeDir(p, 0.5f, 1f), probe);
		if (!s0 && !s1 && !t0 && !t1) {
			out.copyFrom(q).emit();
			return;
		}
		for (int qs = 0; qs < 2; qs++) {
			for (int qt = 0; qt < 2; qt++) {
				float sa = qs * 0.5f, sb = sa + 0.5f;
				float ta = qt * 0.5f, tb = ta + 0.5f;
				// Textur-Ausschnitt: an verbundenen Kanten aus der Mitte (ohne Rand)
				boolean sConn = qs == 0 ? s0 : s1;
				boolean tConn = qt == 0 ? t0 : t1;
				float usa = sConn ? 0.25f : sa, usb = sConn ? 0.75f : sb;
				float uta = tConn ? 0.25f : ta, utb = tConn ? 0.75f : tb;
				out.copyFrom(q);
				vertex(out, 0, p, uv, sa, ta, usa, uta);
				vertex(out, 1, p, uv, sa, tb, usa, utb);
				vertex(out, 2, p, uv, sb, tb, usb, utb);
				vertex(out, 3, p, uv, sb, ta, usb, uta);
				out.emit();
			}
		}
	}

	private static void vertex(QuadEmitter out, int i, float[][] p, float[][] uv, float s, float t, float us, float ut) {
		float[] pos = mix(p, s, t);
		float[] tex = mix(uv, us, ut);
		out.pos(i, pos[0], pos[1], pos[2]);
		out.uv(i, tex[0], tex[1]);
	}

	private static float[] mix(float[][] c, float s, float t) {
		int n = c[0].length;
		float[] r = new float[n];
		for (int k = 0; k < n; k++) {
			float a = c[0][k] + (c[3][k] - c[0][k]) * s;
			float b = c[1][k] + (c[2][k] - c[1][k]) * s;
			r[k] = a + (b - a) * t;
		}
		return r;
	}

	/** Richtung von der Mitte der Seite zur Mitte einer Kante (in Blockrichtungen). */
	private static Direction edgeDir(float[][] p, float s, float t) {
		float[] c = mix(p, 0.5f, 0.5f);
		float[] e = mix(p, s, t);
		float dx = e[0] - c[0], dy = e[1] - c[1], dz = e[2] - c[2];
		float ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ax >= ay && ax >= az) {
			return dx > 0 ? Direction.EAST : Direction.WEST;
		}
		if (ay >= az) {
			return dy > 0 ? Direction.UP : Direction.DOWN;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** Gleicher Block daneben, dessen gleiche Seite auch sichtbar ist (sonst bleibt der Rand an der Ecke). */
	private static boolean connected(BlockAndTintGetter view, BlockPos pos, Block block, Direction face, Direction dir,
			BlockPos.MutableBlockPos probe) {
		if (dir.getAxis() == face.getAxis()) {
			return false;
		}
		probe.setWithOffset(pos, dir);
		if (view.getBlockState(probe).getBlock() != block) {
			return false;
		}
		probe.move(face);
		return view.getBlockState(probe).getBlock() != block;
	}

	/** Liegt das Viereck auf der Blockgrenze und deckt die ganze Seite ab? */
	private static boolean fullFace(QuadView q, Direction face) {
		int a1, a2;
		switch (face.getAxis()) {
			case X -> {
				a1 = 1;
				a2 = 2;
			}
			case Y -> {
				a1 = 0;
				a2 = 2;
			}
			default -> {
				a1 = 0;
				a2 = 1;
			}
		}
		float min1 = 1, max1 = 0, min2 = 1, max2 = 0;
		for (int i = 0; i < 4; i++) {
			float v1 = q.posByIndex(i, a1);
			float v2 = q.posByIndex(i, a2);
			min1 = Math.min(min1, v1);
			max1 = Math.max(max1, v1);
			min2 = Math.min(min2, v2);
			max2 = Math.max(max2, v2);
		}
		return min1 < EPS && max1 > 1 - EPS && min2 < EPS && max2 > 1 - EPS;
	}
}
