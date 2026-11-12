package de.loothub.client.mixin;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import de.loothub.client.cosmetics.MiniMeLayer;

/** Loothub-Cosmetics als zusätzliche Ebene an alle Spieler-Renderer hängen. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarLayersMixin {
	@SuppressWarnings("unchecked")
	@Inject(method = "<init>", at = @At("RETURN"))
	private void loothub$cosmetics(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
		RenderLayerParent<AvatarRenderState, PlayerModel> parent = (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) this;
		// Aura vorerst ausgebaut (AuraLayer bleibt im Code für später)
		// Steigflug-Leuchten ausgebaut (RiseTrailLayer bleibt im Code für später)
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.WingsLayer(parent));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.EyesLayer(parent));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.CrownLayer(parent));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new MiniMeLayer(parent, context.getModelSet()));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.PetsLayer(parent, context.getModelSet()));
	}

	/**
	 * Ego-Perspektive mit Begleitern: den eigenen Spieler unsichtbar und „leer“ vorbereiten, damit nur Haustiere und freie
	 * Mini-Mes gezeichnet werden. Außerdem: Vorschau-Zustände des Drehtellers erkennen.
	 */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void loothub$firstPerson(net.minecraft.world.entity.Avatar entity, AvatarRenderState state, float partialTicks,
			CallbackInfo ci) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (entity == mc.player && !de.loothub.client.cosmetics.CosmeticsPreviewScreen.rendering()
				&& de.loothub.client.cosmetics.FirstPerson.active()) {
			de.loothub.client.cosmetics.FirstPerson.strip(state);
		} else {
			de.loothub.client.cosmetics.FirstPerson.unmark(state);
		}
		if (de.loothub.client.cosmetics.CosmeticsPreviewScreen.rendering() && entity == mc.player) {
			de.loothub.client.cosmetics.CosmeticsPreviewScreen.mark(state);
		}
	}
}
