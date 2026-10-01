package net.mapmcbot.debug.mixin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Debug only (src/debug, remove with it): the sent-message history (the up arrow in the chat box)
 * survives restarts of runClient, kept in mapmcbot/chat_history.txt.
 */
@Mixin(ChatHud.class)
public abstract class ChatHistoryMixin {
	private boolean mapmcbot$loading;

	@Shadow
	public abstract void addToMessageHistory(String message);

	@Inject(method = "<init>(Lnet/minecraft/client/MinecraftClient;)V", at = @At("RETURN"))
	private void mapmcbot$load(MinecraftClient client, CallbackInfo ci) {
		final File file = file(client);

		if (!file.exists()) {
			return;
		}

		mapmcbot$loading = true;

		try {
			final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);

			for (String line : lines) {
				addToMessageHistory(line);
			}
		} catch (IOException e) {
			throw new java.io.UncheckedIOException("could not read the chat history " + file, e);
		} finally {
			mapmcbot$loading = false;
		}
	}

	@Inject(method = "addToMessageHistory(Ljava/lang/String;)V", at = @At("RETURN"))
	private void mapmcbot$save(String message, CallbackInfo ci) {
		if (mapmcbot$loading) {
			return;
		}

		final File file = file(MinecraftClient.getInstance());

		try {
			Files.write(file.toPath(), (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException("could not write the chat history " + file, e);
		}
	}

	private static File file(MinecraftClient client) {
		return new File(client.runDirectory, "mapmcbot/chat_history.txt");
	}
}
