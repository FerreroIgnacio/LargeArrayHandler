package net.mapmcbot.client;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.fabricmc.api.ClientModInitializer;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.legacyfabric.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.mapmcbot.area.AreaStore;
import net.mapmcbot.area.ChestStore;
import net.mapmcbot.bot.BotManager;
import net.mapmcbot.bot.BotProfile;
import net.mapmcbot.bot.BotProfileStore;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.NameList;
import net.mapmcbot.bot.RelayHub;
import net.mapmcbot.chunk.ChunkRegistry;
import net.mapmcbot.chunk.ChunkSnapshotStore;
import net.mapmcbot.job.JobRunner;
import net.mapmcbot.job.JobStore;
import net.mapmcbot.profile.ProfileRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;

/**
 * Client entrypoint: the keybinds, the per-tick work, and the per-world state (areas, bots, chunks) that
 * the rest of the mod reaches through the static getters.
 */
public class MapMcBotClient implements ClientModInitializer {
	/** Every bot's name starts with it, then its number. */
	public static final String NAME_PREFIX = "PandaBot";

	/** The skins of the seed profiles, in order. */
	private static final String[] SEED_SKINS = {"TimedHades11835", "spikeydealdoughs", "hlk0d", "ali123456789000"};
	private static final String DEFAULT_CRACKED_PASSWORD = "pandabot";

	private static final String CATEGORY = "key.categories.mapmcbot";

	/** Each bot's colour, given the first time it is asked for and kept for the session. */
	private static final Map<String, Color> BOT_COLORS = new ConcurrentHashMap<String, Color>();

	private static KeyBinding areasKey;
	private static KeyBinding profilerKey;
	private static KeyBinding botsKey;

	private static AreaStore areas;
	private static String areasWorldId;
	private static ChestStore chests;
	private static String chestsWorldId;
	private static Grab grab;
	private static BotRegistry bots;
	private static JobRunner job;
	private static BotProfileStore botProfiles;
	private static RelayHub relays;
	private static ChunkRegistry chunks;
	private static ProfileRegistry profile;

	@Override
	public void onInitializeClient() {
		// LWJGL 2 key codes, right up to 1.12.2; 1.13+ uses GLFW codes.
		areasKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mapmcbot.areas", Keyboard.KEY_V, CATEGORY));
		profilerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mapmcbot.profiler", Keyboard.KEY_Z, CATEGORY));
		botsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.mapmcbot.bots", Keyboard.KEY_B, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(MapMcBotClient::tick);
	}

	private static void tick(MinecraftClient client) {
		if (client.world == null || client.player == null) {
			leaveWorld();
			return;
		}

		// In a world from the start, not at the first bot: the relays need the port open to connect.
		bots();

		boolean openAreas = false;

		// Drained whole so a lag spike cannot double a press.
		while (areasKey.wasPressed()) {
			openAreas = true;
		}

		if (openAreas && client.currentScreen == null) {
			// Mid-pick the key backs out of the pick instead of reopening the editor.
			if (AreaPick.isActive()) {
				AreaPick.cancel(client);
			} else if (TargetPick.isActive()) {
				TargetPick.cancel(client);
			} else {
				client.setScreen(new AreaScreen());
			}
		}

		boolean openProfiler = false;

		while (profilerKey.wasPressed()) {
			openProfiler = true;
		}

		if (openProfiler && client.currentScreen == null) {
			client.setScreen(new ProfilerScreen());
		}

		boolean openBots = false;

		while (botsKey.wasPressed()) {
			openBots = true;
		}

		if (openBots && client.currentScreen == null) {
			client.setScreen(new BotScreen());
		}

		AreaPick.tick(client);
		glowBots(client);
	}

	/** The key the bot window opens and closes with. */
	public static int botsKeyCode() {
		return botsKey.getCode();
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

	/**
	 * A chat line for the mod: #relays connects to the relays listed that are not connected yet,
	 * #formation sends every bot in the world to a spot around the player. False when it is not one.
	 */
	public static boolean command(String line) {
		if (line.equals("#relays")) {
			// For a relay started after the game: knock on the ones listed that are not connected.
			bots();
			relays.connectMissing();
			return true;
		}

		if (BotCommands.handle(MinecraftClient.getInstance(), line)) {
			return true;
		}

		if (line.equals("#formation")) {
			formation(MinecraftClient.getInstance());
			return true;
		}

		return false;
	}

	/**
	 * The spots around the player, one per bot: the centre stands on the first solid block at or
	 * below the player (mid-air too), then the nearest blocks to stand on, 8-connected and up to one
	 * up or down from the spot they are reached from, so they fill a square around it.
	 */
	private static void formation(MinecraftClient client) {
		final List<String> fleet = new ArrayList<String>(bots().getSpawned());
		Collections.sort(fleet);

		BlockPos ground = new BlockPos(client.player);

		while (ground.getY() >= 0 && !solid(client.world, ground)) {
			ground = ground.down();
		}

		if (ground.getY() < 0) {
			chat(client, "formation_err: no solid ground found");
			return;
		}

		final BlockPos centre = ground.up();
		final List<BlockPos> spots = new ArrayList<BlockPos>();
		// One bot per column: never one standing on another.
		final Set<Long> columns = new HashSet<Long>();
		final Set<BlockPos> seen = new HashSet<BlockPos>();
		final ArrayDeque<BlockPos> queue = new ArrayDeque<BlockPos>();
		seen.add(centre);
		queue.add(centre);

		while (!queue.isEmpty() && spots.size() < fleet.size()) {
			final BlockPos spot = queue.poll();

			// The centre always counts, its head room aside: the player is there.
			if (spot != centre && !standable(client.world, spot)) {
				continue;
			}

			if (!columns.add((long) spot.getX() << 32 | spot.getZ() & 0xFFFFFFFFL)) {
				continue;
			}

			spots.add(spot);

			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}

					for (int dy : new int[] {0, 1, -1}) {
						final BlockPos next = spot.add(dx, dy, dz);

						if (seen.add(next)) {
							queue.add(next);
						}
					}
				}
			}
		}

		if (spots.size() < fleet.size()) {
			chat(client, "formation_err: " + spots.size() + " spots around " + centre.getX() + "," + centre.getY() + "," + centre.getZ() + " for " + fleet.size() + " bots");
		}

		final Map<String, BlockPos> assigned = assign(client, fleet, spots);

		for (Map.Entry<String, BlockPos> entry : assigned.entrySet()) {
			final BlockPos spot = entry.getValue();
			bots().gotoSpot(entry.getKey(), spot.getX(), spot.getY(), spot.getZ());
		}
	}

	/**
	 * Each spot to a bot, nearest first: of every bot the player sees and every spot, the closest pair
	 * take each other, then the closest of the rest, and so on; a bot already in the formation keeps
	 * its spot or one by it, the ones far away get those left at its edge. The bots the player does
	 * not see (no position here) take the spots left after, in order. Bots past the spots get none.
	 */
	private static Map<String, BlockPos> assign(MinecraftClient client, List<String> fleet, List<BlockPos> spots) {
		final List<String> seen = new ArrayList<String>();
		final List<PlayerEntity> players = new ArrayList<PlayerEntity>();
		final List<String> unseen = new ArrayList<String>();

		for (String bot : fleet) {
			final PlayerEntity player = client.world.getPlayerByName(bot);

			if (player == null) {
				unseen.add(bot);
			} else {
				seen.add(bot);
				players.add(player);
			}
		}

		// Every pair, by distance: {distance squared, bot, spot}.
		final List<double[]> pairs = new ArrayList<double[]>(seen.size() * spots.size());

		for (int b = 0; b < seen.size(); b++) {
			for (int s = 0; s < spots.size(); s++) {
				final BlockPos spot = spots.get(s);
				pairs.add(new double[] {players.get(b).squaredDistanceTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5), b, s});
			}
		}

		pairs.sort((x, y) -> Double.compare(x[0], y[0]));
		final Map<String, BlockPos> assigned = new java.util.LinkedHashMap<String, BlockPos>();
		final boolean[] taken = new boolean[spots.size()];

		for (double[] pair : pairs) {
			final String bot = seen.get((int) pair[1]);
			final int spot = (int) pair[2];

			if (taken[spot] || assigned.containsKey(bot)) {
				continue;
			}

			taken[spot] = true;
			assigned.put(bot, spots.get(spot));
		}

		int next = 0;

		for (String bot : unseen) {
			while (next < spots.size() && taken[next]) {
				next++;
			}

			if (next == spots.size()) {
				break;
			}

			taken[next] = true;
			assigned.put(bot, spots.get(next));
		}

		return assigned;
	}

	private static boolean solid(World world, BlockPos pos) {
		return world.getBlockState(pos).getMaterial().blocksMovement();
	}

	/** Solid ground under it, room for the feet and the head. */
	private static boolean standable(World world, BlockPos pos) {
		return solid(world, pos.down()) && !solid(world, pos) && !solid(world, pos.up());
	}

	private static void chat(MinecraftClient client, String message) {
		client.inGameHud.getChatHud().addMessage(new LiteralText(message));
	}

	private static void leaveWorld() {
		if (areas != null) {
			areas.save();
			areas = null;
			areasWorldId = null;
		}

		if (bots != null) {
			// The last profile with the bots still in: called every tick outside a world, so only while there are some.
			if (!bots.getBots().isEmpty()) {
				profile.request("leaving the world");
			}

			bots.stopAll();
		}

		AreaPick.reset();
		TargetPick.reset();
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

	/** The chests of the areas of the world being played, reloaded when the world changes; null outside a world. */
	public static synchronized ChestStore chests() {
		final MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null) {
			return null;
		}

		final String worldId = worldId(client);

		if (worldId == null) {
			return null;
		}

		if (!worldId.equals(chestsWorldId)) {
			chests = new ChestStore(new File(dataDirectory(client), worldId + "/chests.tsv"));
			chestsWorldId = worldId;
		}

		return chests;
	}

	/** The grabs of the bots; created with the bots. */
	static Grab grab() {
		bots();
		return grab;
	}

	public static BotRegistry bots() {
		if (bots == null) {
			final File directory = dataDirectory(MinecraftClient.getInstance());
			final BotManager fleet = new BotManager(new File(directory, "bot"));
			fleet.setReaderFailure(e -> MinecraftClient.getInstance().submit(() -> chat(MinecraftClient.getInstance(), "[MapMcBot] the fleet reader died, its bots' state is stale until restart: " + e.getMessage())));
			relays = new RelayHub(new File(directory, "relays.txt"));
			botProfiles = new BotProfileStore(new File(directory, "bot_profiles.tsv"));
			seedProfiles();
			bots = new BotRegistry(fleet, relays, new NameList(botProfiles.getProfiles().stream().map(BotProfile::getName).collect(Collectors.toList())), botProfiles);
			job = new JobRunner(bots, new JobStore(new File(directory, "job.txt")));
			chunks = new ChunkRegistry(fleet, new ChunkSnapshotStore(new File(directory, "chunks")));
			grab = new Grab(bots, new ChestTracker(bots));
			job.setResolver(grab);
			profile = new ProfileRegistry(fleet, relays, new File(directory, "bot/profile.log"), chunks::chunksByBot);
			relays.start();
		}

		return bots;
	}

	/** The bots' profiles, kept on disk; created with the bots. */
	public static BotProfileStore botProfiles() {
		bots();
		return botProfiles;
	}

	/** The first run's profiles: PandaBot1 to PandaBot4, cracked, each in the skin of a real player. */
	private static void seedProfiles() {
		if (!botProfiles.getProfiles().isEmpty()) {
			return;
		}

		for (int i = 0; i < SEED_SKINS.length; i++) {
			botProfiles.add(new BotProfile(NAME_PREFIX + (i + 1), null, SEED_SKINS[i], DEFAULT_CRACKED_PASSWORD, null));
		}
	}

	/** The relays connected; created with the bots. */
	public static RelayHub relays() {
		bots();
		return relays;
	}

	/** CPU and memory of the mod and the fleet (see ProfilerScreen); created with the bots. */
	public static ProfileRegistry profile() {
		bots();
		return profile;
	}

	/** The bots, or null while none was ever started. */
	/** The grabs, null before the bots are created. */
	static Grab grabOrNull() {
		return grab;
	}

	public static BotRegistry botsOrNull() {
		return bots;
	}

	/** The job and what runs it; created with the bots. */
	public static JobRunner job() {
		bots();
		return job;
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
