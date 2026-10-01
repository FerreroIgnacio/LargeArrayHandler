package net.mapmcbot.mixin;

import net.mapmcbot.client.BotOverlay;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Screen-space hook: the end of the in-game HUD. */
@Mixin(InGameHud.class)
public class InGameHudMixin {
	@Inject(method = "render(F)V", at = @At("RETURN"))
	private void mapmcbot$renderBots(float tickDelta, CallbackInfo ci) {
		BotOverlay.render();
	}
}
