package net.mapmcbot.bot;

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

	/** The bots spawned and not yet gone. */
	private final Set<String> bots = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	/** The bots in the world (BOT_SPAWNED) and not yet gone. */
	private final Set<String> spawned = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	/** The path each walking bot is on, from its PATH frames. */
	private final Map<String, int[]> paths = new ConcurrentHashMap<String, int[]>();

	/** The walking bots with no path to walk until their search sends the next. */
	private final Set<String> waiting = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	public BotRegistry(FleetChannel channel) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("BotRegistry is a singleton: one was already created");
		}

		this.channel = channel;
		channel.setBotListener(this);
	}

	public Set<String> getBots() {
		return Collections.unmodifiableSet(bots);
	}

	/** connecting, idle, walking or waiting instruction. */
	public String getStatus(String bot) {
		if (!spawned.contains(bot)) {
			return "connecting";
		}

		if (!paths.containsKey(bot)) {
			return "idle";
		}

		return waiting.contains(bot) ? "waiting instruction" : "walking";
	}

	/** The paths being walked, by bot: {target x, y, z, node x, y, z, ...}. */
	public Map<String, int[]> getPaths() {
		return Collections.unmodifiableMap(paths);
	}

	/** Starts a bot that joins host:port as `name`, in the background. */
	public void create(String name, String host, int port) {
		if (!bots.add(name)) {
			throw new IllegalStateException("a bot named " + name + " is already running");
		}

		channel.spawn(name, host, port);
	}

	/** See {@link FleetChannel#formation}. */
	public void formation(String name, int id, int x, int y, int z) {
		if (!bots.contains(name)) {
			throw new IllegalStateException("formation for " + name + ": no such bot");
		}

		channel.formation(name, id, x, y, z);
	}

	/** Each bot leaves; the fleet unloads each chunk once no bot holds it. The fleet stays up. */
	public void stopAll() {
		for (String bot : bots) {
			channel.quit(bot);
		}

		clear();
	}

	@Override
	public void onSpawned(String bot) {
		spawned.add(bot);
	}

	@Override
	public void onPath(String bot, int[] path, boolean isWaiting) {
		paths.put(bot, path);

		if (isWaiting) {
			waiting.add(bot);
		} else {
			waiting.remove(bot);
		}
	}

	@Override
	public void onPathCleared(String bot) {
		paths.remove(bot);
		waiting.remove(bot);
	}

	@Override
	public void onGone(String bot) {
		bots.remove(bot);
		spawned.remove(bot);
		paths.remove(bot);
		waiting.remove(bot);
	}

	@Override
	public void onClosed() {
		clear();
	}

	private void clear() {
		bots.clear();
		spawned.clear();
		paths.clear();
		waiting.clear();
	}
}
