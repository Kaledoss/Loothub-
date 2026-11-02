package de.loothub.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.stats.StatsTracker;

/** Liest mit, wenn der Server meldet, dass Sie ein Item aufgesammelt haben (für die eigene Statistik). */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
	private void loothub$onTakeItem(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		// Die Methode läuft zuerst im Netzwerk-Thread und wird dann im Spiel-Thread wiederholt – nur dort zählen
		if (!mc.isSameThread() || mc.player == null || mc.level == null || packet.getPlayerId() != mc.player.getId()) {
			return;
		}
		Entity entity = mc.level.getEntity(packet.getItemId());
		if (entity instanceof ItemEntity item) {
			StatsTracker.onPickup(item.getItem().getItem(), packet.getAmount());
		}
	}
}
