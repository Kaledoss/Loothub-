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

import de.loothub.client.jobs.WidgetConfig;

/**
 * Werkzeugschutz: Ist die Haltbarkeit des Werkzeugs in der Hand fast aufgebraucht, warnt die Mod
 * (Ton + Hinweis) und verhindert Abbauen/Schlagen/Benutzen damit. Schleichen hebt die Sperre auf.
 * Greift nur in eigene Klicks ein, löst selbst nichts aus.
 */
public final class ToolProtection {
	private static ItemStack warnedFor = ItemStack.EMPTY;

	private ToolProtection() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) ->
				world.isClientSide() ? check(player, hand) : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
				world.isClientSide() ? check(player, hand) : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, world, hand, hit) ->
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

	private static boolean critical(ItemStack st) {
		int r = remaining(st);
		return r >= 0 && r <= WidgetConfig.get().toolProtectionMin;
	}

	private static InteractionResult check(Player player, InteractionHand hand) {
		WidgetConfig c = WidgetConfig.get();
		if (!c.toolProtection || player != Minecraft.getInstance().player || player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		ItemStack st = player.getItemInHand(hand);
		if (!critical(st)) {
			return InteractionResult.PASS;
		}
		player.displayClientMessage(Component.literal("🛡 Werkzeugschutz: nur noch " + remaining(st)
				+ " Haltbarkeit – Schleichen zum Benutzen").withStyle(ChatFormatting.RED), true);
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
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.7f));
		mc.player.displayClientMessage(Component.literal("⚠ " + st.getHoverName().getString() + " fast kaputt ("
				+ remaining(st) + " übrig) – Werkzeugschutz aktiv").withStyle(ChatFormatting.GOLD), true);
	}
}
