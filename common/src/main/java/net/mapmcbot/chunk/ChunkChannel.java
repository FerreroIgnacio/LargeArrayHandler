package net.mapmcbot.chunk;

import java.util.List;

/**
 * The transport between the bots and the registry. Today a local socket that copies every chunk;
 * later shared memory, behind this same interface.
 */
public interface ChunkChannel {
	/** Once, before anything arrives. */
	void setListener(Listener listener);

	/** Asks `bot` to serialize and send the chunk it claimed with id `claim`. */
	void requestChunk(String bot, ChunkKey key, int claim);

	/** What the bots report; called on the channel's thread, in the order each bot sent it. */
	interface Listener {
		/** `claim` is the bot's id for this load of the chunk, echoed back in requestChunk. */
		void onClaim(String bot, ChunkKey key, int claim);

		void onChunkData(String bot, ChunkKey key, String mcVersion, ChunkData data);

		/** World coordinates; a multi block change comes as one list. */
		void onBlockUpdate(String bot, ChunkKey key, List<BlockChange> changes);

		/** World coordinates. */
		void onBlockEntityUpdate(String bot, ChunkKey key, int x, int y, int z, String type, byte[] nbt);

		void onBlockEntityRemove(String bot, ChunkKey key, int x, int y, int z);

		void onUnload(String bot, ChunkKey key);

		/** The bot disconnected: it no longer holds any chunk. */
		void onBotGone(String bot);

		/** Every bot is gone at once. */
		void onClosed();
	}
}
