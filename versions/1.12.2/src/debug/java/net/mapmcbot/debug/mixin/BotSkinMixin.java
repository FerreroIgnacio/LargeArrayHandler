package net.mapmcbot.debug.mixin;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Debug only (src/debug, remove with it): every bot (a player named Bot...) wears
 * textures/entity/bot_skin.png of this source set, in the classic (wide-arm) model. Client side
 * only: the bots join offline, with no skin of their own, and only this client sees it.
 */
@Mixin(PlayerListEntry.class)
public abstract class BotSkinMixin {
	private static final Identifier BOT_SKIN = new Identifier("mapmcbot_debug", "textures/entity/bot_skin.png");

	@Shadow
	public abstract GameProfile getProfile();

	@Inject(method = "getSkinTexture()Lnet/minecraft/util/Identifier;", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$botSkin(CallbackInfoReturnable<Identifier> cir) {
		if (mapmcbot$isBot()) {
			cir.setReturnValue(BOT_SKIN);
		}
	}

	@Inject(method = "getModel()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$botModel(CallbackInfoReturnable<String> cir) {
		if (mapmcbot$isBot()) {
			cir.setReturnValue("default");
		}
	}

	private boolean mapmcbot$isBot() {
		return getProfile().getName().startsWith("Bot");
	}
}
