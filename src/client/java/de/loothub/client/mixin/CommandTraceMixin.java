package de.loothub.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnose: jeden Befehl, der an den Server geht – egal von welcher Mod –, mit seinem Auslöser ins Log schreiben.
 * So lässt sich herausfinden, welche Mod Befehle von selbst verschickt. Ändert nichts, liest nur mit.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class CommandTraceMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void loothub$traceCommand(Packet<?> packet, CallbackInfo ci) {
		String command = packet instanceof ServerboundChatCommandPacket p ? p.command()
				: packet instanceof ServerboundChatCommandSignedPacket p ? p.command() : null;
		if (command != null) {
			de.loothub.client.quick.CommandTrace.log(command);
		}
	}
}
