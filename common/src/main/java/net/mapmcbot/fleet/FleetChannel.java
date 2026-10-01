package net.mapmcbot.fleet;

import java.io.File;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkListener;

/**
 * The connection to the bot fleet, both ways: the orders the mod sends it and, through one listener
 * per domain, what it reports: a local socket for the messages, the columns file for the chunks'
 * blocks.
 */
public interface FleetChannel {
	/** Once, before anything arrives. */
	void setChunkListener(ChunkListener listener);

	/** Once, before anything arrives. */
	void setBotListener(BotListener listener);

	/** Starts a bot that joins host:port as `bot`. */
	void spawn(String bot, String host, int port);

	void quit(String bot);

	/**
	 * The bot walks to x,y,z, or next to it when another bot holds that block. `id` is the
	 * formation's, the same for every bot sent to it and higher than any before.
	 */
	void formation(String bot, int id, int x, int y, int z);

	/** The columns file the fleet loads every chunk into, one slot each (see FleetProtocol). */
	File columns();

	/** Hands an unloaded chunk's slot back to the fleet; `bot` is the claim's, for the logs. */
	void release(String bot, int slot);

	/** The snapshot asked for is in the slot (mcVersion its version), or there is none (null). */
	void loaded(ChunkKey key, int slot, String mcVersion);
}
