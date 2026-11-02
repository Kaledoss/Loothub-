package de.loothub.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import de.loothub.client.stats.StatsTracker;

/**
 * Der Client setzt Blöcke beim Platzieren schon vorab (Vorhersage). Ist das erfolgreich, zählen wir den Block
 * für die eigene Statistik. Am Spielablauf ändert sich nichts.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "place", at = @At("RETURN"))
	private void loothub$onPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (context.getLevel().isClientSide() && context.getPlayer() instanceof LocalPlayer
				&& cir.getReturnValue() != null && cir.getReturnValue().consumesAction()) {
			StatsTracker.onBlockPlaced(((BlockItem) (Object) this).getBlock());
		}
	}
}
