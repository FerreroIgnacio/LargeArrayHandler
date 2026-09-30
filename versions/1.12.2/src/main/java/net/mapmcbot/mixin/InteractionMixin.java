package net.mapmcbot.mixin;

import net.mapmcbot.client.AreaPick;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Both mouse buttons pick area corners while a pick is running, and are cancelled so a pick never
 * also swings at or places on the block pointed at.
 */
@Mixin(MinecraftClient.class)
public class InteractionMixin {
	/** Set by a left click the pick took; holds off block breaking until the button is let go. */
	private boolean mapmcbot$attackSwallowed;

	@Inject(method = "doAttack()V", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$attackPicks(CallbackInfo ci) {
		if (AreaPick.click(MinecraftClient.getInstance())) {
			mapmcbot$attackSwallowed = true;
			ci.cancel();
		}
	}

	// Holding the button keeps mining through handleBlockBreaking, which doAttack never sees.
	@Inject(method = "handleBlockBreaking(Z)V", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$holdOffBreaking(boolean attacking, CallbackInfo ci) {
		if (!attacking) {
			mapmcbot$attackSwallowed = false;
			return;
		}

		if (mapmcbot$attackSwallowed || AreaPick.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "doUse()V", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$usePicks(CallbackInfo ci) {	
		if (AreaPick.click(MinecraftClient.getInstance())) {
			ci.cancel();
		}
	}
}
