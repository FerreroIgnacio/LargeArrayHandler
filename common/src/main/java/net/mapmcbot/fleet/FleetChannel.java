package net.mapmcbot.fleet;

import java.io.File;
import java.util.List;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;
import net.mapmcbot.profile.ProfileReport;

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

	/** Once, before anything arrives. */
	void setProfileListener(ProfileListener listener);

	/** Whether the fleet is up: its first bot starts it. */
	boolean isRunning();

	/** Asks the fleet for its profile; it comes back as a PROFILE of the same id. Only while running. */
	void requestProfile(int id, String reason);

	/**
	 * What the mod's side of the channel spent on each bot's frames so far (scope java.bot:name), and
	 * on the fleet's own (scope java.channel): CPU and allocations on the reader thread, frames and bytes.
	 */
	List<ProfileReport.Row> channelRows();

	/** Starts a bot that joins host:port as `bot`. */
	void spawn(String bot, String host, int port);

	void quit(String bot);

	/** The columns file the fleet loads every chunk into, one slot each (see FleetProtocol). */
	File columns();

	/** Hands an unloaded chunk's slot back to the fleet; `bot` is the claim's, for the logs. */
	void release(String bot, int slot);

	/** The snapshot asked for is in the slot (mcVersion its version), or there is none (null). */
	void loaded(ChunkKey key, int slot, String mcVersion);
}
