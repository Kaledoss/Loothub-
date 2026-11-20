package de.loothub.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import de.loothub.client.jobs.WidgetConfig;

/** Krönchen vor Spielern in der Tab-Liste, die auch Loothub nutzen (sie stehen auf dem Cosmetics-Server), dazu ihr Abzeichen. */
@Mixin(PlayerTabOverlay.class)
public abstract class TabListCrownMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void loothub$crown(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		if (info == null || info.getProfile() == null) {
			return;
		}
		// Abzeichen aus dem Loothub-Shop vor dem Namen
		String[] tags = de.loothub.client.progress.Progress.tagsOf(info.getProfile().id());
		Component badge = tags == null ? null : de.loothub.client.progress.Shop.badge(tags[1]);
		if (badge != null) {
			cir.setReturnValue(Component.empty().append(badge).append(" ").append(cir.getReturnValue()));
		}
		if (WidgetConfig.get().loothubCrown && de.loothub.client.cosmetics.CosmeticsServer.isUser(info.getProfile().id())) {
			cir.setReturnValue(Component.literal("♔ ").withStyle(s -> s.withColor(0xF2C14E))
					.append(cir.getReturnValue()));
		}
	}
}
