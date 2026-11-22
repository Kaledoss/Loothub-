package de.loothub.client.progress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import de.loothub.client.Fmt;

/**
 * Crafting-Rätsel: Ein Gegenstand wird gezeigt, das Rezept muss in die 3×3-Werkbank gelegt werden. Zutat unten anklicken,
 * dann Feld anklicken (Rechtsklick leert ein Feld). 90 Sekunden – jedes richtige Rezept gibt einen Punkt und 5 Sekunden.
 * Das Rezept darf irgendwo im Raster liegen und auch gespiegelt sein.
 */
final class CraftScreen extends ArcadeScreen {
	private record Recipe(Item result, String[] rows, Map<Character, Item> key) {
	}

	private static final List<Recipe> RECIPES = new ArrayList<>();

	private static void r(Item result, String rows, Object... key) {
		Map<Character, Item> m = new java.util.HashMap<>();
		for (int i = 0; i < key.length; i += 2) {
			m.put((Character) key[i], (Item) key[i + 1]);
		}
		RECIPES.add(new Recipe(result, rows.split("\\|"), m));
	}

	static {
		Item p = Items.OAK_PLANKS, s = Items.STICK, c = Items.COBBLESTONE, i = Items.IRON_INGOT, g = Items.GOLD_INGOT;
		r(Items.CRAFTING_TABLE, "PP|PP", 'P', p);
		r(Items.CHEST, "PPP|P P|PPP", 'P', p);
		r(Items.FURNACE, "CCC|C C|CCC", 'C', c);
		r(Items.TORCH, "K|S", 'K', Items.COAL, 'S', s);
		r(Items.STICK, "P|P", 'P', p);
		r(Items.WOODEN_PICKAXE, "PPP| S | S ", 'P', p, 'S', s);
		r(Items.STONE_PICKAXE, "CCC| S | S ", 'C', c, 'S', s);
		r(Items.IRON_PICKAXE, "III| S | S ", 'I', i, 'S', s);
		r(Items.DIAMOND_SWORD, "D|D|S", 'D', Items.DIAMOND, 'S', s);
		r(Items.IRON_SWORD, "I|I|S", 'I', i, 'S', s);
		r(Items.IRON_AXE, "II|IS| S", 'I', i, 'S', s);
		r(Items.IRON_SHOVEL, "I|S|S", 'I', i, 'S', s);
		r(Items.BOW, " ST|S T| ST", 'S', s, 'T', Items.STRING);
		r(Items.BUCKET, "I I| I ", 'I', i);
		r(Items.SHEARS, " I|I ", 'I', i);
		r(Items.LADDER, "S S|SSS|S S", 'S', s);
		r(Items.BREAD, "WWW", 'W', Items.WHEAT);
		r(Items.CAKE, "MMM|SES|WWW", 'M', Items.MILK_BUCKET, 'S', Items.SUGAR, 'E', Items.EGG, 'W', Items.WHEAT);
		r(Items.GOLDEN_APPLE, "GGG|GAG|GGG", 'G', g, 'A', Items.APPLE);
		r(Items.BOOKSHELF, "PPP|BBB|PPP", 'P', p, 'B', Items.BOOK);
		r(Items.TNT, "GSG|SGS|GSG", 'G', Items.GUNPOWDER, 'S', Items.SAND);
		r(Items.COMPASS, " I |IRI| I ", 'I', i, 'R', Items.REDSTONE);
		r(Items.CLOCK, " G |GRG| G ", 'G', g, 'R', Items.REDSTONE);
		r(Items.SHIELD, "PIP|PPP| P ", 'P', p, 'I', i);
		r(Items.FISHING_ROD, "  S| ST|S T", 'S', s, 'T', Items.STRING);
		r(Items.IRON_HELMET, "III|I I", 'I', i);
		r(Items.IRON_CHESTPLATE, "I I|III|III", 'I', i);
		r(Items.IRON_LEGGINGS, "III|I I|I I", 'I', i);
		r(Items.IRON_BOOTS, "I I|I I", 'I', i);
		r(Items.LANTERN, "NNN|NTN|NNN", 'N', Items.IRON_NUGGET, 'T', Items.TORCH);
		r(Items.CAMPFIRE, " S |SKS|LLL", 'S', s, 'K', Items.COAL, 'L', Items.OAK_LOG);
		r(Items.HOPPER, "I I|ICI| I ", 'I', i, 'C', Items.CHEST);
		r(Items.WHITE_BED, "WWW|PPP", 'W', Items.WHITE_WOOL, 'P', p);
		r(Items.ARROW, "F|S|E", 'F', Items.FLINT, 'S', s, 'E', Items.FEATHER);
		r(Items.PAINTING, "SSS|SWS|SSS", 'S', s, 'W', Items.WHITE_WOOL);
		r(Items.JUKEBOX, "PPP|PDP|PPP", 'P', p, 'D', Items.DIAMOND);
		r(Items.COOKIE, "WCW", 'W', Items.WHEAT, 'C', Items.COCOA_BEANS);
		r(Items.OAK_DOOR, "PP|PP|PP", 'P', p);
		r(Items.OAK_FENCE, "PSP|PSP", 'P', p, 'S', s);
		r(Items.STONE_BRICKS, "TT|TT", 'T', Items.STONE);
		r(Items.ANVIL, "BBB| I |III", 'B', Items.IRON_BLOCK, 'I', i);
		r(Items.CAULDRON, "I I|I I|III", 'I', i);
		r(Items.RAIL, "I I|ISI|I I", 'I', i, 'S', s);
		r(Items.PISTON, "PPP|CIC|CRC", 'P', p, 'C', c, 'I', i, 'R', Items.REDSTONE);
		r(Items.BOWL, "P P| P ", 'P', p);
		r(Items.GLASS_BOTTLE, "G G| G ", 'G', Items.GLASS);
	}

	private static final int ROUND_MS = 90_000, BONUS_MS = 5_000;

	private final Random random = new Random();
	private final Item[] grid = new Item[9];
	private final List<Item> palette = new ArrayList<>();
	private Recipe current;
	private Item selected;
	private long endAt;
	private int cell, flash;
	private final Set<Integer> recent = new LinkedHashSet<>();

	CraftScreen(Screen parent) {
		super(parent, "craft");
		reset();
	}

	@Override
	protected int[] fieldSize(int maxW, int maxH) {
		cell = Math.max(20, Math.min(26, Math.min(maxW / 12, maxH / 8)));
		return new int[]{cell * 12, cell * 8};
	}

	@Override
	protected void reset() {
		recent.clear();
		next();
		endAt = 0;
	}

	@Override
	protected void begin() {
		if (!started) {
			endAt = System.currentTimeMillis() + ROUND_MS;
		}
		super.begin();
	}

	private void next() {
		int idx;
		do {
			idx = random.nextInt(RECIPES.size());
		} while (recent.contains(idx));
		recent.add(idx);
		if (recent.size() > 12) {
			recent.remove(recent.iterator().next());
		}
		current = RECIPES.get(idx);
		java.util.Arrays.fill(grid, null);
		selected = null;
		// Zutaten des Rezepts plus ein paar falsche, gemischt
		Set<Item> set = new LinkedHashSet<>(current.key().values());
		List<Item> all = new ArrayList<>();
		for (Recipe r : RECIPES) {
			all.addAll(r.key().values());
		}
		Collections.shuffle(all, random);
		for (Item it : all) {
			if (set.size() >= 8) {
				break;
			}
			set.add(it);
		}
		palette.clear();
		palette.addAll(set);
		Collections.shuffle(palette, random);
	}

	@Override
	protected void step() {
		if (flash > 0) {
			flash--;
		}
		if (System.currentTimeMillis() >= endAt) {
			gameOver();
		}
	}

	@Override
	protected String scoreText() {
		long left = started ? Math.max(0, endAt - System.currentTimeMillis()) : ROUND_MS;
		return Component.translatable("loothub.lh.mg.craft.score", Fmt.whole(score), left / 1000, Fmt.whole(best(game))).getString();
	}

	/** Liegt das Rezept im Raster (verschoben und/oder gespiegelt)? */
	private boolean matches() {
		for (boolean mirror : new boolean[]{false, true}) {
			int h = current.rows().length, w = 0;
			for (String row : current.rows()) {
				w = Math.max(w, row.length());
			}
			for (int oy = 0; oy + h <= 3; oy++) {
				for (int ox = 0; ox + w <= 3; ox++) {
					if (fits(ox, oy, w, h, mirror)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean fits(int ox, int oy, int w, int h, boolean mirror) {
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < 3; x++) {
				Item want = null;
				int rx = x - ox, ry = y - oy;
				if (rx >= 0 && ry >= 0 && rx < w && ry < h) {
					String row = current.rows()[ry];
					int cx = mirror ? w - 1 - rx : rx;
					char ch = cx < row.length() ? row.charAt(cx) : ' ';
					want = ch == ' ' ? null : current.key().get(ch);
				}
				if (want != grid[y * 3 + x]) {
					return false;
				}
			}
		}
		return true;
	}

	// Layout: links das Ziel, Mitte das 3×3-Raster, unten die Zutaten
	private int gx() {
		return fx + cell * 4;
	}

	private int gy() {
		return fy + cell;
	}

	private int px(int i) {
		return fx + (fw - palette.size() * (cell + 4)) / 2 + i * (cell + 4);
	}

	private int py() {
		return fy + fh - cell - 8;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (over) {
			return super.mouseClicked(e, doubleClick);
		}
		double mx = e.x(), my = e.y();
		for (int i = 0; i < palette.size(); i++) {
			if (mx >= px(i) && mx < px(i) + cell && my >= py() && my < py() + cell) {
				selected = palette.get(i);
				begin();
				click(1.4F);
				return true;
			}
		}
		int cx = (int) Math.floor((mx - gx()) / cell), cy = (int) Math.floor((my - gy()) / cell);
		if (mx >= gx() && my >= gy() && cx < 3 && cy < 3) {
			begin();
			int idx = cy * 3 + cx;
			grid[idx] = e.button() == 1 ? null : selected;
			click(e.button() == 1 ? 0.8F : 1.0F);
			if (matches()) {
				score++;
				flash = 10;
				endAt += BONUS_MS;
				if (minecraft != null && minecraft.player != null) {
					minecraft.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.4F, 1.6F);
				}
				next();
			}
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	private void click(float pitch) {
		if (minecraft != null && minecraft.player != null) {
			minecraft.player.playSound(SoundEvents.WOOD_PLACE, 0.3F, pitch);
		}
	}

	@Override
	protected void drawField(GuiGraphics g, float partial) {
		// Werkbank-Holz (Ludo) als Untergrund
		for (int x = 0; x < fw; x += 128) {
			for (int y = 0; y < fh; y += 128) {
				g.blit(RenderPipelines.GUI_TEXTURED, tex("mg_wood"), fx + x, fy + y, 0f, 0f, 128, 128, 128, 128, 128, 128);
			}
		}
		g.fill(fx, fy, fx + fw, fy + fh, flash > 0 ? 0x4055E06A : 0x50000000);
		// Ziel
		int tx = fx + cell, ty = gy() + cell - 4;
		g.fill(tx - 4, ty - 4, tx + cell + 4, ty + cell + 4, 0xFF8B8B8B);
		g.fill(tx - 3, ty - 3, tx + cell + 3, ty + cell + 3, 0xFF373737);
		renderBig(g, new ItemStack(current.result()), tx, ty, cell);
		Component name = new ItemStack(current.result()).getHoverName();
		int nw = font.width(name);
		g.drawString(font, name, tx + cell / 2 - nw / 2, ty + cell + 8, 0xFFFFFFFF, true);
		g.drawString(font, "→", gx() - cell + 4, gy() + cell + cell / 2 - 4, 0xFFF2C14E, true);
		// Raster
		for (int i = 0; i < 9; i++) {
			int x = gx() + (i % 3) * cell, y = gy() + (i / 3) * cell;
			g.fill(x, y, x + cell, y + cell, 0xFF8B8B8B);
			g.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, 0xFF373737);
			if (grid[i] != null) {
				g.renderItem(new ItemStack(grid[i]), x + (cell - 16) / 2, y + (cell - 16) / 2);
			}
		}
		// Zutaten
		for (int i = 0; i < palette.size(); i++) {
			int x = px(i), y = py();
			boolean sel = palette.get(i) == selected;
			g.fill(x - 1, y - 1, x + cell + 1, y + cell + 1, sel ? 0xFFF2C14E : 0xFF8B8B8B);
			g.fill(x, y, x + cell, y + cell, 0xFF373737);
			g.renderItem(new ItemStack(palette.get(i)), x + (cell - 16) / 2, y + (cell - 16) / 2);
		}
	}

	private static void renderBig(GuiGraphics g, ItemStack stack, int x, int y, int size) {
		float s = size / 16f;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(s, s);
		g.renderItem(stack, 0, 0);
		g.pose().popMatrix();
	}

	static {
		// Gegenstände ohne Rezept-Zutat-Paar wären unlösbar – zur Sicherheit prüfen
		for (Recipe rc : RECIPES) {
			for (String row : rc.rows()) {
				for (char ch : row.toCharArray()) {
					if (ch != ' ' && !rc.key().containsKey(ch)) {
						throw new IllegalStateException("Rezept ohne Zutat: " + rc.result());
					}
				}
			}
		}
	}
}
