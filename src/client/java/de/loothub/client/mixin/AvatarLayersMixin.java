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
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.AuraLayer(parent));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.WingsLayer(parent));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.EyesLayer(parent));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.CrownLayer(parent));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new MiniMeLayer(parent, context.getModelSet()));
		((LivingEntityRendererInvoker) this).loothub$addLayer(new de.loothub.client.cosmetics.PetsLayer(parent, context.getModelSet()));
	}
}
