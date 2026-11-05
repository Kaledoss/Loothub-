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

	private record Target(EntityType<?> type, float lastHealth, long lastHit) {
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
			StatsStore.add("used." + BuiltInRegistries.ITEM.getKey(stack.getItem()), 1);
			return;
		}
		toolStat("tool_used.", stack);
	}

	private static long lastToolUseTick = -1;

	/** Werkzeuge = alles, was sich abnutzt (Spitzhacken, Äxte, Schwerter, Bögen, Angeln, Scheren …). */
	public static boolean isTool(ItemStack stack) {
		return !stack.isEmpty() && stack.isDamageableItem();
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

	public static void onPickup(Item item, int amount) {
		if (tracking(Minecraft.getInstance())) {
			StatsStore.add("picked." + BuiltInRegistries.ITEM.getKey(item), Math.max(1, amount));
		}
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

	public static boolean isRealBlock(Block block) {
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
		if (entity instanceof LivingEntity living && tracking(mc)) {
			TARGETS.put(entity.getId(), new Target(entity.getType(), living.getHealth(), System.currentTimeMillis()));
		}
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

		trackMovement(mc, p);
		trackHealth(p);
		trackEating(p);
		trackTargets(mc, now);

		StatsStore.saveIfDirty(false);
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
				it.remove();
				continue;
			}
			float health = living.getHealth();
			if (health < t.lastHealth()) {
				StatsStore.add("general.damage_dealt", Math.round((t.lastHealth() - health) * 10));
			}
			if (living.isDeadOrDying()) {
				StatsStore.add("killed." + BuiltInRegistries.ENTITY_TYPE.getKey(t.type()), 1);
				it.remove();
			} else if (now - t.lastHit() > KILL_WINDOW_MS) {
				it.remove();
			} else {
				e.setValue(new Target(t.type(), health, t.lastHit()));
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
