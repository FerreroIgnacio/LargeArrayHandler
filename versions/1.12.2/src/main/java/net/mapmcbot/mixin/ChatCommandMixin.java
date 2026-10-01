package net.mapmcbot.mixin;

import net.mapmcbot.client.MapMcBotClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat lines starting with # are mod commands: handled here and never sent to the server. */
@Mixin(ClientPlayerEntity.class)
public class ChatCommandMixin {
	@Inject(method = "sendChatMessage(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
	private void mapmcbot$command(String message, CallbackInfo ci) {
		if (MapMcBotClient.command(message)) {
			ci.cancel();
		}
	}
}
