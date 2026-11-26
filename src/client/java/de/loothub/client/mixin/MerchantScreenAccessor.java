package de.loothub.client.mixin;

import net.minecraft.client.gui.screens.inventory.MerchantScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gewählter Handel im Dorfbewohner-Fenster (für „Max handeln“). */
@Mixin(MerchantScreen.class)
public interface MerchantScreenAccessor {
	@Accessor("shopItem")
	int loothub$shopItem();
}
