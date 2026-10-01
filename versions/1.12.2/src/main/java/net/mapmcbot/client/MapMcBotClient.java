package net.mapmcbot.client;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.api.ClientModInitializer;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.legacyfabric.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.mapmcbot.area.AreaStore;
import net.mapmcbot.bot.BotManager;
import net.mapmcbot.chunk.ChunkRegistry;
import net.mapmcbot.chunk.ChunkSnapshotStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.lwjgl.input.Keyboard;

/**
 * Client entrypoint: the keybinds, the per-tick work, and the per-world state (areas, bots, chunks) that
 * the rest of the mod reaches through the static getters.
 */
public class MapMcBotClient implements ClientModInitializer {
	private static final String CATEGORY = "key.categories.mapmcbot";

	/** Each bot's colour, given the first time it is asked for and kept for the session. */
	private static final Map<String, Color> BOT_COLORS = new ConcurrentHashMap<String, Color>();

	private static KeyBinding areasKey;

	/** The id of the last #formation sent; each one gets the next. */
	private static int lastFormation;

	private static AreaStore areas;
	private static String areasWorldId;
	private static BotManager bots;
	private static ChunkRegistry chunks;

	@Override
	public void onInitializeClient() {
		// LWJGL 2 key codes, right up to 1.12.2; 1.13+ uses GLFW codes.
		areasKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mapmcbot.areas", Keyboard.KEY_V, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(MapMcBotClient::tick);
	}

	private static void tick(MinecraftClient client) {
		if (client.world == null || client.player == null) {
			leaveWorld();
			return;
		}

		boolean openAreas = false;

		// Drained whole so a lag spike cannot double a press.
		while (areasKey.wasPressed()) {
			openAreas = true;
		}

		if (openAreas && client.currentScreen == null) {
			// Mid-pick the key backs out of the pick instead of reopening the editor.
			if (AreaPick.isActive()) {
				AreaPick.cancel(client);
			} else {
				client.setScreen(new AreaScreen());
			}
		}

		AreaPick.tick(client);
		glowBots(client);
	}

	/** Every bot glows through walls, coloured by the scoreboard team it is put on. */
	private static void glowBots(MinecraftClient client) {
		if (bots == null || bots.getBots().isEmpty()) {
			return;
		}

		final Scoreboard scoreboard = client.world.getScoreboard();

		for (PlayerEntity player : client.world.playerEntities) {
			final String name = player.getEntityName();

			if (!bots.getBots().contains(name)) {
				continue;
			}

			final Color color = colorOf(name);
			final String teamName = "mapmcbot_" + color.name().toLowerCase();
			Team team = scoreboard.getTeam(teamName);

			if (team == null) {
				team = scoreboard.addTeam(teamName);
				team.setFormatting(color.getFormatting());
			}

			if (scoreboard.getPlayerTeam(name) != team) {
				scoreboard.addPlayerToTeam(name, teamName);
			}

			player.setGlowing(true);
		}
	}

	/** The bot's colour; a new bot takes the next one in turn, so the first few never share one. */
	public static Color colorOf(String bot) {
		return BOT_COLORS.computeIfAbsent(bot, name -> Color.values()[BOT_COLORS.size() % Color.values().length]);
	}

	/** A chat line for the mod: #formation sends every bot to the block the player stands on. False when it is not one. */
	public static boolean command(String line) {
		if (!line.equals("#formation")) {
			return false;
		}

		final MinecraftClient client = MinecraftClient.getInstance();
		final int x = (int) Math.floor(client.player.x);
		final int y = (int) Math.floor(client.player.y);
		final int z = (int) Math.floor(client.player.z);
		final int id = ++lastFormation;

		for (String bot : bots().getBots()) {
			bots().formation(bot, id, x, y, z);
		}

		return true;
	}

	private static void leaveWorld() {
		if (areas != null) {
			areas.save();
			areas = null;
			areasWorldId = null;
		}

		if (bots != null) {
			bots.stopAll();
		}

		AreaPick.reset();
	}

	/** The areas of the world being played, reloaded when the world changes; null outside a world. */
	public static AreaStore areas() {
		final MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null) {
			return null;
		}

		final String worldId = worldId(client);

		if (worldId == null) {
			return null;
		}

		if (!worldId.equals(areasWorldId)) {
			if (areas != null) {
				areas.save();
			}

			areas = new AreaStore(new File(dataDirectory(client), worldId + "/areas.tsv"));
			areasWorldId = worldId;
		}

		return areas;
	}

	public static BotManager bots() {
		if (bots == null) {
			final File directory = dataDirectory(MinecraftClient.getInstance());
			bots = new BotManager(new File(directory, "bot"));
			chunks = new ChunkRegistry(bots, new ChunkSnapshotStore(new File(directory, "chunks")));
		}

		return bots;
	}

	/** The bots, or null while none was ever started. */
	public static BotManager botsOrNull() {
		return bots;
	}

	/** The chunks the bots hold; created with the bots. */
	public static ChunkRegistry chunks() {
		bots();
		return chunks;
	}

	private static File dataDirectory(MinecraftClient client) {
		return new File(client.runDirectory, "mapmcbot");
	}

	/**
	 * A folder name per world and dimension, same layout as the original mod so its data carries
	 * over. Singleplayer goes by the save folder: the client world is a placeholder named MpServer
	 * in every save. Null while the integrated server is still starting.
	 */
	private static String worldId(MinecraftClient client) {
		final String base;

		if (client.isInSingleplayer()) {
			final MinecraftServer server = client.getServer();
			final ServerWorld overworld = server == null ? null : server.getWorld(0);

			if (overworld == null || overworld.getSaveHandler() == null
					|| overworld.getSaveHandler().getWorldFolder() == null) {
				return null;
			}

			base = "sp-" + overworld.getSaveHandler().getWorldFolder().getName();
		} else {
			final ServerInfo server = client.getCurrentServerEntry();
			base = "mp-" + (server == null ? "unknown" : server.address);
		}

		return sanitize(base) + "/" + sanitize(client.world.dimension.getDimensionType().getName());
	}

	private static String sanitize(String raw) {
		final StringBuilder out = new StringBuilder(raw.length());

		for (int i = 0; i < raw.length(); i++) {
			final char c = raw.charAt(i);
			out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
		}

		return out.length() == 0 ? "world" : out.toString();
	}
}
