package de.loothub.client.mixin;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import de.loothub.client.quick.ChatExtras;

/** Jede Chat-Nachricht (auch Spieler-Nachrichten) vor der Anzeige um Uhrzeit, Kopieren und Erwähnung ergänzen. */
@Mixin(ChatComponent.class)
public abstract class ChatMessageMixin {
	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component loothub$extras(Component message) {
		return ChatExtras.modify(message);
	}
}
