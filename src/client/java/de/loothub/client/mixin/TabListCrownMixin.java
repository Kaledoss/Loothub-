package de.loothub.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import de.loothub.client.jobs.WidgetConfig;

/** Krönchen vor Spielern in der Tab-Liste, die auch Loothub nutzen (sie stehen auf dem Cosmetics-Server). */
@Mixin(PlayerTabOverlay.class)
public abstract class TabListCrownMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void loothub$crown(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		if (!WidgetConfig.get().loothubCrown || info == null || info.getProfile() == null) {
			return;
		}
		if (de.loothub.client.cosmetics.CosmeticsServer.isUser(info.getProfile().id())) {
			cir.setReturnValue(Component.literal("♔ ").withStyle(s -> s.withColor(0xF2C14E))
					.append(cir.getReturnValue()));
		}
	}
}
