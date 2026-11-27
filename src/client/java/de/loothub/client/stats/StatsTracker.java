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
	private static final long KILL_WINDOW_MS = 20_000;
	/** Verschwindet ein getroffener Mob so lange nach dem letzten Treffer (Sterbe-Animation von Server-Modellen), zählt es. */
	private static final long VANISH_KILL_MS = 15_000;

	/** {@code name}: Name eines Server-Mobs (eigener Name), sonst null → Zählung nach Mob-Art. */
	private record Target(EntityType<?> type, String name, float lastHealth, long lastHit, double x, double y, double z) {
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

	/** Weggeworfene Items zählen (Q in der Welt, Q im Inventar, aus dem Fenster ziehen). */
	public static void onDropped(net.minecraft.world.item.ItemStack stack, int amount) {
		Minecraft mc = Minecraft.getInstance();
		if (!tracking(mc) || stack == null || stack.isEmpty() || amount <= 0) {
			return;
		}
		int n = Math.min(amount, stack.getCount());
		StatsStore.add("dropped." + BuiltInRegistries.ITEM.getKey(stack.getItem()), n);
		// Weggeworfenes merken: das Item, das gleich neben einem auftaucht, ist das eigene (siehe trackOwnDrops)
		PENDING_DROPS.add(new Object[]{pickupKey(stack), System.currentTimeMillis()});
	}

	/** Eben weggeworfen: {Schlüssel, Zeitpunkt} – wartet darauf, dass das Item in der Welt erscheint. */
	private static final java.util.List<Object[]> PENDING_DROPS = new java.util.ArrayList<>();
	/** Item-Objekte in der Welt, die man selbst weggeworfen hat: Aufheben zählt nicht noch einmal. */
	private static final java.util.Set<Integer> OWN_DROPS = new java.util.HashSet<>();

	/** Zähl-Schlüssel eines Items wie beim Aufsammeln (eigener Name hat Vorrang). */
	private static String pickupKey(ItemStack stack) {
		var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		String name = custom == null ? null : net.minecraft.ChatFormatting.stripFormatting(custom.getString());
		if (name != null && !(name = name.strip()).isEmpty()) {
			return "~" + (name.length() > 40 ? name.substring(0, 40).strip() : name);
		}
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	/** Frisch neben einem erschienene Items einem eben weggeworfenen Item zuordnen (genau dieses Objekt merken). */
	private static void trackOwnDrops(Minecraft mc, long now) {
		PENDING_DROPS.removeIf(d -> now - (long) d[1] > 1500);
		if (mc.level == null || mc.player == null) {
			return;
		}
		if (!PENDING_DROPS.isEmpty()) {
			var box = mc.player.getBoundingBox().inflate(3.0);
			for (var it : mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
				if (it.tickCount > 20 || OWN_DROPS.contains(it.getId())) {
					continue;
				}
				String key = pickupKey(it.getItem());
				for (java.util.Iterator<Object[]> i = PENDING_DROPS.iterator(); i.hasNext();) {
					if (key.equals(i.next()[0])) {
						OWN_DROPS.add(it.getId());
						i.remove();
						break;
					}
				}
			}
		}
		// Objekte, die es nicht mehr gibt, vergessen
		if (!OWN_DROPS.isEmpty() && mc.player.tickCount % 100 == 0) {
			OWN_DROPS.removeIf(id -> mc.level.getEntity(id) == null);
		}
	}

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
		startTreeCheck(p, state, now);
	}

	/** One-Click-Äxte von OPSUCHT (Name enthält einen dieser Begriffe): fällen den ganzen Baum auf einmal. */
	private static final java.util.List<String> TREE_TOOLS = java.util.List.of("gamechanger", "timber", "wurzelbrecher",
			"seelenfresser", "runenbrecher");
	private static final int TREE_MAX = 256;

	private static boolean isTreeTool(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		var name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
		StringBuilder text = new StringBuilder(name == null ? "" : name.getString());
		var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		if (lore != null) {
			for (var line : lore.lines()) {
				text.append(' ').append(line.getString()); // z. B. Verzauberung „Timber“ im Tooltip
			}
		}
		String t = text.toString().toLowerCase(java.util.Locale.ROOT);
		for (String k : TREE_TOOLS) {
			if (t.contains(k)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Baum fällen: Wird ein Stamm mit einer One-Click-Axt abgebaut, alle damit verbundenen Stämme merken (bis 256).
	 * Verschwinden sie in den nächsten Sekunden, zählen sie einzeln als abgebaut – Blätter zählen nicht.
	 */
	private static void startTreeCheck(net.minecraft.core.BlockPos start, BlockState state, long now) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !state.is(net.minecraft.tags.BlockTags.LOGS)
				|| !isTreeTool(mc.player.getMainHandItem())) {
			return;
		}
		java.util.ArrayDeque<net.minecraft.core.BlockPos> queue = new java.util.ArrayDeque<>();
		java.util.Set<net.minecraft.core.BlockPos> seen = new java.util.HashSet<>();
		queue.add(start);
		seen.add(start);
		int found = 0;
		while (!queue.isEmpty() && found < TREE_MAX) {
			net.minecraft.core.BlockPos c = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						net.minecraft.core.BlockPos n = c.offset(dx, dy, dz);
						if (!seen.add(n) || Math.abs(n.getX() - start.getX()) > 12 || Math.abs(n.getZ() - start.getZ()) > 12
								|| n.getY() < start.getY() - 2 || n.getY() > start.getY() + 40) {
							continue;
						}
						BlockState st = mc.level.getBlockState(n);
						if (st.is(net.minecraft.tags.BlockTags.LOGS) && !recentlyCounted(n, now)) {
							pendingArea.put(n.immutable(), st);
							pendingUntil.put(n.immutable(), now + 4000L); // der Server fällt große Bäume nach und nach
							queue.add(n);
							if (++found >= TREE_MAX) {
								break;
							}
						}
					}
				}
			}
		}
	}

	public static void onBlockBroken(BlockState state) {
		Minecraft mc = Minecraft.getInstance();
		if (!tracking(mc)) {
			return;
		}
		if (isRealBlock(state.getBlock()) || isPlant(state.getBlock())) {
			StatsStore.add("mined." + BuiltInRegistries.BLOCK.getKey(state.getBlock()), 1);
		}
		lastMinedAt = System.currentTimeMillis();
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
		onPickup(stack, amount, null);
	}

	public static void onPickup(ItemStack stack, int amount, net.minecraft.world.entity.item.ItemEntity entity) {
		pickupEntity = entity;
		if (!tracking(Minecraft.getInstance()) || stack.isEmpty()) {
			return;
		}
		// Server-Items mit eigenem Namen (z. B. „Kasuarhorn“ als Mob-Drop) unter ihrem Namen zählen
		String item = pickupKey(stack);
		int n = Math.max(1, amount);
		// Aufgesammeltes erklärt Inventar-Zugänge (Magnet-Erkennung soll es nicht noch einmal zählen)
		long[] ex = PICKED_RECENT.computeIfAbsent(item, k -> new long[2]);
		ex[0] += n;
		ex[1] = System.currentTimeMillis();
		if (entity != null && OWN_DROPS.remove(entity.getId())) {
			pickupEntity = null;
			return; // selbst weggeworfen und wieder aufgehoben: zählt nicht noch einmal
		}
		StatsStore.add("picked." + item, n);
		countLoot(item, n);
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

	/** Zeitpunkt des letzten eigenen Schlags (für Flächenschaden: Schwungangriff, Flächen-Waffen). */
	private static long lastSwingAt;
	/** So lange nach einem Schlag zählen auch Mobs, die nebenbei Schaden nehmen. */
	private static final long AREA_HIT_MS = 400;
	/** Reichweite: normaler Schwungangriff nur direkt um das Ziel, Spezialwaffen (eigener Name/Modell) weiter. */
	private static final double SWEEP_RANGE = 3.5, AREA_RANGE = 8.0;
	private static net.minecraft.world.phys.Vec3 lastHitAt;

	/** Treffer auf ein Lebewesen merken: für ausgeteilten Schaden und Kills. */
	public static void onAttack(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		lastSwingAt = System.currentTimeMillis();
		lastHitAt = entity.position();
		if (tracking(mc)) {
			toolStat("tool_hits.", mc.player.getMainHandItem());
		}
		LivingEntity living = livingOf(entity);
		if (living != null && tracking(mc) && !TARGETS.containsKey(living.getId())) {
			describe(entity, living);
		} else if (living == null && tracking(mc)) {
			// kein Lebewesen zu sehen (der Server schickt bei manchen Modell-Mobs nur Modell und Trefferfläche): dann ist
			// das Modell selbst der Mob – beobachtet wird das Teil, auf dem die Trefferfläche sitzt
			Entity root = entity.getRootVehicle();
			String model = modelName(entity, Math.max(3.0, entity.getBbWidth() + 1.0)); // große Mobs (Elefant): weiter suchen
			if (model != null) {
				if (!TARGETS.containsKey(root.getId())) {
					de.loothub.Loothub.LOGGER.info("[Loothub-Mobinfo] getroffen: {} | nur Modell, gezählt als: {}", part(entity), model);
				}
				TARGETS.put(root.getId(), new Target(root.getType(), model, 0F, System.currentTimeMillis(), root.getX(),
						root.getY(), root.getZ()));
			} else {
				StringBuilder b = new StringBuilder("[Loothub-Mobinfo] getroffen, aber kein Mob gefunden: ").append(part(entity));
				for (Entity n : entity.level().getEntities(entity, entity.getBoundingBox().inflate(4.0))) {
					if (!(n instanceof net.minecraft.world.entity.player.Player)) {
						b.append("\n    daneben: ").append(part(n));
					}
				}
				de.loothub.Loothub.LOGGER.info(b.toString());
			}
		}
		if (living != null && tracking(mc)) {
			TARGETS.put(living.getId(), new Target(living.getType(), customName(living), living.getHealth(),
					System.currentTimeMillis(), living.getX(), living.getY(), living.getZ()));
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
		double radius = Math.max(3.5, entity.getBbWidth() + 1.0); // große Trefferfläche (Elefant …): Träger kann weiter weg sein
		double best = (radius + 0.5) * (radius + 0.5);
		for (Entity e : entity.level().getEntities(entity, entity.getBoundingBox().inflate(radius))) {
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
			if (model == null) {
				model = modelName(living, 4.5); // große Modelle (Elefant): Teile sitzen weiter vom Träger weg
			}
			if (model != null) {
				return model;
			}
			// unsichtbarer Träger (z. B. Fledermaus als Trefferfläche) ohne lesbaren Namen: nicht als echtes Tier zählen
			return living.isInvisible() ? "Server-Mob (" + GermanLang.entity(living.getType()) + ")" : null;
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
		String german = germanName(n, true); // englisches Namensschild („Rabbit Black“) eindeutschen
		if (german != null) {
			n = german;
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
	private static String modelName(Entity living) {
		return modelName(living, 1.5);
	}

	private static String modelName(Entity living, double reach) {
		java.util.List<Entity> parts = new java.util.ArrayList<>();
		living.getIndirectPassengers().forEach(parts::add);
		if (living.getVehicle() != null) {
			parts.add(living.getVehicle());
		}
		parts.addAll(living.level().getEntities(living, living.getBoundingBox().inflate(reach, 0.5, reach).expandTowards(0.0, 1.0 + reach, 0.0)));
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

	/** Englische Tiernamen aus Modellnamen → deutsch (sonst wird der Modellname schön geschrieben). */
	private static final java.util.Map<String, String> GERMAN = java.util.Map.ofEntries(
			java.util.Map.entry("mole", "Maulwurf"),
			java.util.Map.entry("cassowary", "Kasuar"),
			java.util.Map.entry("kasuar", "Kasuar"),
			java.util.Map.entry("deer", "Rentier"),
			java.util.Map.entry("reindeer", "Rentier"),
			java.util.Map.entry("stag", "Hirsch"),
			java.util.Map.entry("roe deer", "Reh"),
			java.util.Map.entry("boar", "Eber"),
			java.util.Map.entry("wild boar", "Wildschwein"),
			java.util.Map.entry("shark hammerhead", "Hammerhai"),
			java.util.Map.entry("hammerhead shark", "Hammerhai"),
			java.util.Map.entry("hammerhead", "Hammerhai"),
			java.util.Map.entry("shark", "Hai"),
			java.util.Map.entry("white shark", "Weißer Hai"),
			java.util.Map.entry("great white shark", "Weißer Hai"),
			java.util.Map.entry("seahorse", "Seepferdchen"),
			java.util.Map.entry("sea horse", "Seepferdchen"),
			java.util.Map.entry("penguin", "Pinguin"),
			java.util.Map.entry("rabbit", "Kaninchen"),
			java.util.Map.entry("bunny", "Kaninchen"),
			java.util.Map.entry("hare", "Hase"),
			java.util.Map.entry("crab", "Krabbe"),
			java.util.Map.entry("lobster", "Hummer"),
			java.util.Map.entry("shrimp", "Garnele"),
			java.util.Map.entry("turtle", "Schildkröte"),
			java.util.Map.entry("tortoise", "Schildkröte"),
			java.util.Map.entry("sea turtle", "Meeresschildkröte"),
			java.util.Map.entry("fox", "Fuchs"),
			java.util.Map.entry("arctic fox", "Polarfuchs"),
			java.util.Map.entry("wolf", "Wolf"),
			java.util.Map.entry("bear", "Bär"),
			java.util.Map.entry("polar bear", "Eisbär"),
			java.util.Map.entry("brown bear", "Braunbär"),
			java.util.Map.entry("grizzly", "Grizzlybär"),
			java.util.Map.entry("panda", "Panda"),
			java.util.Map.entry("red panda", "Roter Panda"),
			java.util.Map.entry("owl", "Eule"),
			java.util.Map.entry("eagle", "Adler"),
			java.util.Map.entry("hawk", "Falke"),
			java.util.Map.entry("falcon", "Falke"),
			java.util.Map.entry("parrot", "Papagei"),
			java.util.Map.entry("seagull", "Möwe"),
			java.util.Map.entry("gull", "Möwe"),
			java.util.Map.entry("duck", "Ente"),
			java.util.Map.entry("goose", "Gans"),
			java.util.Map.entry("swan", "Schwan"),
			java.util.Map.entry("crow", "Krähe"),
			java.util.Map.entry("raven", "Rabe"),
			java.util.Map.entry("pigeon", "Taube"),
			java.util.Map.entry("dove", "Taube"),
			java.util.Map.entry("flamingo", "Flamingo"),
			java.util.Map.entry("peacock", "Pfau"),
			java.util.Map.entry("pelican", "Pelikan"),
			java.util.Map.entry("toucan", "Tukan"),
			java.util.Map.entry("ostrich", "Strauß"),
			java.util.Map.entry("emu", "Emu"),
			java.util.Map.entry("vulture", "Geier"),
			java.util.Map.entry("stork", "Storch"),
			java.util.Map.entry("snake", "Schlange"),
			java.util.Map.entry("cobra", "Kobra"),
			java.util.Map.entry("python", "Python"),
			java.util.Map.entry("lizard", "Eidechse"),
			java.util.Map.entry("gecko", "Gecko"),
			java.util.Map.entry("chameleon", "Chamäleon"),
			java.util.Map.entry("iguana", "Leguan"),
			java.util.Map.entry("crocodile", "Krokodil"),
			java.util.Map.entry("alligator", "Alligator"),
			java.util.Map.entry("frog", "Frosch"),
			java.util.Map.entry("toad", "Kröte"),
			java.util.Map.entry("monkey", "Affe"),
			java.util.Map.entry("gorilla", "Gorilla"),
			java.util.Map.entry("chimpanzee", "Schimpanse"),
			java.util.Map.entry("orangutan", "Orang-Utan"),
			java.util.Map.entry("sloth", "Faultier"),
			java.util.Map.entry("koala", "Koala"),
			java.util.Map.entry("kangaroo", "Känguru"),
			java.util.Map.entry("elephant", "Elefant"),
			java.util.Map.entry("mammoth", "Mammut"),
			java.util.Map.entry("giraffe", "Giraffe"),
			java.util.Map.entry("zebra", "Zebra"),
			java.util.Map.entry("hippo", "Nilpferd"),
			java.util.Map.entry("hippopotamus", "Nilpferd"),
			java.util.Map.entry("rhino", "Nashorn"),
			java.util.Map.entry("rhinoceros", "Nashorn"),
			java.util.Map.entry("lion", "Löwe"),
			java.util.Map.entry("lioness", "Löwin"),
			java.util.Map.entry("lionness", "Löwin"),
			java.util.Map.entry("budgie", "Wellensittich"),
			java.util.Map.entry("budgerigar", "Wellensittich"),
			java.util.Map.entry("parakeet", "Sittich"),
			java.util.Map.entry("cockatoo", "Kakadu"),
			java.util.Map.entry("cockatiel", "Nymphensittich"),
			java.util.Map.entry("tiger", "Tiger"),
			java.util.Map.entry("leopard", "Leopard"),
			java.util.Map.entry("snow leopard", "Schneeleopard"),
			java.util.Map.entry("panther", "Panther"),
			java.util.Map.entry("jaguar", "Jaguar"),
			java.util.Map.entry("cheetah", "Gepard"),
			java.util.Map.entry("lynx", "Luchs"),
			java.util.Map.entry("hyena", "Hyäne"),
			java.util.Map.entry("jackal", "Schakal"),
			java.util.Map.entry("buffalo", "Büffel"),
			java.util.Map.entry("bison", "Bison"),
			java.util.Map.entry("yak", "Yak"),
			java.util.Map.entry("camel", "Kamel"),
			java.util.Map.entry("alpaca", "Alpaka"),
			java.util.Map.entry("llama", "Lama"),
			java.util.Map.entry("moose", "Elch"),
			java.util.Map.entry("elk", "Elch"),
			java.util.Map.entry("antelope", "Antilope"),
			java.util.Map.entry("gazelle", "Gazelle"),
			java.util.Map.entry("whale", "Wal"),
			java.util.Map.entry("orca", "Orca"),
			java.util.Map.entry("killer whale", "Orca"),
			java.util.Map.entry("narwhal", "Narwal"),
			java.util.Map.entry("dolphin", "Delfin"),
			java.util.Map.entry("seal", "Robbe"),
			java.util.Map.entry("walrus", "Walross"),
			java.util.Map.entry("otter", "Otter"),
			java.util.Map.entry("beaver", "Biber"),
			java.util.Map.entry("jellyfish", "Qualle"),
			java.util.Map.entry("octopus", "Oktopus"),
			java.util.Map.entry("squid", "Tintenfisch"),
			java.util.Map.entry("starfish", "Seestern"),
			java.util.Map.entry("clownfish", "Clownfisch"),
			java.util.Map.entry("pufferfish", "Kugelfisch"),
			java.util.Map.entry("stingray", "Rochen"),
			java.util.Map.entry("manta", "Mantarochen"),
			java.util.Map.entry("manta ray", "Mantarochen"),
			java.util.Map.entry("eel", "Aal"),
			java.util.Map.entry("piranha", "Piranha"),
			java.util.Map.entry("anglerfish", "Anglerfisch"),
			java.util.Map.entry("swordfish", "Schwertfisch"),
			java.util.Map.entry("tuna", "Thunfisch"),
			java.util.Map.entry("salmon", "Lachs"),
			java.util.Map.entry("cod", "Kabeljau"),
			java.util.Map.entry("fish", "Fisch"),
			java.util.Map.entry("squirrel", "Eichhörnchen"),
			java.util.Map.entry("hedgehog", "Igel"),
			java.util.Map.entry("raccoon", "Waschbär"),
			java.util.Map.entry("badger", "Dachs"),
			java.util.Map.entry("skunk", "Stinktier"),
			java.util.Map.entry("mouse", "Maus"),
			java.util.Map.entry("rat", "Ratte"),
			java.util.Map.entry("hamster", "Hamster"),
			java.util.Map.entry("bat", "Fledermaus"),
			java.util.Map.entry("butterfly", "Schmetterling"),
			java.util.Map.entry("bee", "Biene"),
			java.util.Map.entry("wasp", "Wespe"),
			java.util.Map.entry("ant", "Ameise"),
			java.util.Map.entry("beetle", "Käfer"),
			java.util.Map.entry("scarab", "Skarabäus"),
			java.util.Map.entry("ladybug", "Marienkäfer"),
			java.util.Map.entry("dragonfly", "Libelle"),
			java.util.Map.entry("snail", "Schnecke"),
			java.util.Map.entry("worm", "Wurm"),
			java.util.Map.entry("scorpion", "Skorpion"),
			java.util.Map.entry("spider", "Spinne"),
			java.util.Map.entry("tarantula", "Vogelspinne"),
			java.util.Map.entry("cow", "Kuh"),
			java.util.Map.entry("bull", "Stier"),
			java.util.Map.entry("pig", "Schwein"),
			java.util.Map.entry("sheep", "Schaf"),
			java.util.Map.entry("goat", "Ziege"),
			java.util.Map.entry("chicken", "Huhn"),
			java.util.Map.entry("horse", "Pferd"),
			java.util.Map.entry("donkey", "Esel"),
			java.util.Map.entry("cat", "Katze"),
			java.util.Map.entry("dog", "Hund"),
			java.util.Map.entry("dragon", "Drache"),
			java.util.Map.entry("baby dragon", "Babydrache"),
			java.util.Map.entry("dino", "Dino"),
			java.util.Map.entry("dinosaur", "Dinosaurier"),
			java.util.Map.entry("raptor", "Raptor"),
			java.util.Map.entry("trex", "T-Rex"),
			java.util.Map.entry("t rex", "T-Rex"),
			java.util.Map.entry("yeti", "Yeti"),
			java.util.Map.entry("troll", "Troll"),
			java.util.Map.entry("goblin", "Goblin"),
			java.util.Map.entry("orc", "Ork"),
			java.util.Map.entry("ogre", "Oger"),
			java.util.Map.entry("golem", "Golem"),
			java.util.Map.entry("ghost", "Geist"),
			java.util.Map.entry("mummy", "Mumie"),
			java.util.Map.entry("werewolf", "Werwolf"),
			java.util.Map.entry("vampire", "Vampir"),
			java.util.Map.entry("skeleton", "Skelett"),
			java.util.Map.entry("zombie", "Zombie"),
			java.util.Map.entry("slime", "Schleim"),
			java.util.Map.entry("witch", "Hexe"),
			java.util.Map.entry("wizard", "Zauberer"),
			java.util.Map.entry("knight", "Ritter"),
			java.util.Map.entry("pirate", "Pirat"),
			java.util.Map.entry("bandit", "Bandit"),
			java.util.Map.entry("snowman", "Schneemann"),
			java.util.Map.entry("gingerbread", "Lebkuchenmann"),
			java.util.Map.entry("gingerbread man", "Lebkuchenmann"),
			java.util.Map.entry("elf", "Elf"),
			java.util.Map.entry("santa", "Weihnachtsmann"),
			java.util.Map.entry("krampus", "Krampus"),
			java.util.Map.entry("reaper", "Sensenmann"),
			java.util.Map.entry("pumpkin", "Kürbis"),
			java.util.Map.entry("scarecrow", "Vogelscheuche"),
			java.util.Map.entry("mushroom", "Pilz"),
			java.util.Map.entry("treant", "Baumgeist"),
			java.util.Map.entry("ent", "Baumgeist"));
	private static final java.util.Map<String, String> COLORS = java.util.Map.ofEntries(
			java.util.Map.entry("black", "schwarz"), java.util.Map.entry("white", "weiß"),
			java.util.Map.entry("brown", "braun"), java.util.Map.entry("gray", "grau"), java.util.Map.entry("grey", "grau"),
			java.util.Map.entry("red", "rot"), java.util.Map.entry("golden", "golden"), java.util.Map.entry("gold", "golden"),
			java.util.Map.entry("blue", "blau"), java.util.Map.entry("green", "grün"), java.util.Map.entry("yellow", "gelb"),
			java.util.Map.entry("orange", "orange"), java.util.Map.entry("pink", "rosa"), java.util.Map.entry("purple", "lila"),
			java.util.Map.entry("violet", "violett"), java.util.Map.entry("skyblue", "himmelblau"),
			java.util.Map.entry("lightblue", "hellblau"), java.util.Map.entry("darkblue", "dunkelblau"),
			java.util.Map.entry("lightgreen", "hellgrün"), java.util.Map.entry("darkgreen", "dunkelgrün"),
			java.util.Map.entry("silver", "silbern"), java.util.Map.entry("albino", "albino"),
			java.util.Map.entry("spotted", "gefleckt"), java.util.Map.entry("striped", "gestreift"),
			java.util.Map.entry("spangle", "gesprenkelt"), java.util.Map.entry("spangled", "gesprenkelt"));

	/**
	 * Deutscher Name aus einem Modellnamen: Hersteller-Kürzel (nm_, nocsy_, pet_ …) weg, bekannte Tiere übersetzen, Farben
	 * dahinter in Klammern („rabbit black“ → „Kaninchen (schwarz)“). null = unbekannt.
	 */
	static String germanName(String words) {
		return germanName(words, false);
	}

	/** strict: nur übersetzen, wenn der ganze Name (ohne Farben/Kürzel) bekannt ist – für alte Statistik-Einträge. */
	static String germanName(String words, boolean strict) {
		String w = words.toLowerCase(java.util.Locale.ROOT)
				.replaceAll("\\b(nm|nocsy|mm|me|pet|pets|custom|op|opsucht|mob|mobs)\\b", " ")
				.replaceAll("\\b(sky|light|dark) (blue|green)\\b", "$1$2")
				.replaceAll("\\s+", " ").strip();
		if (GERMAN.containsKey(w)) {
			return GERMAN.get(w); // ganzer Name zuerst („red panda“ → Roter Panda, nicht „Panda (rot)“)
		}
		java.util.List<String> colors = new java.util.ArrayList<>();
		StringBuilder rest = new StringBuilder();
		for (String part : w.split(" ")) {
			if (COLORS.containsKey(part)) {
				colors.add(COLORS.get(part));
			} else if (!part.isEmpty()) {
				rest.append(rest.length() > 0 ? " " : "").append(part);
			}
		}
		String animal = GERMAN.get(rest.toString());
		if (animal == null && !strict) {
			// einzelnes bekanntes Wort im Namen (z. B. „baby shark“ → Hai)
			for (String part : rest.toString().split(" ")) {
				if (GERMAN.containsKey(part)) {
					animal = GERMAN.get(part);
				}
			}
		}
		if (animal == null) {
			return null;
		}
		return colors.isEmpty() ? animal : animal + " (" + String.join(", ", colors) + ")";
	}

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
		String german = germanName(path);
		if (german != null) {
			return german;
		}
		path = path.replaceAll("^((nm|nocsy|mm|me|pet|pets|custom|op|opsucht|mob|mobs) )+", "").strip();
		if (path.length() < 2) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (String w : path.split(" ")) {
			out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return out.toString();
	}

	/**
	 * Letzte Kills: Ein aufgesammeltes Item zählt als Loot des Mobs, an dessen Todesstelle es lag (höchstens 3 Blöcke
	 * entfernt) und der höchstens 20 s vorher gestorben ist – das Item muss außerdem nach dem Tod entstanden sein.
	 * So landet z. B. ein Kasuarhorn nicht beim Skelett, das zufällig zuletzt gestorben ist.
	 */
	private static final long LOOT_WINDOW_MS = 20_000;
	private record Kill(String mob, long at, double x, double y, double z) {
	}

	private static final java.util.ArrayDeque<Kill> RECENT_KILLS = new java.util.ArrayDeque<>();
	private static net.minecraft.world.entity.item.ItemEntity pickupEntity;

	private static void countKill(Target t) {
		String key = killKey(t);
		StatsStore.add(key, 1);
		RECENT_KILLS.addFirst(new Kill(key.substring("killed.".length()), System.currentTimeMillis(), t.x(), t.y(), t.z()));
		while (RECENT_KILLS.size() > 24) {
			RECENT_KILLS.removeLast();
		}
	}

	/** Beim Erscheinen zugeordnete Drops: Item-Objekt → Mob. So zählt Loot auch, wenn man ihn erst später aufsammelt. */
	private static final Map<Integer, String> LOOT_TAG = new HashMap<>();

	/** Frisch erschienene Items einem Mob zuordnen, der eben in der Nähe gestorben ist. */
	private static void tagDrops(Minecraft mc, long now) {
		if (RECENT_KILLS.isEmpty() || mc.level == null || mc.player == null) {
			return;
		}
		Kill newest = RECENT_KILLS.peekFirst();
		if (now - newest.at() > 3000) {
			return; // seit 3 s niemand gestorben: nichts zuzuordnen
		}
		var box = mc.player.getBoundingBox().inflate(48.0);
		for (var it : mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
			if (it.tickCount > 20 || LOOT_TAG.containsKey(it.getId()) || OWN_DROPS.contains(it.getId())) {
				continue;
			}
			long born = now - it.tickCount * 50L;
			Kill best = null;
			double bestDist = 5.0 * 5.0; // etwas großzügiger: Magnete ziehen Drops sofort weg
			for (Kill k : RECENT_KILLS) {
				if (born < k.at() - 1500 || born - k.at() > 3000) {
					continue;
				}
				double dx = it.getX() - k.x(), dz = it.getZ() - k.z(), dy = Math.max(0, Math.abs(it.getY() - k.y()) - 2.0);
				double d = dx * dx + dz * dz + dy * dy;
				if (d <= bestDist) {
					bestDist = d;
					best = k;
				}
			}
			if (best != null) {
				LOOT_TAG.put(it.getId(), best.mob());
			}
		}
		if (LOOT_TAG.size() > 500) {
			LOOT_TAG.keySet().removeIf(id -> mc.level.getEntity(id) == null);
		}
	}

	/** Über Aufsammel-Pakete erklärte Zugänge der letzten Sekunden: Schlüssel → {Menge, Zeitpunkt}. */
	private static final Map<String, long[]> PICKED_RECENT = new HashMap<>();
	private static Map<String, Long> lastInv;

	/**
	 * Magnete legen Drops oft direkt ins Inventar, ohne dass ein Item am Boden lag oder aufgesammelt wurde. Was kurz
	 * nach einem Kill ohne Aufsammeln im Inventar dazukommt, zählt deshalb als Loot dieses Mobs (nur ohne offenes Menü).
	 */
	/** Zuletzt einen Block abgebaut – Inventar-Zugänge kurz danach sind Drops (auch per Magnet/Direkt-ins-Inventar). */
	private static long lastMinedAt;

	private static void inventoryLoot(Minecraft mc, long now) {
		if (mc.player == null) {
			return;
		}
		Map<String, Long> inv = new HashMap<>();
		var pinv = mc.player.getInventory();
		for (int i = 0; i < pinv.getContainerSize(); i++) {
			ItemStack st = pinv.getItem(i);
			if (!st.isEmpty()) {
				inv.merge(pickupKey(st), (long) st.getCount(), Long::sum);
			}
		}
		Map<String, Long> before = lastInv;
		lastInv = inv;
		PICKED_RECENT.values().removeIf(x -> now - x[1] > 2000);
		boolean mining = now - lastMinedAt <= 2000;
		if (before == null || mc.screen != null || (RECENT_KILLS.isEmpty() && !mining)) {
			return;
		}
		Kill k = RECENT_KILLS.peekFirst();
		boolean fresh = k != null && now - k.at() <= 1500;
		for (Map.Entry<String, Long> e : inv.entrySet()) {
			long gain = e.getValue() - before.getOrDefault(e.getKey(), 0L);
			long[] ex = PICKED_RECENT.get(e.getKey());
			if (gain > 0 && ex != null) {
				long used = Math.min(gain, ex[0]); // schon über Aufsammeln gezählt
				ex[0] -= used;
				gain -= used;
			}
			if (gain > 0 && fresh) {
				StatsStore.add("mobloot." + k.mob(), gain);
				StatsStore.add("mobloot_item." + k.mob() + "|" + e.getKey(), gain);
				if (tracking(mc)) {
					// per Magnet direkt ins Inventar: zählt auch als aufgesammelt (z. B. Quest „Knochen sammeln“)
					StatsStore.add("picked." + e.getKey(), gain);
				}
			} else if (gain > 0 && mining && tracking(mc)) {
				// Abbau-Drops, die ohne Aufsammel-Meldung im Inventar landen (Magnet, Server legt direkt ins Inventar)
				StatsStore.add("picked." + e.getKey(), gain);
			}
		}
	}

	private static void countLoot(String item, int amount) {
		var entity = pickupEntity;
		pickupEntity = null;
		if (entity == null) {
			return; // ohne Item-Lage keine sichere Zuordnung
		}
		String tagged = LOOT_TAG.remove(entity.getId());
		if (tagged != null) {
			StatsStore.add("mobloot." + tagged, amount);
			StatsStore.add("mobloot_item." + tagged + "|" + item, amount);
			return;
		}
		long now = System.currentTimeMillis();
		long itemBorn = now - entity.tickCount * 50L;
		Kill best = null;
		double bestDist = 3.0 * 3.0;
		for (Kill k : RECENT_KILLS) {
			if (now - k.at() > LOOT_WINDOW_MS || itemBorn < k.at() - 1500) {
				continue; // zu alt, oder das Item lag schon vor dem Tod da
			}
			// Drops fallen von der Todesstelle herunter: waagerecht nah, senkrecht etwas Spielraum
			double dx = entity.getX() - k.x(), dz = entity.getZ() - k.z(), dy = Math.max(0, Math.abs(entity.getY() - k.y()) - 2.0);
			double d = dx * dx + dz * dz + dy * dy;
			if (d <= bestDist) {
				bestDist = d;
				best = k;
			}
		}
		if (best == null) {
			return;
		}
		StatsStore.add("mobloot." + best.mob(), amount);
		StatsStore.add("mobloot_item." + best.mob() + "|" + item, amount);
	}

	private static String killKey(Target t) {
		if (t.name() != null) {
			// OPSUCHT benennt normale Mobs oft nur um („⚠ Huhn“, „Schwein“): dann als normalen Mob zählen
			String id = mobIdForName(t.name());
			if (id == null) {
				return "killed.~" + t.name();
			}
			return "killed." + id;
		}
		return "killed." + BuiltInRegistries.ENTITY_TYPE.getKey(t.type());
	}

	private static Map<String, String> mobNames;

	/** Name eines Mobs (deutsch oder in Spielsprache, Zeichen wie ⚠ egal) → ID des normalen Mobs, sonst null. */
	public static synchronized String mobIdForName(String name) {
		if (mobNames == null) {
			mobNames = new HashMap<>();
			for (var type : BuiltInRegistries.ENTITY_TYPE) {
				String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
				mobNames.putIfAbsent(cleanName(type.getDescription().getString()), id);
				mobNames.putIfAbsent(cleanName(GermanLang.entity(type)), id);
			}
			mobNames.remove("");
		}
		return mobNames.get(cleanName(name));
	}

	private static String cleanName(String s) {
		String t = net.minecraft.ChatFormatting.stripFormatting(s == null ? "" : s);
		// nur Buchstaben und Leerzeichen (Symbole, Zahlen wie „x3“ oder Herzen weg)
		t = t.replaceAll("[^\\p{L} ]", " ").replaceAll("\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT);
		return t;
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
			if (!OpsuchtAfk.afk(mc)) {
				// aktive Spielzeit: nur, solange OPSUCHT einen nicht als AFK meldet (für Quests und Rangliste)
				long step = Math.min(now - lastTickTime, 1000);
				StatsStore.add("general.active_time", step);
				// Erkundung: Zeit im Nether und tief unter der Erde (für Quests)
				// OPSUCHT hat eigene Welten (Farmwelt, Farm-Nether …) mit eigenen Namen: nach dem Namen gehen,
				// nicht nur nach der Vanilla-Oberwelt
				String dim = mc.level.dimension().identifier().getPath().toLowerCase(java.util.Locale.ROOT);
				// Welt-Art statt Name: Nether = Decke und kein Himmelslicht, End = weder noch (Farm-Nether heißen beliebig)
				var dt = mc.level.dimensionType();
				boolean nether = mc.level.dimension() == net.minecraft.world.level.Level.NETHER || dim.contains("nether")
						|| (!dt.hasSkyLight() && dt.hasCeiling());
				boolean end = !nether && (mc.level.dimension() == net.minecraft.world.level.Level.END || dim.contains("end")
						|| (!dt.hasSkyLight() && !dt.hasCeiling()));
				if (nether) {
					StatsStore.add("general.nether_time", step);
				} else if (!end && p.getY() < 0) {
					StatsStore.add("general.deep_time", step);
				}
			}
		}
		lastTickTime = now;

		watchMining(mc, now);
		trackMovement(mc, p);
		trackHealth(p);
		trackEating(p);
		trackAreaHits(mc, now);
		trackOwnDrops(mc, now);
		tagDrops(mc, now);
		inventoryLoot(mc, now);
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
				lastFaceAxis = bhr.getDirection().getAxis();
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
		// Ist der Block schon weg, zeigt das Fadenkreuz auf den Block dahinter: dann die zuletzt angeklickte Seite nehmen –
		// nie einen ganzen Würfel, sonst landen verdeckte Blöcke dahinter in der Liste (die zeigt der Server wegen
		// Anti-Xray oft als zufällige Erze an → „Gold abgebaut“, obwohl es Erde war)
		net.minecraft.core.Direction.Axis axis = mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr
				&& bhr.getBlockPos().equals(center) ? bhr.getDirection().getAxis() : lastFaceAxis;
		if (axis == null) {
			return;
		}
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
					if ((isRealBlock(st.getBlock()) || isPlant(st.getBlock())) && !(isOre(st) && hidden(mc, p, center))) {
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
	private static net.minecraft.core.Direction.Axis lastFaceAxis;

	private static boolean isOre(BlockState st) {
		String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
		return id.endsWith("_ore") || id.equals("ancient_debris");
	}

	/** Rundum von festen Blöcken umgeben (der gerade abgebaute zählt als fest): echtes Aussehen unbekannt (Anti-Xray). */
	private static boolean hidden(Minecraft mc, net.minecraft.core.BlockPos p, net.minecraft.core.BlockPos center) {
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
			net.minecraft.core.BlockPos n = p.relative(d);
			if (!n.equals(center) && !mc.level.getBlockState(n).isSolidRender()) {
				return false;
			}
		}
		return true;
	}

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
				if (!(cur.isAir() || cur.canBeReplaced()) && (isRealBlock(cur.getBlock()) || isPlant(cur.getBlock()))) {
					e.setValue(cur); // der Server zeigt jetzt den echten Block (vorher verdeckt): den merken
					continue;
				}
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

	/**
	 * Flächenschaden: Kurz nach einem eigenen Schlag nehmen oft mehrere Mobs gleichzeitig Schaden (Schwungangriff,
	 * Flächen-Waffen, Mob-Farmen). Alle Mobs in der Nähe, die gerade getroffen wurden oder sterben, werden dann ebenfalls
	 * beobachtet – so zählen z. B. 10 Endermen, die mit einem Schlag sterben, auch als 10 Kills.
	 */
	private static void trackAreaHits(Minecraft mc, long now) {
		if (now - lastSwingAt > AREA_HIT_MS || mc.level == null || mc.player == null) {
			return;
		}
		// Nur Mobs rund um den eigenen Treffer – sonst zählen in Mob-Farmen Mobs mit, die andere (oder die Farm) töten
		boolean wide = isSpecialTool(mc.player.getMainHandItem());
		double range = wide ? AREA_RANGE : SWEEP_RANGE;
		net.minecraft.world.phys.Vec3 at = wide || lastHitAt == null ? mc.player.position() : lastHitAt;
		var box = new net.minecraft.world.phys.AABB(at, at).inflate(range);
		for (LivingEntity l : mc.level.getEntitiesOfClass(LivingEntity.class, box)) {
			if (l == mc.player || l instanceof net.minecraft.world.entity.player.Player || TARGETS.containsKey(l.getId())
					|| l instanceof net.minecraft.world.entity.decoration.ArmorStand) {
				continue;
			}
			// gerade getroffen (Treffer-Aufleuchten) – ein Mob, der nur stirbt, ohne von uns getroffen zu sein, zählt nicht
			boolean hit = l.hurtTime > 0 && l.hurtTime >= l.hurtDuration - 2;
			if (!hit) {
				continue;
			}
			// vorige Lebenspunkte unbekannt: ab jetzt beobachten (der Schaden dieses Treffers zählt nicht doppelt)
			TARGETS.put(l.getId(), new Target(l.getType(), customName(l), l.getHealth(), now, l.getX(), l.getY(), l.getZ()));
		}
	}

	/** Getroffene Lebewesen beobachten: Lebensverlust = ausgeteilter Schaden, Tod kurz nach Treffer = Kill. */
	private static void trackTargets(Minecraft mc, long now) {
		Iterator<Map.Entry<Integer, Target>> it = TARGETS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Target> e = it.next();
			Target t = e.getValue();
			Entity entity = mc.level.getEntity(e.getKey());
			if (entity != null && !(entity instanceof LivingEntity)) {
				// reines Modell (ohne Lebewesen): nur auf das Verschwinden warten
				if (now - t.lastHit() > KILL_WINDOW_MS) {
					it.remove();
				} else {
					e.setValue(new Target(t.type(), t.name(), 0F, t.lastHit(), entity.getX(), entity.getY(), entity.getZ()));
				}
				continue;
			}
			if (!(entity instanceof LivingEntity living)) {
				// Server-Mobs verschwinden beim Tod ohne normale Sterbe-Animation – sofort oder nach der Animation ihres
				// Modells. Verschwindet ein Mob kurz nach einem Treffer, während man noch in der Nähe ist (nicht nur
				// außer Sichtweite gelaufen), zählt das als Kill
				boolean kill = now - t.lastHit() < VANISH_KILL_MS && mc.player.distanceToSqr(t.x(), t.y(), t.z()) < 24 * 24;
				if (kill) {
					countKill(t);
				}
				de.loothub.Loothub.LOGGER.info("[Loothub-Mobinfo] {} verschwunden {} ms nach dem letzten Treffer → {}",
						killKey(t), now - t.lastHit(), kill ? "als Kill gezählt" : "nicht gezählt");
				it.remove();
				continue;
			}
			float health = living.getHealth();
			if (health < t.lastHealth()) {
				StatsStore.add("general.damage_dealt", Math.round((t.lastHealth() - health) * 10));
			}
			if (living.isDeadOrDying()) {
				countKill(t);
				it.remove();
			} else if (now - t.lastHit() > KILL_WINDOW_MS) {
				it.remove();
			} else {
				String n = customName(living);
				e.setValue(new Target(t.type(), n != null ? n : t.name(), health, t.lastHit(), living.getX(), living.getY(),
						living.getZ()));
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
