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
				Blocks.BLACK_STAINED_GLASS,
				Blocks.GLASS_PANE, Blocks.WHITE_STAINED_GLASS_PANE, Blocks.ORANGE_STAINED_GLASS_PANE,
				Blocks.MAGENTA_STAINED_GLASS_PANE, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE, Blocks.YELLOW_STAINED_GLASS_PANE,
				Blocks.LIME_STAINED_GLASS_PANE, Blocks.PINK_STAINED_GLASS_PANE, Blocks.GRAY_STAINED_GLASS_PANE,
				Blocks.LIGHT_GRAY_STAINED_GLASS_PANE, Blocks.CYAN_STAINED_GLASS_PANE, Blocks.PURPLE_STAINED_GLASS_PANE,
				Blocks.BLUE_STAINED_GLASS_PANE, Blocks.BROWN_STAINED_GLASS_PANE, Blocks.GREEN_STAINED_GLASS_PANE,
				Blocks.RED_STAINED_GLASS_PANE, Blocks.BLACK_STAINED_GLASS_PANE);
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

	/** Breite des Rands der Glas-Textur (1 von 16 Pixeln). */
	private static final float BORDER = 1F / 16F;

	/**
	 * Rand an verbundenen Kanten entfernen, ohne die Fläche zu teilen: Die Textur wird an jeder verbundenen Kante um
	 * die Randbreite „hineingeschoben“ (leicht gestreckt). Dadurch gibt es keine Naht in der Mitte, und es klappt auch
	 * für die halben Flächen von Glasscheiben.
	 */
	private static void emit(QuadEmitter out, QuadView q, BlockAndTintGetter view, BlockPos pos, Block block,
			BlockPos.MutableBlockPos probe) {
		Direction normal = q.nominalFace();
		if (normal == null) {
			out.copyFrom(q).emit();
			return;
		}
		// zwei Achsen in der Fläche (0 = x, 1 = y, 2 = z)
		int a1, a2;
		switch (normal.getAxis()) {
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
			min1 = Math.min(min1, q.posByIndex(i, a1));
			max1 = Math.max(max1, q.posByIndex(i, a1));
			min2 = Math.min(min2, q.posByIndex(i, a2));
			max2 = Math.max(max2, q.posByIndex(i, a2));
		}
		// nur große Flächen (ganze Blockseite oder halbe Scheibe), keine Pfosten oder schmalen Kanten
		if (max1 - min1 < 0.45F || max2 - min2 < 0.45F) {
			out.copyFrom(q).emit();
			return;
		}
		Direction face = q.cullFace(); // auf der Blockgrenze (Glasblock) – bei Scheiben null
		// Verbindungen gelten für den ganzen Block – so passen beide Hälften einer Scheibe in der Mitte zusammen
		boolean lo1 = connected(view, pos, block, face, dir(a1, false), probe);
		boolean hi1 = connected(view, pos, block, face, dir(a1, true), probe);
		boolean lo2 = connected(view, pos, block, face, dir(a2, false), probe);
		boolean hi2 = connected(view, pos, block, face, dir(a2, true), probe);
		if (!lo1 && !hi1 && !lo2 && !hi2) {
			out.copyFrom(q).emit();
			return;
		}
		// Textur als lineare Funktion der Lage in der Fläche: uv = o + d1*c1 + d2*c2 (aus drei Ecken bestimmt)
		float[] c1 = new float[4], c2 = new float[4], u = new float[4], v = new float[4];
		for (int i = 0; i < 4; i++) {
			c1[i] = q.posByIndex(i, a1);
			c2[i] = q.posByIndex(i, a2);
			u[i] = q.u(i);
			v[i] = q.v(i);
		}
		float det = (c1[1] - c1[0]) * (c2[2] - c2[0]) - (c1[2] - c1[0]) * (c2[1] - c2[0]);
		if (Math.abs(det) < 1e-6F) {
			out.copyFrom(q).emit();
			return;
		}
		float[] du = solve(c1, c2, u, det), dv = solve(c1, c2, v, det);
		// neue Lage für die Textur: an verbundenen Seiten um den Rand nach innen verschoben
		float s1lo = lo1 ? BORDER : 0F, s1hi = hi1 ? 1F - BORDER : 1F;
		float s2lo = lo2 ? BORDER : 0F, s2hi = hi2 ? 1F - BORDER : 1F;
		out.copyFrom(q);
		for (int i = 0; i < 4; i++) {
			float n1 = s1lo + c1[i] * (s1hi - s1lo);
			float n2 = s2lo + c2[i] * (s2hi - s2lo);
			float nu = u[0] + du[0] * (n1 - c1[0]) + du[1] * (n2 - c2[0]);
			float nv = v[0] + dv[0] * (n1 - c1[0]) + dv[1] * (n2 - c2[0]);
			out.uv(i, nu, nv);
		}
		out.emit();
	}

	/** Ableitungen {d/dc1, d/dc2} eines Werts aus den Ecken 0, 1, 2. */
	private static float[] solve(float[] c1, float[] c2, float[] val, float det) {
		float e1 = val[1] - val[0], e2 = val[2] - val[0];
		float d1 = (e1 * (c2[2] - c2[0]) - e2 * (c2[1] - c2[0])) / det;
		float d2 = ((c1[1] - c1[0]) * e2 - (c1[2] - c1[0]) * e1) / det;
		return new float[]{d1, d2};
	}

	private static Direction dir(int axis, boolean positive) {
		return switch (axis) {
			case 0 -> positive ? Direction.EAST : Direction.WEST;
			case 1 -> positive ? Direction.UP : Direction.DOWN;
			default -> positive ? Direction.SOUTH : Direction.NORTH;
		};
	}

	/**
	 * Gleicher Block daneben? Bei Glasblöcken muss dessen gleiche Seite auch sichtbar sein (sonst bleibt der Rand an
	 * der Ecke); bei Scheiben ({@code face} = null) zählt nur der Nachbar selbst.
	 */
	private static boolean connected(BlockAndTintGetter view, BlockPos pos, Block block, Direction face, Direction dir,
			BlockPos.MutableBlockPos probe) {
		if (face != null && dir.getAxis() == face.getAxis()) {
			return false;
		}
		probe.setWithOffset(pos, dir);
		if (view.getBlockState(probe).getBlock() != block) {
			return false;
		}
		if (face == null) {
			return true;
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
