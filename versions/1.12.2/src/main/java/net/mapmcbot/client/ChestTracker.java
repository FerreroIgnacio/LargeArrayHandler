package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mapmcbot.area.Area;
import net.mapmcbot.area.AreaStore;
import net.mapmcbot.area.ChestStore;
import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;
import net.mapmcbot.bot.BotWindow;
import net.mapmcbot.chunk.ChunkKey;
import net.minecraft.client.MinecraftClient;

/**
 * Keeps the chests of the areas as the bots see them (see ChestStore). The window a bot opens is the
 * chest it last right-clicked; its contents are checked against the ones known when the chest is
 * clean (a difference is an error: something the mod does not see changed it), then kept, and kept
 * again on each change while the bot has it open. A chest turns dirty when a fleet lets its column
 * go, or when more players have it open than the bots that opened it (a player is in it).
 *
 * Where the chests are comes from every fleet, the mod's and the relays' alike (CHEST, COLUMN_GONE):
 * an index of the chests in the columns they hold, by column. Only chests inside an area are kept.
 */
final class ChestTracker implements BotRegistry.ActionListener, BotRegistry.WindowListener, BotRegistry.StateListener, BotRegistry.LidListener, BotRegistry.ChestListener {
	static final Set<String> CHEST_BLOCKS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("minecraft:chest", "minecraft:trapped_chest")));

	private final BotRegistry bots;

	/** The chests in the columns the fleets hold: column -> "x,y,z" -> its block. */
	private final Map<ChunkKey, Map<String, String>> index = new ConcurrentHashMap<ChunkKey, Map<String, String>>();

	/** The block each bot right-clicked last, its window not come yet. */
	private final Map<String, int[]> pending = new ConcurrentHashMap<String, int[]>();
	/** The chest each bot has open. */
	private final Map<String, ChestStore.Chest> open = new ConcurrentHashMap<String, ChestStore.Chest>();

	ChestTracker(BotRegistry bots) {
		this.bots = bots;
		bots.addActionListener(this);
		bots.addWindowListener(this);
		bots.addStateListener(this);
		bots.addLidListener(this);
		bots.addChestListener(this);
	}

	/** The chests of the world being played; null outside a world. */
	private static ChestStore store() {
		return MapMcBotClient.chests();
	}

	@Override
	public void onAction(String bot, String json, boolean internal) {
		// Another order: the block right-clicked before opened no window.
		pending.remove(bot);

		if (!json.contains("\"interact\"")) {
			return;
		}

		final JsonObject a = new JsonParser().parse(json).getAsJsonObject();
		final JsonObject target = a.getAsJsonObject("target");

		if (a.get("action").getAsString().equals("interact") && "right".equals(a.get("button").getAsString()) && target != null && "block".equals(target.get("kind").getAsString())) {
			pending.put(bot, new int[] {target.get("x").getAsInt(), target.get("y").getAsInt(), target.get("z").getAsInt()});
		}
	}

	@Override
	public void onState(String bot, BotStatus status) {
		// The interact failed: no window will come. Done, its window may still be on its way.
		if (status.getKind() == BotStatus.Kind.ERROR) {
			pending.remove(bot);
		}
	}

	@Override
	public void onWindow(String bot, BotWindow window) {
		if (window == null) {
			open.remove(bot);
			return;
		}

		final ChestStore store = store();

		if (store == null) {
			return;
		}

		final List<BotInventory.Item> contents = window.getSlots().subList(0, window.getContainerSlots());
		// A block right-clicked since: its window, in place of the one open.
		final int[] clicked = pending.remove(bot);

		if (clicked == null) {
			final ChestStore.Chest already = open.get(bot);

			if (already != null) {
				store.see(already, contents);
			}

			return;
		}

		open.remove(bot);

		final String server = bots.getServer(bot);
		final String block = chestAt(server, clicked);

		if (block == null) {
			return;
		}

		final List<int[]> blocks = new ArrayList<int[]>();
		blocks.add(clicked);

		if (window.getContainerSlots() == 54) {
			final int[] partner = partnerOf(server, clicked, block);

			if (partner == null) {
				throw new IllegalStateException(bot + " opened a double chest at " + at(clicked) + " with no " + block + " beside it");
			}

			blocks.add(partner);
		} else if (window.getContainerSlots() != 27) {
			throw new IllegalStateException(bot + " opened " + block + " at " + at(clicked) + " as a window of " + window.getContainerSlots() + " slots, not a chest's 27 or 54");
		}

		if (!inAnArea(blocks)) {
			return;
		}

		final ChestStore.Chest chest = store.chestOf(blocks);

		if (!chest.isDirty()) {
			final String difference = difference(chest.getContents(), contents);

			if (difference != null) {
				throw new IllegalStateException(bot + " opened " + chest + ", clean, and found it changed: " + difference);
			}
		}

		store.see(chest, contents);
		open.put(bot, chest);
	}

	@Override
	public void onChestLid(String bot, int x, int y, int z, int viewers) {
		final ChestStore store = store();
		final ChestStore.Chest chest = store == null ? null : store.at(x, y, z);

		if (chest == null || chest.isDirty()) {
			return;
		}

		// The bots that have it open, or are opening it.
		int ours = 0;

		for (String other : bots.getBots()) {
			final int[] clicked = pending.get(other);

			if (open.get(other) == chest || (clicked != null && store.at(clicked[0], clicked[1], clicked[2]) == chest)) {
				ours++;
			}
		}

		if (viewers > ours) {
			store.markDirty(chest);
		}
	}

	@Override
	public void onChest(String bot, ChunkKey key, int x, int y, int z, String block) {
		final String at = x + "," + y + "," + z;

		if (block.isEmpty()) {
			final Map<String, String> chests = index.get(key);

			if (chests != null) {
				chests.remove(at);
			}

			// Broken: gone from the store too, a double chest's other half with it (found again as one).
			final ChestStore store = store();
			final ChestStore.Chest known = store == null ? null : store.at(x, y, z);

			if (known != null) {
				store.remove(known);
			}

			return;
		}

		if (!CHEST_BLOCKS.contains(block)) {
			throw new IllegalStateException(bot + " reported a chest at " + at + " that is " + block);
		}

		index.computeIfAbsent(key, k -> new ConcurrentHashMap<String, String>()).put(at, block);
	}

	@Override
	public void onColumnGone(String bot, ChunkKey key) {
		index.remove(key);
		final ChestStore store = store();

		if (store == null) {
			return;
		}

		for (ChestStore.Chest chest : store.all()) {
			for (int[] block : chest.getBlocks()) {
				if (block[0] >> 4 == key.getX() && block[2] >> 4 == key.getZ()) {
					store.markDirty(chest);
					break;
				}
			}
		}
	}

	/**
	 * The chests of the area in the columns the fleets of the bots hold, into the store: the new ones
	 * with no contents yet. One known is left as it is, contents and all.
	 */
	void scan(Area area) {
		final ChestStore store = store();
		final Set<String> servers = new HashSet<String>();

		for (String bot : bots.getSpawned()) {
			servers.add(bots.getServer(bot));
		}

		final String dimension = dimension();

		for (Map.Entry<ChunkKey, Map<String, String>> column : index.entrySet()) {
			final ChunkKey key = column.getKey();

			if (!servers.contains(key.getServer()) || !key.getDimension().equals(dimension)
					|| (key.getX() << 4) + 15 < area.getMinX() || key.getX() << 4 > area.getMaxX()
					|| (key.getZ() << 4) + 15 < area.getMinZ() || key.getZ() << 4 > area.getMaxZ()) {
				continue;
			}

			for (Map.Entry<String, String> chest : column.getValue().entrySet()) {
				final String[] xyz = chest.getKey().split(",");
				final int[] block = {Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])};

				if (!area.contains(block[0], block[1], block[2]) || store.at(block[0], block[1], block[2]) != null) {
					continue;
				}

				final List<int[]> blocks = new ArrayList<int[]>();
				blocks.add(block);
				final int[] partner = partnerOf(key.getServer(), block, chest.getValue());

				if (partner != null) {
					blocks.add(partner);
				}

				store.chestOf(blocks);
			}
		}
	}

	/** The other half of a double chest: the one chest of the same block beside it, side by side; null for none. */
	private int[] partnerOf(String server, int[] block, String name) {
		final int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		int[] found = null;

		for (int[] side : sides) {
			final int[] next = {block[0] + side[0], block[1], block[2] + side[1]};

			if (name.equals(chestAt(server, next))) {
				if (found != null) {
					throw new IllegalStateException(name + " at " + at(block) + " has two of it beside it: " + at(found) + " and " + at(next));
				}

				found = next;
			}
		}

		return found;
	}

	/** The chest block at the spot as the fleets reported it, null for none known. */
	private String chestAt(String server, int[] block) {
		final Map<String, String> chests = index.get(new ChunkKey(server, dimension(), block[0] >> 4, block[2] >> 4));
		return chests == null ? null : chests.get(at(block));
	}

	/** The dimension being played, as the fleet keys its columns. */
	private static String dimension() {
		return "minecraft:" + MinecraftClient.getInstance().world.dimension.getDimensionType().getName();
	}

	private static boolean inAnArea(List<int[]> blocks) {
		final AreaStore areas = MapMcBotClient.areas();

		for (Area area : areas.getAreas()) {
			for (int[] block : blocks) {
				if (area.contains(block[0], block[1], block[2])) {
					return true;
				}
			}
		}

		return false;
	}

	static String at(int[] block) {
		return block[0] + "," + block[1] + "," + block[2];
	}

	/** The first slot where the contents known and the ones found differ, null when they are the same. */
	private static String difference(List<BotInventory.Item> known, List<BotInventory.Item> found) {
		if (known.size() != found.size()) {
			return known.size() + " slots known, " + found.size() + " found";
		}

		for (int i = 0; i < known.size(); i++) {
			if (!sameItem(known.get(i), found.get(i))) {
				return "slot " + i + " was " + describe(known.get(i)) + ", is " + describe(found.get(i));
			}
		}

		return null;
	}

	static boolean sameItem(BotInventory.Item a, BotInventory.Item b) {
		if (a == null || b == null) {
			return a == b;
		}

		return a.getName().equals(b.getName()) && a.getCount() == b.getCount() && a.getMetadata() == b.getMetadata() && Arrays.equals(a.getNbt(), b.getNbt());
	}

	static String describe(BotInventory.Item item) {
		return item == null ? "nothing" : item.getCount() + " x " + item.getName();
	}
}
