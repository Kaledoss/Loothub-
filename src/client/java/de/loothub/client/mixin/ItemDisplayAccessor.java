package de.loothub.client.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Item einer Item-Anzeige lesen – Server-Mobs bestehen oft aus solchen Modell-Teilen (eigenes Item-Modell). */
@Mixin(Display.ItemDisplay.class)
public interface ItemDisplayAccessor {
	@Invoker("getItemStack")
	ItemStack loothub$getItemStack();
}
