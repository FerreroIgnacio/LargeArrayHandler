package net.mapmcbot.debug;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.IntStream;

import net.fabricmc.api.ClientModInitializer;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.mapmcbot.client.MapMcBotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.server.PlayerManager;
import net.minecraft.world.GameMode;

/**
 * Debug only, loaded by runClient alone (its own source set, never in the mod's jar): clicks through
 * Singleplayer and the first world as a player would, opens it to LAN in survival as soon as it is
 * up, then adds PandaBot2 to PandaBot128 on the LAN port. Removing it is deleting src/debug and its block in
 * build.gradle.
 */
public class AutoStart implements ClientModInitializer {
	/** Every bot's name starts with it; BotSkinMixin tells the bots apart by it. */
	public static final String BOT_PREFIX = "PandaBot";

	private static final String[] BOTS = IntStream.rangeClosed(2, 128).mapToObj(i -> BOT_PREFIX + i).toArray(String[]::new);

	/** The LAN's player limit, vanilla's 8 being too few for the player and every bot. */
	private static final int MAX_PLAYERS = 128;

	/** Vanilla button ids: Singleplayer, Play Selected World. */
	private static final int SINGLEPLAYER = 1;
	private static final int PLAY_SELECTED = 1;

	/** Ticks between two bots joining, and before each click (a screen builds its buttons first). */
	private static final int BOT_GAP_TICKS = 10;
	private static final int SETTLE_TICKS = 10;

	private enum Step { TITLE, PICK_WORLD, PLAY, LOADING, BOTS, DONE }

	private Step step = Step.TITLE;
	private int wait = SETTLE_TICKS;
	private int nextBot;
	private int port;

	/** The skin BotSkinMixin puts on every bot; a missing one would only show as the purple checkerboard. */
	private static final String BOT_SKIN = "/assets/mapmcbot_debug/textures/entity/bot_skin.png";

	@Override
	public void onInitializeClient() {
		if (AutoStart.class.getResource(BOT_SKIN) == null) {
			throw new IllegalStateException("missing the bots' skin: put a 64x64 skin png at src/debug/resources" + BOT_SKIN);
		}

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(MinecraftClient client) {
		if (step == Step.DONE || --wait > 0) {
			return;
		}

		wait = SETTLE_TICKS;
		final Screen screen = client.currentScreen;

		switch (step) {
			case TITLE:
				if (screen instanceof TitleScreen && click(screen, SINGLEPLAYER)) {
					step = Step.PICK_WORLD;
				}
				break;

			case PICK_WORLD:
				if (screen instanceof SelectWorldScreen) {
					// The first row of the world list, as a click on it: the list starts at y 32.
					clickAt(screen, screen.width / 2, 32 + 4 + 10);
					step = Step.PLAY;
				}
				break;

			case PLAY:
				if (screen instanceof SelectWorldScreen && click(screen, PLAY_SELECTED)) {
					step = Step.LOADING;
				}
				break;

			case LOADING:
				// openToLAN is what Start LAN World calls, and the only thing that hands back the port.
				if (client.world != null && client.player != null && client.getServer() != null && screen == null) {
					final String opened = client.getServer().openToLAN(GameMode.SURVIVAL, true);

					if (opened != null) {
						raisePlayerLimit(client.getServer().getPlayerManager());
						port = Integer.parseInt(opened);
						step = Step.BOTS;
					}
				}
				break;

			case BOTS:
				MapMcBotClient.bots().create(BOTS[nextBot], "localhost", port);
				nextBot++;
				wait = BOT_GAP_TICKS;
				step = nextBot < BOTS.length ? Step.BOTS : Step.DONE;
				break;

			default:
				break;
		}
	}

	/** Vanilla keeps the integrated server at 8 players; its field is protected and set once. */
	private static void raisePlayerLimit(PlayerManager players) {
		try {
			final Field field = PlayerManager.class.getDeclaredField("maxPlayers");
			field.setAccessible(true);
			field.setInt(players, MAX_PLAYERS);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("debug autostart could not raise the player limit", e);
		}
	}

	/** Clicks the screen's button `id` at its middle, if it is there and enabled. */
	private static boolean click(Screen screen, int id) {
		for (ButtonWidget button : buttons(screen)) {
			if (button.id == id && button.visible && button.active) {
				clickAt(screen, button.x + button.getWidth() / 2, button.y + 10);
				return true;
			}
		}

		return false;
	}

	/** A left click at (x, y) on the screen, through its own mouse handling. */
	private static void clickAt(Screen screen, int x, int y) {
		try {
			final Method mouseClicked = Screen.class.getDeclaredMethod("mouseClicked", int.class, int.class, int.class);
			mouseClicked.setAccessible(true);
			mouseClicked.invoke(screen, x, y, 0);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("debug autostart could not click", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ButtonWidget> buttons(Screen screen) {
		try {
			final Field field = Screen.class.getDeclaredField("buttons");
			field.setAccessible(true);
			return (List<ButtonWidget>) field.get(screen);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("debug autostart could not read the buttons", e);
		}
	}
}
