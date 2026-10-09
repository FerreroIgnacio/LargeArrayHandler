package net.mapmcbot.debug.mixin;

import com.mojang.authlib.GameProfile;

import net.mapmcbot.client.MapMcBotClient;
import net.mapmcbot.client.SkinCache;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Debug only (src/debug, remove with it): every bot with a profile wears the skin its profile names
 * (see SkinCache), with that skin's own model. Client side only: the bots join an offline LAN with no
 * skin of their own, and only this client sees it. On a real server the skin is the bot's to set with
 * /skin. Until the skin has arrived the bot keeps the game's default.
 */
@Mixin(PlayerListEntry.class)
public abstract class BotSkinMixin {
	@Shadow
	public abstract GameProfile getProfile();

	@Inject(method = "getSkinTexture()Lnet/minecraft/util/Identifier;", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$botSkin(CallbackInfoReturnable<Identifier> cir) {
		final SkinCache.Skin skin = mapmcbot$skin();

		if (skin != null) {
			cir.setReturnValue(skin.getTexture());
		}
	}

	@Inject(method = "getModel()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$botModel(CallbackInfoReturnable<String> cir) {
		final SkinCache.Skin skin = mapmcbot$skin();

		if (skin != null) {
			cir.setReturnValue(skin.isSlim() ? "slim" : "default");
		}
	}

	/** The skin of the bot's profile, null for a player with no profile or while it loads. */
	private SkinCache.Skin mapmcbot$skin() {
		final String name = getProfile().getName();

		if (!name.startsWith(MapMcBotClient.NAME_PREFIX) || !MapMcBotClient.botProfiles().has(name)) {
			return null;
		}

		return SkinCache.of(MapMcBotClient.botProfiles().get(name).getSkinName());
	}
}
