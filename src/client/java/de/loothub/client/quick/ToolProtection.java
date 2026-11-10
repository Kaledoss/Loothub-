package de.loothub.client.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Werkzeugschutz: Ist die Haltbarkeit des Werkzeugs in der Hand fast aufgebraucht, warnt die Mod
 * (Ton + Hinweis) und verhindert Abbauen/Schlagen/Benutzen damit. Grenze und geschützte Werkzeugarten
 * stellt man in den Einstellungen ein.
 * Greift nur in eigene Klicks ein, löst selbst nichts aus.
 */
public final class ToolProtection {
	private static ItemStack warnedFor = ItemStack.EMPTY;

	/** Schützbare Werkzeugarten: ID (für die Einstellungen) und Anzeigename. */
	public static final java.util.List<String[]> KINDS = java.util.List.of(
			new String[]{"pickaxe", "Spitzhacken"}, new String[]{"axe", "Äxte"}, new String[]{"shovel", "Schaufeln"},
			new String[]{"hoe", "Hacken"}, new String[]{"sword", "Schwerter"}, new String[]{"bow", "Bögen/Armbrüste"},
			new String[]{"trident", "Dreizack/Streitkolben"}, new String[]{"fishing_rod", "Angeln"},
			new String[]{"shears", "Scheren"}, new String[]{"other", "Sonstiges"});

	/** Werkzeugart eines Items (Schlüssel aus {@link #KINDS}), oder null bei Dingen ohne Haltbarkeit. */
	public static String kind(ItemStack st) {
		if (st.isEmpty() || !st.isDamageableItem()) {
			return null;
		}
		if (st.is(net.minecraft.tags.ItemTags.PICKAXES)) {
			return "pickaxe";
		}
		if (st.is(net.minecraft.tags.ItemTags.AXES)) {
			return "axe";
		}
		if (st.is(net.minecraft.tags.ItemTags.SHOVELS)) {
			return "shovel";
		}
		if (st.is(net.minecraft.tags.ItemTags.HOES)) {
			return "hoe";
		}
		if (st.is(net.minecraft.tags.ItemTags.SWORDS)) {
			return "sword";
		}
		String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
		if (id.equals("bow") || id.equals("crossbow")) {
			return "bow";
		}
		if (id.equals("trident") || id.equals("mace")) {
			return "trident";
		}
		if (id.endsWith("fishing_rod")) {
			return "fishing_rod";
		}
		if (id.equals("shears")) {
			return "shears";
		}
		return "other";
	}

	private ToolProtection() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) ->
				world.isClientSide() ? check(player, hand) : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
				world.isClientSide() ? check(player, hand) : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, world, hand, hit) ->
				world.isClientSide() ? check(player, hand) : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, world, hand) ->
				world.isClientSide() ? check(player, hand) : InteractionResult.PASS);
		ClientTickEvents.END_CLIENT_TICK.register(client -> warnTick());
	}

	/** Resthaltbarkeit, oder -1 bei Dingen ohne Haltbarkeit. */
	private static int remaining(ItemStack st) {
		if (st.isEmpty() || !st.isDamageableItem()) {
			return -1;
		}
		return st.getMaxDamage() - st.getDamageValue();
	}

	/** Resthaltbarkeit in Prozent. */
	private static double percent(ItemStack st) {
		int max = st.getMaxDamage();
		return max <= 0 ? 100 : remaining(st) * 100.0 / max;
	}

	private static String percentText(ItemStack st) {
		return de.loothub.client.Fmt.compactPlain(percent(st)) + " %";
	}

	private static boolean critical(ItemStack st) {
		int r = remaining(st);
		String k = kind(st);
		WidgetConfig c = WidgetConfig.get();
		return r >= 0 && k != null && !c.toolProtectionOff.contains(k) && percent(st) <= c.toolProtectionPercent;
	}

	private static InteractionResult check(Player player, InteractionHand hand) {
		WidgetConfig c = WidgetConfig.get();
		if (!c.toolProtection || player != Minecraft.getInstance().player) {
			return InteractionResult.PASS;
		}
		ItemStack st = player.getItemInHand(hand);
		if (!critical(st)) {
			return InteractionResult.PASS;
		}
		player.displayClientMessage(Component.literal("🛡 Werkzeugschutz: nur noch " + percentText(st)
				+ " Haltbarkeit (" + remaining(st) + ") – erst reparieren").withStyle(ChatFormatting.RED), true);
		return InteractionResult.FAIL;
	}

	/** Einmalige Warnung, sobald ein Werkzeug in der Hand den Grenzwert erreicht. */
	private static void warnTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !WidgetConfig.get().toolProtection) {
			return;
		}
		ItemStack st = mc.player.getMainHandItem();
		if (!critical(st)) {
			if (!warnedFor.isEmpty() && (st.isEmpty() || !ItemStack.isSameItem(st, warnedFor))) {
				warnedFor = ItemStack.EMPTY;
			}
			return;
		}
		if (!warnedFor.isEmpty() && ItemStack.isSameItem(st, warnedFor)) {
			return;
		}
		warnedFor = st.copy();
		de.loothub.client.Sounds.play(de.loothub.client.Sounds.Group.PROTECT, SoundEvents.NOTE_BLOCK_BASS, 0.7F, 0.25F);
		mc.player.displayClientMessage(Component.literal("⚠ " + st.getHoverName().getString() + " fast kaputt ("
				+ percentText(st) + ", " + remaining(st) + " übrig) – Werkzeugschutz aktiv").withStyle(ChatFormatting.GOLD), true);
	}
}
