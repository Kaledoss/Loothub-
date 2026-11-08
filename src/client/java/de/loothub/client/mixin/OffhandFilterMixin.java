package de.loothub.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import de.loothub.client.quick.OffhandFilter;

/** Offhand-Filter: Rechtsklick mit gesperrtem Item in der zweiten Hand einfach nicht ausführen. */
@Mixin(MultiPlayerGameMode.class)
public abstract class OffhandFilterMixin {
	@Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
	private void loothub$useItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (OffhandFilter.blocks(hand)) {
			cir.setReturnValue(InteractionResult.PASS);
		}
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void loothub$useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (OffhandFilter.blocks(hand)) {
			cir.setReturnValue(InteractionResult.PASS);
		}
	}

	@Inject(method = "interact", at = @At("HEAD"), cancellable = true)
	private void loothub$interact(Player player, Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (OffhandFilter.blocks(hand)) {
			cir.setReturnValue(InteractionResult.PASS);
		}
	}
}
