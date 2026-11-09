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
 * Kopf-Item (z. B. das „Auto“) und den Talismanrucksack in der Zweithand nicht zeichnen – am eigenen Spieler und an
 * anderen Spielern (dort bleiben echte Helme sichtbar). Nur die eigene Darstellung; die Wirkung des Items bleibt.
 */
@Mixin(HumanoidMobRenderer.class)
public abstract class HumanoidHeadMixin {
	@Inject(method = "extractHumanoidRenderState", at = @At("TAIL"))
	private static void loothub$hideOwnHead(LivingEntity entity, HumanoidRenderState state, float partialTick,
			ItemModelResolver resolver, CallbackInfo ci) {
		de.loothub.client.cosmetics.Cosmetics.mark(state, entity);
		de.loothub.client.cosmetics.Motion.update(state, entity, partialTick);
		if (entity != Minecraft.getInstance().player) {
			// Andere Spieler, die seit 5 Minuten völlig stillstehen, schlafen (nur eigene Darstellung)
			de.loothub.client.quick.AfkView.markLying(state, de.loothub.client.quick.OtherAfk.lieProgress(entity));
			if (entity instanceof net.minecraft.world.entity.player.Player) {
				// Kopf-Items (Autos, Köpfe …) auch bei anderen Spielern ausblenden – echte Helme bleiben sichtbar
				if (WidgetConfig.get().hideOwnHeadItem) {
					state.headItem.clear();
					if (!state.headEquipment.is(net.minecraft.tags.ItemTags.HEAD_ARMOR)) {
						state.headEquipment = ItemStack.EMPTY;
					}
				}
				hideBackpack(entity, state);
			}
			return;
		}
		// AFK-Ansicht: eigener Charakter liegt schlafend da (nur eigene Darstellung)
		de.loothub.client.quick.AfkView.markOwn(state);
		if (WidgetConfig.get().hideOwnCape && state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar) {
			avatar.showCape = false;
		}
		if (WidgetConfig.get().hideOwnHeadItem) {
			state.headEquipment = ItemStack.EMPTY;
			state.headItem.clear();
		}
		// Talismanrucksack in der Zweithand (von außen gesehen)
		hideBackpack(entity, state);
	}

	private static void hideBackpack(LivingEntity entity, HumanoidRenderState state) {
		if (de.loothub.client.quick.HiddenItems.hideOffhand(entity.getOffhandItem())) {
			if (entity.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) {
				state.leftHandItemState.clear();
			} else {
				state.rightHandItemState.clear();
			}
		}
	}
}
