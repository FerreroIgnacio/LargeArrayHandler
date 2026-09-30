package net.mapmcbot.mixin;

import net.mapmcbot.client.AreaRenderer;
import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** World-space hook: the end of entity rendering, where the camera transform is already set. */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
	@Inject(method = "renderEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/CameraView;F)V",
			at = @At("RETURN"))
	private void mapmcbot$renderAreas(Entity camera, CameraView view, float tickDelta, CallbackInfo ci) {
		AreaRenderer.render(tickDelta);
	}
}
