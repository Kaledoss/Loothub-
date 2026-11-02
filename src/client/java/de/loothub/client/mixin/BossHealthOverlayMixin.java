package de.loothub.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.quick.ProgressColors;

/** Fortschrittsbalken oben (Bossleisten, auf OPSUCHT z. B. Erfolge und Jobitems) nach Fortschritt einfärben. */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
	@Inject(method = "drawBar(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/BossEvent;)V", at = @At("TAIL"))
	private void loothub$recolor(GuiGraphics g, int x, int y, BossEvent event, CallbackInfo ci) {
		ProgressColors.drawBossBar(g, x, y, event.getProgress());
	}
}
