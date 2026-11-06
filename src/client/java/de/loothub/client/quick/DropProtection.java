package de.loothub.client.quick;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import de.loothub.client.jobs.WidgetConfig;

/**
 * Wegwerf-Schutz: Wertvolle Items (Seltenheit, eigener Name, eigenes Modell, verzaubert) fallen erst, wenn man innerhalb
 * kurzer Zeit ein zweites Mal wirft. Der erste Wurf wird nur auf dem eigenen Client zurückgehalten – es wird nichts
 * automatisch ausgelöst.
 */
public final class DropProtection {
	private static final long WINDOW_MS = 1500;
	private static ItemStack armed = ItemStack.EMPTY;
	private static long armedAt;

	private DropProtection() {
	}

	public static boolean valuable(ItemStack st) {
		if (st.isEmpty()) {
			return false;
		}
		return RarityFrames.rarityColor(st) != 0 || st.has(DataComponents.CUSTOM_NAME)
				|| st.has(DataComponents.CUSTOM_MODEL_DATA) || st.isEnchanted();
	}

	/** @return true, wenn dieser Wurf erlaubt ist; false = zurückhalten (erster Druck). */
	public static boolean allow(ItemStack st) {
		if (!WidgetConfig.get().dropProtection || !valuable(st)) {
			return true;
		}
		long now = System.currentTimeMillis();
		if (!armed.isEmpty() && now - armedAt <= WINDOW_MS && ItemStack.isSameItemSameComponents(armed, st)) {
			armed = ItemStack.EMPTY;
			return true;
		}
		armed = st.copy();
		armedAt = now;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.gui.setOverlayMessage(Component.translatable("loothub.drop_protection.hint", st.getHoverName())
					.withStyle(ChatFormatting.GOLD), false);
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.6F, 0.5F));
		}
		return false;
	}
}
