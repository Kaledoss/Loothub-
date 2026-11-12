package de.loothub.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Töne vom Server: den gelernten OPSUCHT-Fangton beim Angeln nicht abspielen (siehe FishSounds). */
@Mixin(ClientPacketListener.class)
public abstract class FishSoundMixin {
	@Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
	private void loothub$fishSound(ClientboundSoundPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread() && de.loothub.client.jobs.FishSounds.onSound(packet.getSound())) {
			ci.cancel();
		}
	}

	@Inject(method = "handleSoundEntityEvent", at = @At("HEAD"), cancellable = true)
	private void loothub$fishEntitySound(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread() && de.loothub.client.jobs.FishSounds.onSound(packet.getSound())) {
			ci.cancel();
		}
	}
}
