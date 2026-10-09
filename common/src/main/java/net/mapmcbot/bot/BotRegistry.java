package net.mapmcbot.bot;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.mapmcbot.fleet.FleetChannel;

/**
 * The bots of the fleet and what each is doing, as the fleet reports it; the orders for them go
 * out through here too, so a bot is known from the moment it is asked for.
 *
 * Thread-safe: the channel thread feeds it, anyone may read it.
 *
 * SINGLETON: one per game, as the fleet; a second registry throws.
 */
public final class BotRegistry implements BotListener {
	private static final AtomicBoolean CREATED = new AtomicBoolean();

	private final FleetChannel channel;

	/** The relays; their bots are run there, the orders go through it. */
	private final RelayHub relays;

	/** The names for the bots: the mod picks them, the fleets are only told. */
	private final NameList names;

	/** The bots spawned and not yet gone. */
	private final Set<String> bots = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	/** The fleet running each bot spawned and not yet gone: 0 the mod's own, else a relay's rid. */
	private final Map<String, Integer> fleetOf = new ConcurrentHashMap<String, Integer>();

	/** The server each bot spawned and not yet gone joins: where another fleet sends it if its relay fails to start. */
	private final Map<String, InetSocketAddress> serverOf = new ConcurrentHashMap<String, InetSocketAddress>();

	/** The bots in the world (BOT_SPAWNED) and not yet gone. */
	private final Set<String> spawned = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	/** The path each walking bot is on, from its PATH frames. */
	private final Map<String, int[]> paths = new ConcurrentHashMap<String, int[]>();

	/** What each bot is doing, from its STATE frames. */
	private final Map<String, BotStatus> statuses = new ConcurrentHashMap<String, BotStatus>();

	/** The window each bot has open, from its WINDOW frames. */
	private final Map<String, BotWindow> windows = new ConcurrentHashMap<String, BotWindow>();

	/** Each bot's inventory window, from its INVENTORY frames. */
	private final Map<String, BotInventory> inventories = new ConcurrentHashMap<String, BotInventory>();

	/** Hears each inventory as it comes (the bot window, while it is open); called on the channel's thread. */
	public interface InventoryListener {
		void onInventory(String bot, BotInventory inventory);
	}

	private volatile InventoryListener inventoryListener;

	/** The id of the last click sent to each bot's inventory window, counting up from 1. */
	private final Map<String, Integer> clicksSent = new ConcurrentHashMap<String, Integer>();

	public BotRegistry(FleetChannel channel, RelayHub relays, NameList names) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("BotRegistry is a singleton: one was already created");
		}

		this.channel = channel;
		this.names = names;
		this.relays = relays;
		channel.setBotListener(this);
		relays.setBots(this);
	}

	public Set<String> getBots() {
		return Collections.unmodifiableSet(bots);
	}

	/** The bots in the world, ready for orders. */
	public Set<String> getSpawned() {
		return Collections.unmodifiableSet(spawned);
	}

	/** connecting, or what the bot is doing (see BotStatus). */
	public String getStatus(String bot) {
		if (!spawned.contains(bot)) {
			return "connecting";
		}

		return statuses.getOrDefault(bot, BotStatus.IDLE).toString();
	}

	/** What the bot is doing; idle before its first report. */
	public BotStatus getState(String bot) {
		return statuses.getOrDefault(bot, BotStatus.IDLE);
	}

	/** The window the bot has open, null for none. */
	public BotWindow getWindow(String bot) {
		return windows.get(bot);
	}

	/** A primitive for the bot as JSON, on the fleet running it (see FleetProtocol#ACTION). */
	public void action(String bot, String json) {
		final Integer rid = fleetOf.get(bot);

		if (rid == null) {
			throw new IllegalStateException("no bot named " + bot + " to order " + json);
		}

		if (!spawned.contains(bot)) {
			throw new IllegalStateException(bot + " is not in the world yet to order " + json);
		}

		if (rid == 0) {
			channel.action(bot, json);
		} else {
			relays.action(rid, bot, json);
		}
	}

	/** The bot's inventory window as last reported, null before its first. */
	public BotInventory getInventory(String bot) {
		return inventories.get(bot);
	}

	/** The one listener of the inventories as they come, null for none. */
	public void setInventoryListener(InventoryListener listener) {
		inventoryListener = listener;
	}

	/** How many bots the fleet runs (0 the mod's own, else a relay's rid), those still connecting too. */
	public int countOn(int rid) {
		int count = 0;

		for (int fleet : fleetOf.values()) {
			if (fleet == rid) {
				count++;
			}
		}

		return count;
	}

	/** Where the bot runs: 0 the mod's own fleet, else a relay's rid; null for no such bot. */
	public Integer getFleet(String bot) {
		return fleetOf.get(bot);
	}

	/** A click on the bot's inventory window, on the fleet running it (see FleetProtocol#WINDOW_CLICK). */
	public void windowClick(String bot, int slot, int button, int mode) {
		final Integer rid = fleetOf.get(bot);

		if (rid == null) {
			throw new IllegalStateException("no bot named " + bot + " to click slot " + slot + " of");
		}

		final int id = clicksSent.merge(bot, 1, Integer::sum);

		if (rid == 0) {
			channel.windowClick(bot, slot, button, mode, id);
		} else {
			relays.windowClick(rid, bot, slot, button, mode, id);
		}
	}

	/** The villager trade the bot's open window shows, on the fleet running it (see FleetProtocol#TRADE_SELECT). */
	public void tradeSelect(String bot, int trade) {
		final Integer rid = fleetOf.get(bot);

		if (rid == null) {
			throw new IllegalStateException("no bot named " + bot + " to pick trade " + trade + " for");
		}

		if (rid == 0) {
			channel.tradeSelect(bot, trade);
		} else {
			relays.tradeSelect(rid, bot, trade);
		}
	}

	/**
	 * Whether the inventory has every click sent to the bot done: one older than the clicks after it
	 * would undo what the window already shows of them, until the next one comes.
	 */
	public boolean coversClicks(String bot, BotInventory inventory) {
		return inventory.getLastClick() >= clicksSent.getOrDefault(bot, 0);
	}

	/** The paths being walked, by bot: {target x, y, z, node x, y, z, ...}. */
	public Map<String, int[]> getPaths() {
		return Collections.unmodifiableMap(paths);
	}

	/**
	 * Starts a bot that joins host:port in the background, named by the name list, on the fleet
	 * (the mod's own or a relay, a starting one too) with the fewest bots; returns the name.
	 * Synchronized with a relay failing or closing, so its rid is never picked as it goes.
	 */
	public synchronized String create(String host, int port) {
		final String name = names.take();

		try {
			if (!bots.add(name)) {
				throw new IllegalStateException("a bot named " + name + " is already running");
			}

			serverOf.put(name, InetSocketAddress.createUnresolved(host, port));
			spawnOn(leastLoadedFleet(), name);
		} catch (RuntimeException e) {
			if (bots.remove(name)) {
				fleetOf.remove(name);
				serverOf.remove(name);
				names.give(name);
			}

			throw e;
		}

		return name;
	}

	private void spawnOn(int rid, String name) {
		final InetSocketAddress server = serverOf.get(name);
		fleetOf.put(name, rid);

		if (rid == 0) {
			channel.spawn(name, server.getHostString(), server.getPort());
		} else {
			relays.spawn(rid, name, server.getHostString(), server.getPort());
		}
	}

	/** The rid of the fleet running the fewest bots, the lowest on a tie: 0 is the mod's own. */
	private int leastLoadedFleet() {
		final Map<Integer, Integer> counts = new java.util.TreeMap<Integer, Integer>();
		counts.put(0, 0);

		for (int rid : relays.rids()) {
			counts.put(rid, 0);
		}

		for (int rid : fleetOf.values()) {
			if (counts.containsKey(rid)) {
				counts.put(rid, counts.get(rid) + 1);
			}
		}

		int best = 0;

		for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
			if (entry.getValue() < counts.get(best)) {
				best = entry.getKey();
			}
		}

		return best;
	}

	/** The bot walks to stand at x, y, z, on the fleet running it. */
	public void gotoSpot(String bot, int x, int y, int z) {
		final Integer rid = fleetOf.get(bot);

		if (rid == null) {
			throw new IllegalStateException("no bot named " + bot + " to send to " + x + "," + y + "," + z);
		}

		if (rid == 0) {
			channel.gotoSpot(bot, x, y, z);
		} else {
			relays.gotoSpot(rid, bot, x, y, z);
		}
	}

	/** Each bot leaves; the fleet unloads each chunk once no bot holds it. The fleets stay up. */
	public synchronized void stopAll() {
		for (String bot : bots) {
			final int rid = fleetOf.get(bot);

			if (rid == 0) {
				channel.quit(bot);
			} else {
				relays.quit(rid, bot);
			}
		}

		clear();
	}

	@Override
	public void onSpawned(String bot) {
		spawned.add(bot);
	}

	@Override
	public void onPath(String bot, int[] path) {
		paths.put(bot, path);
	}

	@Override
	public void onInventory(String bot, BotInventory inventory) {
		inventories.put(bot, inventory);
		final InventoryListener listener = inventoryListener;

		if (listener != null) {
			listener.onInventory(bot, inventory);
		}
	}

	@Override
	public void onState(String bot, BotStatus status) {
		statuses.put(bot, status);
	}

	@Override
	public void onWindow(String bot, BotWindow window) {
		if (window == null) {
			windows.remove(bot);
		} else {
			windows.put(bot, window);
		}
	}

	@Override
	public void onPathCleared(String bot) {
		paths.remove(bot);
	}

	@Override
	public void onGone(String bot) {
		if (bots.remove(bot)) {
			names.give(bot);
		}

		fleetOf.remove(bot);
		serverOf.remove(bot);

		spawned.remove(bot);
		paths.remove(bot);
		inventories.remove(bot);
		clicksSent.remove(bot);
		statuses.remove(bot);
		windows.remove(bot);
	}

	/** The mod's own fleet is gone: its bots with it. */
	@Override
	public void onClosed() {
		clearFleet(0);
	}

	/** A relay failed before its WELCOME (the hub holding this registry's lock): its bots never got there and go to the fleet with the fewest. */
	void onRelayFailed(int rid) {
		for (Map.Entry<String, Integer> entry : fleetOf.entrySet()) {
			if (entry.getValue() == rid) {
				spawnOn(leastLoadedFleet(), entry.getKey());
			}
		}
	}

	/** A relay is gone: its bots with it. */
	void onRelayClosed(int rid) {
		clearFleet(rid);
	}

	private void clearFleet(int rid) {
		for (Map.Entry<String, Integer> entry : fleetOf.entrySet()) {
			if (entry.getValue() != rid) {
				continue;
			}

			final String bot = entry.getKey();
			fleetOf.remove(bot);
			serverOf.remove(bot);
			spawned.remove(bot);
			paths.remove(bot);
			inventories.remove(bot);
			clicksSent.remove(bot);
			statuses.remove(bot);
			windows.remove(bot);

			if (bots.remove(bot)) {
				names.give(bot);
			}
		}
	}

	private void clear() {
		names.giveAll();
		bots.clear();
		fleetOf.clear();
		serverOf.clear();
		spawned.clear();
		paths.clear();
		inventories.clear();
		clicksSent.clear();
		statuses.clear();
		windows.clear();
	}
}
