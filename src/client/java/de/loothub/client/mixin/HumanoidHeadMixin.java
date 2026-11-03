package de.loothub.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Kopf-Item am eigenen Spieler nicht zeichnen (z. B. das „Auto“ – eine umgestaltete Pferderüstung auf dem Kopf).
 * Nur eigene Darstellung; andere Spieler sehen es weiterhin, die Wirkung des Items bleibt.
 */
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidHeadMixin {
	@Inject(method = "extractHumanoidRenderState", at = @At("TAIL"))
	private static void loothub$hideOwnHead(LivingEntity entity, HumanoidRenderState state, float partialTick,
			ItemModelResolver resolver, CallbackInfo ci) {
		if (WidgetConfig.get().hideOwnHeadItem && entity == Minecraft.getInstance().player) {
			state.headEquipment = ItemStack.EMPTY;
			state.headItem.clear();
		}
	}
}
