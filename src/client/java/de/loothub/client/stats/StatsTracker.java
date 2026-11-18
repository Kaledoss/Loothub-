package de.loothub.client.stats;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Erfasst die eigene Statistik auf dem Client – nur auf Servern (nicht im Einzelspieler, dort gibt es die
 * normale Statistik). Gezählt wird nur, was auf diesem PC sichtbar passiert.
 */
public final class StatsTracker {
	/** Bewegungen über dieser Strecke pro Tick gelten als Teleport und zählen nicht. */
	private static final double MAX_STEP = 10.0;
	/** So lange nach dem letzten Treffer zählt ein Mob-Tod als eigener Kill. */
	private static final long KILL_WINDOW_MS = 5_000;

	/** {@code name}: Name eines Server-Mobs (eigener Name), sonst null → Zählung nach Mob-Art. */
	private record Target(EntityType<?> type, String name, float lastHealth, long lastHit) {
	}

	private static final Map<Integer, Target> TARGETS = new HashMap<>();

	private static Vec3 lastPos;
	private static boolean lastOnGround = true;
	private static boolean lastDead;
	private static float lastHp = -1;
	private static long lastTickTime;
	private static String eatingItem;
	private static int eatingRemaining;

	private StatsTracker() {
	}

	public static boolean tracking(Minecraft mc) {
		return mc.player != null && mc.level != null && !mc.hasSingleplayerServer()
				&& de.loothub.client.jobs.WidgetConfig.get().statsEnabled;
	}

	// ------------------------------------------------------------------ Ereignisse

	public static void onBlockBroken(net.minecraft.core.BlockPos pos, BlockState state) {
		net.minecraft.core.BlockPos p = pos.immutable();
		long now = System.currentTimeMillis();
		if (recentlyCounted(p, now)) {
			return; // schon über die Bohrer-Erkennung gezählt
		}
		lastCountedPos = p;
		lastCountedAt = now;
		markCounted(p, now);
		onBlockBroken(state);
		startAreaCheck(p, now);
	}

	public static void onBlockBroken(BlockState state) {
		Minecraft mc = Minecraft.getInstance();
		if (!tracking(mc)) {
			return;
		}
		if (isRealBlock(state.getBlock()) || isPlant(state.getBlock())) {
			StatsStore.add("mined." + BuiltInRegistries.BLOCK.getKey(state.getBlock()), 1);
		}
		toolStat("tool_mined.", mc.player.getMainHandItem());
	}

	/** Rechtsklick mit einem Werkzeug (Bogen, Angel, Hacke, Schere, Feuerzeug …). */
	public static void onToolUse(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (!tracking(mc)) {
			return;
		}
		// Ein Rechtsklick kann sowohl „auf Block“ als auch „in die Luft“ melden – pro Tick nur einmal zählen
		long tick = mc.level.getGameTime();
		if (tick == lastToolUseTick) {
			return;
		}
		lastToolUseTick = tick;
		if (stack.isEmpty()) {
			return;
		}
		if (!stack.isDamageableItem()) {
			// Sonstige Items, die man benutzt (Enderperle, Feuerwerk, Schneeball …); Essen zählt extra beim Verzehr
			if (stack.has(net.minecraft.core.component.DataComponents.CONSUMABLE)
					|| stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
				return;
			}
			// Blöcke und Pflanzen zählen beim Platzieren („placed.“), nicht als benutztes Item
			if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi
					&& (isRealBlock(bi.getBlock()) || isPlant(bi.getBlock()))) {
				return;
			}
			StatsStore.add("used." + BuiltInRegistries.ITEM.getKey(stack.getItem()), 1);
			return;
		}
		toolStat("tool_used.", stack);
	}

	private static long lastToolUseTick = -1;

	/** Werkzeuge = alles, was sich abnutzt (Spitzhacken, Äxte, Schwerter, Bögen, Angeln, Scheren …). */
	public static boolean isTool(ItemStack stack) {
		// auch unzerstörbare Server-Werkzeuge (Bohrer & Co.) – die haben keine Haltbarkeit, aber eine Werkzeug-Eigenschaft
		return !stack.isEmpty() && (stack.isDamageableItem() || stack.has(net.minecraft.core.component.DataComponents.TOOL));
	}

	private static void toolStat(String prefix, ItemStack stack) {
		if (isTool(stack)) {
			StatsStore.add(prefix + BuiltInRegistries.ITEM.getKey(stack.getItem()), 1);
		}
	}

	public static void onBlockPlaced(Block block) {
		if (tracking(Minecraft.getInstance()) && (isRealBlock(block) || isPlant(block))) {
			StatsStore.add("placed." + BuiltInRegistries.BLOCK.getKey(block), 1);
		}
	}

	public static void onPickup(ItemStack stack, int amount) {
		if (!tracking(Minecraft.getInstance()) || stack.isEmpty()) {
			return;
		}
		// Server-Items mit eigenem Namen (z. B. „Kasuarhorn“ als Mob-Drop) unter ihrem Namen zählen
		var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		String name = custom == null ? null : net.minecraft.ChatFormatting.stripFormatting(custom.getString());
		if (name != null && !(name = name.strip()).isEmpty()) {
			StatsStore.add("picked.~" + (name.length() > 40 ? name.substring(0, 40).strip() : name), Math.max(1, amount));
			return;
		}
		StatsStore.add("picked." + BuiltInRegistries.ITEM.getKey(stack.getItem()), Math.max(1, amount));
	}

	/**
	 * Nur „echte“ Blöcke zählen. Nicht dazu gehören:
	 * <ul>
	 *   <li>Überbaubares wie Gras, hohes Gras, Farn, Seegras, Ranken, Schneeschicht,</li>
	 *   <li>alles ohne oder mit schmaler/flacher Kollision: Schilder, Pflanzen, Fackeln, Ketten, Laternen, Falltüren, Türen, Zäune.</li>
	 * </ul>
	 */
	/**
	 * Pflanzen: Blumen, Setzlinge, Feldfrüchte, Kaktus, Zuckerrohr, Bambus, Kakao, Pilze, Kelp … – eigene Kategorie
	 * in der Statistik. Überbaubares (Gras, Farn, Seegras) zählt weiterhin gar nicht.
	 */
	public static boolean isPlant(Block block) {
		BlockState state = block.defaultBlockState();
		if (state.canBeReplaced()) {
			return false;
		}
		return block instanceof net.minecraft.world.level.block.VegetationBlock
				|| block instanceof net.minecraft.world.level.block.CactusBlock
				|| block instanceof net.minecraft.world.level.block.SugarCaneBlock
				|| block instanceof net.minecraft.world.level.block.BambooStalkBlock
				|| block instanceof net.minecraft.world.level.block.CocoaBlock
				|| block instanceof net.minecraft.world.level.block.GrowingPlantBlock
				|| state.is(net.minecraft.tags.BlockTags.FLOWERS)
				|| state.is(net.minecraft.tags.BlockTags.SAPLINGS)
				|| state.is(net.minecraft.tags.BlockTags.CROPS);
	}

	/** Blöcke, die trotz Sonderform/Funktion als „echter“ Block zählen (z. B. Spawner). */
	private static final java.util.Set<String> ALWAYS_BLOCK = java.util.Set.of("minecraft:spawner", "minecraft:trial_spawner",
			"minecraft:reinforced_deepslate", "minecraft:vault");

	public static boolean isRealBlock(Block block) {
		if (ALWAYS_BLOCK.contains(BuiltInRegistries.BLOCK.getKey(block).toString())) {
			return true;
		}
		BlockState state = block.defaultBlockState();
		if (state.canBeReplaced() || isPlant(block)) {
			return false;
		}
		// Nur „richtige“ Blöcke: Kollision, die fast die ganze Grundfläche füllt und mindestens halb so hoch ist.
		// Damit fallen Schilder, Fackeln, Blumen, Knöpfe, Schienen, Ketten, Laternen, Falltüren, Türen,
		// Zäune, Mauern und Teppiche heraus (Glasscheiben zählen); Stufen, Treppen, Kisten bleiben drin.
		// Blöcke mit Inhalt/Funktion (Kisten, Fässer, Öfen, Trichter, Shulkerkisten …) zählen als Item
		if (block instanceof net.minecraft.world.level.block.EntityBlock) {
			return false;
		}
		// Glasscheiben zählen trotz schmaler Form als Block
		if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("glass_pane")) {
			return true;
		}
		try {
			net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(
					net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
			if (shape.isEmpty()) {
				return false;
			}
			net.minecraft.world.phys.AABB box = shape.bounds();
			return box.getXsize() >= 0.75 && box.getZsize() >= 0.75 && box.getYsize() >= 0.4;
		} catch (RuntimeException e) {
			return state.blocksMotion();
		}
	}

	/** Treffer auf ein Lebewesen merken: für ausgeteilten Schaden und Kills. */
	public static void onAttack(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (tracking(mc)) {
			toolStat("tool_hits.", mc.player.getMainHandItem());
		}
		LivingEntity living = livingOf(entity);
		if (living != null && tracking(mc) && !TARGETS.containsKey(living.getId())) {
			describe(entity, living);
		}
		if (living != null && tracking(mc)) {
			TARGETS.put(living.getId(), new Target(living.getType(), customName(living), living.getHealth(),
					System.currentTimeMillis()));
		}
	}

	/**
	 * Eigene Server-Mobs bestehen oft aus mehreren Teilen (unsichtbare Trefferfläche, Namensschild, Modell), die
	 * aufeinander reiten. Getroffen wird dann manchmal ein Teil, das kein Lebewesen ist – das zugehörige Lebewesen
	 * suchen.
	 */
	private static LivingEntity livingOf(Entity entity) {
		if (entity instanceof LivingEntity l && !(entity instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
			return l;
		}
		for (Entity v = entity.getVehicle(); v != null; v = v.getVehicle()) {
			if (v instanceof LivingEntity l && !(v instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
				return l;
			}
		}
		for (Entity p : entity.getPassengers()) {
			if (p instanceof LivingEntity l && !(p instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
				return l;
			}
		}
		// lose Trefferfläche (Interaktions-Entity, Anzeige, Rüstungsständer), die an nichts hängt: das nächste Lebewesen
		// direkt daneben ist der eigentliche Mob (z. B. Maulwurf = Kaninchen unter einer Trefferfläche)
		LivingEntity near = null;
		double best = 2.5 * 2.5;
		for (Entity e : entity.level().getEntities(entity, entity.getBoundingBox().inflate(2.0))) {
			if (e instanceof LivingEntity l && !(e instanceof net.minecraft.world.entity.player.Player)
					&& !(e instanceof net.minecraft.world.entity.decoration.ArmorStand) && l.isAlive()) {
				double d = e.distanceToSqr(entity);
				if (d < best) {
					best = d;
					near = l;
				}
			}
		}
		if (near != null) {
			return near;
		}
		return entity instanceof LivingEntity l ? l : null;
	}

	/**
	 * Hilfe zum Nachbessern: Was die Mod beim ersten Treffer auf einen Mob sieht (getroffenes Teil, Mob, Mitfahrer und
	 * Teile in der Nähe samt Namen und Item-Modellen) – steht im Log unter „[Loothub-Mobinfo]“.
	 */
	private static void describe(Entity hit, LivingEntity living) {
		StringBuilder b = new StringBuilder("[Loothub-Mobinfo] getroffen: ").append(part(hit))
				.append(" | Mob: ").append(part(living)).append(" | gezählt als: ")
				.append(customName(living) != null ? customName(living) : BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()));
		for (Entity e : living.level().getEntities(living, living.getBoundingBox().inflate(2.0, 1.0, 2.0).expandTowards(0, 2.5, 0))) {
			if (!(e instanceof net.minecraft.world.entity.player.Player)) {
				b.append("\n    daneben: ").append(part(e));
			}
		}
		de.loothub.Loothub.LOGGER.info(b.toString());
	}

	private static String part(Entity e) {
		StringBuilder b = new StringBuilder(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
		if (e.isInvisible()) {
			b.append(" (unsichtbar)");
		}
		if (e.getCustomName() != null) {
			b.append(" Name=\"").append(e.getCustomName().getString()).append('"');
		}
		if (e instanceof net.minecraft.world.entity.Display.TextDisplay td) {
			b.append(" Text=\"").append(((de.loothub.client.mixin.TextDisplayAccessor) td).loothub$getText().getString().replace('\n', ' ')).append('"');
		}
		ItemStack stack = e instanceof net.minecraft.world.entity.Display.ItemDisplay idp
				? ((de.loothub.client.mixin.ItemDisplayAccessor) idp).loothub$getItemStack()
				: e instanceof LivingEntity le ? le.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD) : ItemStack.EMPTY;
		if (!stack.isEmpty()) {
			b.append(" Item=").append(BuiltInRegistries.ITEM.getKey(stack.getItem()));
			var model = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
			if (model != null) {
				b.append(" Modell=").append(model);
			}
			var cmd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
			if (cmd != null) {
				b.append(" ModellDaten=").append(cmd);
			}
		}
		if (e.getVehicle() != null) {
			b.append(" reitet auf ").append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getVehicle().getType()));
		}
		return b.toString();
	}

	/**
	 * Name eines Server-Mobs: eigener Name des Mobs oder seines Namensschilds (Mitfahrer/Reittier). Zahlen, Lebensanzeige
	 * und Klammern fliegen raus, damit „Waldgeist [Lv. 3] ❤ 40/40“ immer als „Waldgeist“ zählt.
	 */
	private static String customName(LivingEntity living) {
		net.minecraft.network.chat.Component name = living.getCustomName();
		if (name == null) {
			for (Entity p : living.getPassengers()) {
				name = p.getCustomName();
				if (name != null) {
					break;
				}
			}
		}
		if (name == null && living.getVehicle() != null) {
			name = living.getVehicle().getCustomName();
		}
		if (name == null) {
			name = nearbyLabel(living); // Namensschild schwebt nur über dem Mob (Textanzeige/Rüstungsständer)
		}
		if (name == null) {
			// kein Name zu lesen: Name des Modells nehmen (Item-Anzeige bzw. Kopf-Item eines Rüstungsständers mit eigenem
			// Item-Modell, z. B. „opsucht:mobs/kasuar“ → „Kasuar“)
			String model = modelName(living);
			if (model != null) {
				return model;
			}
			// unsichtbarer Träger (z. B. Fledermaus als Trefferfläche) ohne lesbaren Namen: nicht als echtes Tier zählen
			return living.isInvisible() ? "Server-Mob (" + living.getType().getDescription().getString() + ")" : null;
		}
		String n = net.minecraft.ChatFormatting.stripFormatting(name.getString());
		if (n == null) {
			return null;
		}
		n = n.split("\\R", 2)[0] // nur die erste Zeile
				.replaceAll("[\\[(<{][^\\])>}]*[\\])>}]", " ") // [Lv. 3], (Boss) …
				.replaceAll("[❤♥✦★☆%/|:+\\-]", " ")
				.replaceAll("\\d+([.,]\\d+)?", " ")
				.replaceAll("\\s+", " ").strip();
		if (n.length() < 2) {
			return null;
		}
		return n.length() > 40 ? n.substring(0, 40).strip() : n;
	}

	/** Text einer Textanzeige oder eines Rüstungsständers direkt über dem Mob (bis 3 Blöcke hoch). */
	private static net.minecraft.network.chat.Component nearbyLabel(LivingEntity living) {
		var level = living.level();
		var box = living.getBoundingBox().inflate(0.8, 0.0, 0.8).expandTowards(0.0, 3.0, 0.0);
		net.minecraft.network.chat.Component best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : level.getEntities(living, box)) {
			net.minecraft.network.chat.Component text = null;
			if (e instanceof net.minecraft.world.entity.Display.TextDisplay td) {
				text = ((de.loothub.client.mixin.TextDisplayAccessor) td).loothub$getText();
			} else if (e instanceof net.minecraft.world.entity.decoration.ArmorStand && e.hasCustomName()) {
				text = e.getCustomName();
			}
			if (text == null || text.getString().isBlank()) {
				continue;
			}
			double d = e.distanceToSqr(living);
			if (d < bestD) {
				bestD = d;
				best = text;
			}
		}
		return best;
	}

	/** Name aus dem Modell eines Server-Mobs: Item-Anzeigen bzw. Rüstungsständer-Köpfe, die mitreiten oder direkt dabei sind. */
	private static String modelName(LivingEntity living) {
		java.util.List<Entity> parts = new java.util.ArrayList<>();
		living.getIndirectPassengers().forEach(parts::add);
		if (living.getVehicle() != null) {
			parts.add(living.getVehicle());
		}
		parts.addAll(living.level().getEntities(living, living.getBoundingBox().inflate(1.5, 0.5, 1.5).expandTowards(0.0, 2.5, 0.0)));
		// ein Modell besteht aus vielen Teilen (Kopf, Arme, Beine …) – der Name, der am häufigsten vorkommt, gewinnt;
		// einzelne Effekte daneben (z. B. Feuer) fallen so heraus
		java.util.Map<String, Integer> votes = new java.util.HashMap<>();
		java.util.Set<Entity> seen = new java.util.HashSet<>();
		for (Entity e : parts) {
			if (!seen.add(e)) {
				continue;
			}
			ItemStack stack = ItemStack.EMPTY;
			if (e instanceof net.minecraft.world.entity.Display.ItemDisplay idp) {
				stack = ((de.loothub.client.mixin.ItemDisplayAccessor) idp).loothub$getItemStack();
			} else if (e instanceof net.minecraft.world.entity.decoration.ArmorStand as) {
				stack = as.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
			}
			String n = itemModelName(stack);
			if (n != null) {
				votes.merge(n, 1, Integer::sum);
			}
		}
		return votes.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey)
				.orElse(null);
	}

	/** Englische Modellnamen bekannter OPSUCHT-Mobs → deutscher Name (sonst wird der Modellname schön geschrieben). */
	private static final java.util.Map<String, String> GERMAN = java.util.Map.of(
			"mole", "Maulwurf", "cassowary", "Kasuar", "kasuar", "Kasuar");

	/** Eigenes Modell eines Items als lesbarer Name (eigener Item-Name hat Vorrang), sonst null. */
	private static String itemModelName(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		if (custom != null && !custom.getString().isBlank()) {
			return net.minecraft.ChatFormatting.stripFormatting(custom.getString()).strip();
		}
		var model = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
		if (model == null || "minecraft".equals(model.getNamespace())) {
			return null;
		}
		// ModelEngine & Co.: „modelengine:mole/molehead“ – vorn steht das Modell (mole), hinten nur das Körperteil.
		// Allgemeine Ordner (mobs/, entity/ …) überspringen; interne Effekte (internal_fire) zählen nicht.
		String path = null;
		for (String seg : model.getPath().split("/")) {
			if (!seg.isEmpty() && !seg.matches("mobs?|entity|entities|models?|items?|custom|creatures?")) {
				path = seg;
				break;
			}
		}
		if (path == null || path.startsWith("internal")) {
			return null;
		}
		path = path.replaceAll("(_|-)(body|head|model|base|main|idle|walk|anim|entity|mob)$", "")
				.replaceAll("[_\\-]+", " ").replaceAll("\\d+", " ").replaceAll("\\s+", " ").strip();
		if (path.length() < 2) {
			return null;
		}
		String known = GERMAN.get(path.toLowerCase(java.util.Locale.ROOT));
		if (known != null) {
			return known;
		}
		StringBuilder out = new StringBuilder();
		for (String w : path.split(" ")) {
			out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return out.toString();
	}

	private static String killKey(Target t) {
		return t.name() != null ? "killed.~" + t.name() : "killed." + BuiltInRegistries.ENTITY_TYPE.getKey(t.type());
	}

	// ------------------------------------------------------------------ Jeder Tick

	public static void tick(Minecraft mc) {
		if (!tracking(mc)) {
			reset();
			return;
		}
		LocalPlayer p = mc.player;
		long now = System.currentTimeMillis();

		// Spielzeit
		if (lastTickTime > 0) {
			StatsStore.add("general.play_time", Math.min(now - lastTickTime, 1000));
		}
		lastTickTime = now;

		watchMining(mc, now);
		trackMovement(mc, p);
		trackHealth(p);
		trackEating(p);
		trackTargets(mc, now);

		StatsStore.saveIfDirty(false);
	}

	// ---- Abbauen, das der Server selbst erledigt (Spawner, verstärkter Tiefenschiefer, Spezial-Werkzeuge):
	// Minecraft meldet dafür kein „Block abgebaut“. Deshalb den anvisierten Block beobachten – verschwindet er
	// kurz nach dem Abbauen, zählt er auch.
	private static net.minecraft.core.BlockPos watchPos, lastCountedPos;
	private static BlockState watchState;
	private static long watchUntil, lastCountedAt;

	private static void watchMining(Minecraft mc, long now) {
		checkArea(mc, now);
		if (mc.options.keyAttack.isDown() && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr
				&& mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			BlockState st = mc.level.getBlockState(bhr.getBlockPos());
			if (!st.isAir()) {
				watchPos = bhr.getBlockPos().immutable();
				watchState = st;
				watchUntil = now + 1500;
				snapshotArea(mc, watchPos);
			}
		}
		if (watchPos == null) {
			return;
		}
		BlockState now0 = mc.level.getBlockState(watchPos);
		if (now0.getBlock() != watchState.getBlock()) {
			if ((now0.isAir() || now0.canBeReplaced()) && !recentlyCounted(watchPos, now)) {
				markCounted(watchPos, now);
				onBlockBroken(watchState);
				startAreaCheck(watchPos, now);
			}
			watchPos = null;
		} else if (now > watchUntil) {
			watchPos = null;
		}
	}

	// ---- Bohrer & Co.: bauen mehrere Blöcke um den anvisierten herum auf einmal ab (das erledigt der Server).
	// Vor dem Abbauen wird die Umgebung (3×3×3, beim Bohrer V3 5×5×5) gemerkt; verschwinden dort kurz nach dem Hauptblock weitere
	// Blöcke, zählen sie mit. Nur mit Spezial-Werkzeugen (eigener Name/eigenes Modell), damit z. B. fallender
	// Sand oder Wasser nicht mitgezählt werden.
	private static final java.util.Map<net.minecraft.core.BlockPos, BlockState> AREA = new java.util.HashMap<>();
	private static net.minecraft.core.BlockPos areaCenter;
	/** Blöcke um gerade abgebaute Hauptblöcke, die noch verschwinden dürfen – je mit eigener Frist. */
	private static final java.util.Map<net.minecraft.core.BlockPos, BlockState> pendingArea = new java.util.HashMap<>();
	private static final java.util.Map<net.minecraft.core.BlockPos, Long> pendingUntil = new java.util.HashMap<>();
	private static final java.util.Map<net.minecraft.core.BlockPos, Long> COUNTED = new java.util.HashMap<>();

	private static boolean isSpecialTool(ItemStack stack) {
		if (!isTool(stack)) {
			return false;
		}
		return stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME) != null
				|| stack.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA) != null
				// eigenes Modell (neuere Server setzen das Item-Modell statt Modell-Daten)
				|| !java.util.Objects.equals(stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL),
						stack.getItem().components().get(net.minecraft.core.component.DataComponents.ITEM_MODEL));
	}

	private static void snapshotArea(Minecraft mc, net.minecraft.core.BlockPos center) {
		if (!isSpecialTool(mc.player.getMainHandItem())) {
			AREA.clear();
			areaCenter = null;
			return;
		}
		if (!center.equals(areaCenter)) {
			AREA.clear();
			areaCenter = center;
		}
		int r = areaRadius(mc.player.getMainHandItem());
		// Bohrer bauen eine Fläche quer zur angeklickten Seite ab (3×3 bzw. 5×5), nicht in die Tiefe
		net.minecraft.core.Direction.Axis axis = mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr
				&& bhr.getBlockPos().equals(center) ? bhr.getDirection().getAxis() : null;
		int rx = axis == net.minecraft.core.Direction.Axis.X ? 0 : r;
		int ry = axis == net.minecraft.core.Direction.Axis.Y ? 0 : r;
		int rz = axis == net.minecraft.core.Direction.Axis.Z ? 0 : r;
		for (int x = -rx; x <= rx; x++) {
			for (int y = -ry; y <= ry; y++) {
				for (int z = -rz; z <= rz; z++) {
					if (x == 0 && y == 0 && z == 0) {
						continue;
					}
					net.minecraft.core.BlockPos p = center.offset(x, y, z);
					BlockState st = mc.level.getBlockState(p);
					if (isRealBlock(st.getBlock()) || isPlant(st.getBlock())) {
						AREA.put(p, st);
					} else {
						AREA.remove(p);
					}
				}
			}
		}
	}

	/**
	 * Reichweite um den anvisierten Block: Bohrer V3 baut 5×5 ab (2 Blöcke), Bohrer, Bohrer V2 und Tunnelgräberschaufel
	 * 3×3 (1 Block). Unbekannte Spezial-Werkzeuge: 3×3.
	 */
	private static int areaRadius(ItemStack tool) {
		var name = tool.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		String n = name == null ? "" : net.minecraft.ChatFormatting.stripFormatting(name.getString()).toLowerCase(java.util.Locale.ROOT);
		return n.contains("bohrer") && n.contains("v3") ? 2 : 1;
	}

	private static void startAreaCheck(net.minecraft.core.BlockPos center, long now) {
		Minecraft mc = Minecraft.getInstance();
		if (!center.equals(areaCenter) && mc.player != null && mc.level != null) {
			// sofort abgebaut (Bohrer baut ohne Wartezeit ab): Umgebung jetzt merken – die Nachbarn baut der Server
			// erst gleich danach ab, sie stehen also noch
			snapshotArea(mc, center);
		}
		if (center.equals(areaCenter) && !AREA.isEmpty()) {
			// zu den noch offenen dazunehmen, nicht ersetzen – beim schnellen Bohren überlappen sich die Bereiche
			for (var e : AREA.entrySet()) {
				if (!recentlyCounted(e.getKey(), now)) {
					pendingArea.put(e.getKey(), e.getValue());
					pendingUntil.put(e.getKey(), now + 1500L); // Server kann bei Last etwas nachhängen
				}
			}
		}
		AREA.clear();
		areaCenter = null;
	}

	private static void checkArea(Minecraft mc, long now) {
		if (pendingArea.isEmpty()) {
			return;
		}
		var it = pendingArea.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			BlockState cur = mc.level.getBlockState(e.getKey());
			if (cur.getBlock() != e.getValue().getBlock()) {
				if ((cur.isAir() || cur.canBeReplaced()) && !recentlyCounted(e.getKey(), now)) {
					markCounted(e.getKey(), now);
					onBlockBroken(e.getValue());
				}
				it.remove();
				pendingUntil.remove(e.getKey());
			} else if (now > pendingUntil.getOrDefault(e.getKey(), 0L)) {
				it.remove();
				pendingUntil.remove(e.getKey());
			}
		}
	}

	private static boolean recentlyCounted(net.minecraft.core.BlockPos p, long now) {
		Long t = COUNTED.get(p);
		return t != null && now - t < 2000;
	}

	private static void markCounted(net.minecraft.core.BlockPos p, long now) {
		COUNTED.put(p, now);
		if (COUNTED.size() > 256) {
			COUNTED.values().removeIf(t -> now - t > 2000);
		}
	}

	private static void trackMovement(Minecraft mc, LocalPlayer p) {
		Vec3 pos = p.position();
		boolean onGround = p.onGround();
		if (lastPos != null && !p.isDeadOrDying()) {
			double dx = pos.x - lastPos.x;
			double dy = pos.y - lastPos.y;
			double dz = pos.z - lastPos.z;
			double horizontal = Math.sqrt(dx * dx + dz * dz);
			double full = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (full > 0 && full < MAX_STEP) {
				if (p.isPassenger()) {
					add("general.ride", full);
				} else if (p.isSwimming() || p.isUnderWater() || p.isInWater()) {
					add("general.swim", full);
				} else if (p.isFallFlying()) {
					add("general.elytra", full);
				} else if (p.getAbilities().flying) {
					add("general.fly", full);
				} else if (p.onClimbable() && dy > 0) {
					add("general.climb", dy);
				} else if (p.isSprinting()) {
					add("general.sprint", horizontal);
				} else if (p.isCrouching()) {
					add("general.crouch", horizontal);
				} else {
					add("general.walk", horizontal);
				}
			}
			// Sprung: gerade noch am Boden, jetzt in der Luft und nach oben, Sprungtaste gedrückt
			if (lastOnGround && !onGround && p.getDeltaMovement().y > 0.1 && mc.options.keyJump.isDown()
					&& !p.isPassenger() && !p.isInWater()) {
				StatsStore.add("general.jumps", 1);
			}
		}
		lastPos = pos;
		lastOnGround = onGround;
	}

	private static void trackHealth(LocalPlayer p) {
		boolean dead = p.isDeadOrDying();
		float hp = p.getHealth() + p.getAbsorptionAmount();
		if (dead && !lastDead) {
			StatsStore.add("general.deaths", 1);
		}
		if (lastHp >= 0 && hp < lastHp) {
			StatsStore.add("general.damage_taken", Math.round((lastHp - hp) * 10));
		}
		lastDead = dead;
		lastHp = hp;
	}

	/** Essen: Wenn das Benutzen eines Nahrungsmittels kurz vor Schluss endet, wurde es gegessen. */
	private static void trackEating(LocalPlayer p) {
		if (p.isUsingItem()) {
			ItemStack use = p.getUseItem();
			if (use.get(DataComponents.FOOD) != null) {
				eatingItem = BuiltInRegistries.ITEM.getKey(use.getItem()).toString();
				eatingRemaining = p.getUseItemRemainingTicks();
				return;
			}
		}
		if (eatingItem != null && eatingRemaining <= 2) {
			StatsStore.add("eaten." + eatingItem, 1);
		}
		eatingItem = null;
	}

	/** Getroffene Lebewesen beobachten: Lebensverlust = ausgeteilter Schaden, Tod kurz nach Treffer = Kill. */
	private static void trackTargets(Minecraft mc, long now) {
		Iterator<Map.Entry<Integer, Target>> it = TARGETS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Target> e = it.next();
			Target t = e.getValue();
			Entity entity = mc.level.getEntity(e.getKey());
			if (!(entity instanceof LivingEntity living)) {
				// Server-Mobs verschwinden beim Tod oft sofort, ohne Sterbe-Animation: verschwindet ein Mob direkt nach
				// einem Treffer, zählt das als Kill
				if (now - t.lastHit() < 1_500L) {
					StatsStore.add(killKey(t), 1);
				}
				it.remove();
				continue;
			}
			float health = living.getHealth();
			if (health < t.lastHealth()) {
				StatsStore.add("general.damage_dealt", Math.round((t.lastHealth() - health) * 10));
			}
			if (living.isDeadOrDying()) {
				StatsStore.add(killKey(t), 1);
				it.remove();
			} else if (now - t.lastHit() > KILL_WINDOW_MS) {
				it.remove();
			} else {
				String n = customName(living);
				e.setValue(new Target(t.type(), n != null ? n : t.name(), health, t.lastHit()));
			}
		}
	}

	private static void add(String key, double blocks) {
		long cm = Math.round(blocks * 100);
		if (cm > 0) {
			StatsStore.add(key, cm);
		}
	}

	private static void reset() {
		lastPos = null;
		lastOnGround = true;
		lastDead = false;
		lastHp = -1;
		lastTickTime = 0;
		eatingItem = null;
		TARGETS.clear();
	}
}
