package de.loothub.client.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import de.loothub.client.cosmetics.FirstPerson;

/**
 * Spielerfigur im Inventar: Hier darf die Ego-Perspektive den eigenen Spieler nicht unsichtbar machen – sonst sähe man
 * im Inventar nur noch Flügel und Begleiter.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryEntityMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
			at = @At("HEAD"))
	private static void loothub$guiStart(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> cir) {
		FirstPerson.gui = true;
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
			at = @At("RETURN"))
	private static void loothub$guiEnd(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> cir) {
		FirstPerson.gui = false;
	}
}
