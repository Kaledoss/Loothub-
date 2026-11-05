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
 * Kopf-Item (z. B. das „Auto“) und den Talismanrucksack in der Zweithand am eigenen Spieler nicht zeichnen.
 * Nur eigene Darstellung; andere Spieler sehen es weiterhin, die Wirkung des Items bleibt.
 */
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidHeadMixin {
	@Inject(method = "extractHumanoidRenderState", at = @At("TAIL"))
	private static void loothub$hideOwnHead(LivingEntity entity, HumanoidRenderState state, float partialTick,
			ItemModelResolver resolver, CallbackInfo ci) {
		if (entity != Minecraft.getInstance().player) {
			return;
		}
		// AFK-Ansicht: eigener Charakter liegt schlafend da (nur eigene Darstellung)
		if (de.loothub.client.quick.AfkView.sleeping()) {
			state.pose = net.minecraft.world.entity.Pose.SLEEPING;
		}
		if (WidgetConfig.get().hideOwnHeadItem) {
			state.headEquipment = ItemStack.EMPTY;
			state.headItem.clear();
		}
		// Talismanrucksack in der Zweithand (von außen gesehen)
		if (de.loothub.client.quick.HiddenItems.hideOffhand(entity.getOffhandItem())) {
			if (entity.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) {
				state.leftHandItemState.clear();
			} else {
				state.rightHandItemState.clear();
			}
		}
	}
}
