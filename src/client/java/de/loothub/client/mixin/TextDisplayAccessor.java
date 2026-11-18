package de.loothub.client.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Text einer schwebenden Textanzeige lesen – Server-Mobs tragen ihren Namen oft nur dort. */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
	@Invoker("getText")
	Component loothub$getText();
}
