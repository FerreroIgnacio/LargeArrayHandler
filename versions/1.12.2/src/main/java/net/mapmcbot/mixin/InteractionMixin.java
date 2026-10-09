package net.mapmcbot.mixin;

import net.mapmcbot.client.AreaPick;
import net.mapmcbot.client.TargetPick;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Both mouse buttons pick area corners (or a primitive's target) while a pick is running, and are cancelled so a pick never
 * also swings at or places on the block pointed at.
 */
@Mixin(MinecraftClient.class)
public class InteractionMixin {
	/** Set by a left click the pick took; holds off block breaking until the button is let go. */
	private boolean mapmcbot$attackSwallowed;

	@Inject(method = "doAttack()V", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$attackPicks(CallbackInfo ci) {
		if (AreaPick.click(MinecraftClient.getInstance()) || TargetPick.click(MinecraftClient.getInstance())) {
			mapmcbot$attackSwallowed = true;
			ci.cancel();
		}
	}

	// Holding the button keeps mining through handleBlockBreaking, which doAttack never sees.
	@Inject(method = "handleBlockBreaking(Z)V", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$holdOffBreaking(boolean attacking, CallbackInfo ci) {
		// Every tick: the right button let go ends the use hold-off.
		if (!MinecraftClient.getInstance().options.useKey.isPressed()) {
			mapmcbot$useSwallowed = false;
		}

		if (!attacking) {
			mapmcbot$attackSwallowed = false;
			return;
		}

		if (mapmcbot$attackSwallowed || AreaPick.isActive() || TargetPick.isActive()) {
			ci.cancel();
		}
	}

	/** Set by a right click the pick took; holds off use (a chest opening, a block placed) until the button is let go. */
	private boolean mapmcbot$useSwallowed;

	@Inject(method = "doUse()V", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$usePicks(CallbackInfo ci) {
		final MinecraftClient client = MinecraftClient.getInstance();

		// Holding the button repeats the use: the ones after the pick's are swallowed too.
		if (mapmcbot$useSwallowed) {
			ci.cancel();
			return;
		}

		if (AreaPick.click(client) || TargetPick.click(client)) {
			mapmcbot$useSwallowed = true;
			ci.cancel();
		}
	}
}
