package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.mapmcbot.bot.BotRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/** The bots and their status, top right of the HUD. */
public final class BotOverlay {
	private static final int MARGIN = 4;

	private BotOverlay() {
	}

	public static void render() {
		final BotRegistry bots = MapMcBotClient.botsOrNull();

		if (bots == null || bots.getBots().isEmpty()) {
			return;
		}

		final MinecraftClient client = MinecraftClient.getInstance();
		final Window window = new Window(client);
		final List<String> names = new ArrayList<String>(bots.getBots());
		Collections.sort(names);

		int y = MARGIN;

		for (String name : names) {
			final String line = name + " " + bots.getStatus(name);
			client.textRenderer.drawWithShadow(line, window.getWidth() - MARGIN - client.textRenderer.getStringWidth(line), y, 0xFFFFFF);
			y += client.textRenderer.fontHeight + 2;
		}
	}
}
