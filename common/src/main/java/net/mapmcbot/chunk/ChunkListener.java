package net.mapmcbot.chunk;

/**
 * The chunks the fleet reports, called on the channel's thread. A chunk is claimed once when the
 * fleet's first bot gets it and unloaded once when its last lets it go, whichever bots hold it in
 * between; `bot` is only the one that reported it. Its blocks are in its slot of the columns file,
 * never in a message.
 */
public interface ChunkListener {
	/** state id (id << 4 | meta) -> its name, null for ids no slot holds. Once, before any claim. */
	void onStateNames(String[] names);

	/** `claim` is the fleet's id for this load of the chunk, loaded into `slot` of the columns file. */
	void onClaim(String bot, ChunkKey key, int claim, int slot, String mcVersion);

	/**
	 * A chunk no bot holds, wanted by the fleet's path searches: its snapshot goes into the free
	 * `slot`, answered with FleetChannel#loaded either way.
	 */
	void onLoadSnapshot(ChunkKey key, int slot);

	/** The slot holds the chunk claimed as `claim`. */
	void onReady(String bot, ChunkKey key, int claim);

	/** The readied chunk's blocks or block entities changed (several changes at once, maybe). */
	void onChanged(String bot, ChunkKey key);

	/** World coordinates. */
	void onBlockEntityUpdate(String bot, ChunkKey key, int x, int y, int z, String type, byte[] nbt);

	void onBlockEntityRemove(String bot, ChunkKey key, int x, int y, int z);

	/** The slot is the listener's until it releases it (FleetChannel#release). */
	void onUnload(String bot, ChunkKey key);

	/** Every bot is gone at once. */
	void onClosed();
}
