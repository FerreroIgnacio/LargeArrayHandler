package net.mapmcbot.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chunks some bot has loaded, shared by all of them.
 *
 * The first bot to claim a chunk owns it and is asked for its blocks; later claims only count the
 * bot in. When the owner unloads, the ownership passes to another holder (asked for the blocks in
 * turn if they never came). When the last holder unloads, the chunk leaves and its snapshot goes
 * to disk.
 *
 * Every holder forwards its block updates; one already applied changes nothing. While the blocks
 * are still on their way, updates are dropped: the owner serializes its world once asked, so they
 * are in what it sends.
 *
 * Thread-safe: the channel thread feeds it, anyone may read it.
 */
public final class ChunkRegistry implements ChunkChannel.Listener {
	private final ChunkChannel channel;
	private final ChunkSnapshotStore snapshots;
	private final Map<ChunkKey, Entry> chunks = new HashMap<ChunkKey, Entry>();

	public ChunkRegistry(ChunkChannel channel, ChunkSnapshotStore snapshots) {
		this.channel = channel;
		this.snapshots = snapshots;
		channel.setListener(this);
	}

	/** Whether the chunk is held and its blocks have arrived. */
	public synchronized boolean isLoaded(ChunkKey key) {
		final Entry entry = chunks.get(key);
		return entry != null && entry.data != null;
	}

	public synchronized String getOwner(ChunkKey key) {
		return entry(key).owner;
	}

	/** World coordinates. */
	public synchronized String getState(ChunkKey key, int x, int y, int z) {
		return loaded(key).getState(local(x, key.getX()), y, local(z, key.getZ()));
	}

	/** World coordinates; null when there is no block entity there. */
	public synchronized ChunkData.BlockEntity getBlockEntity(ChunkKey key, int x, int y, int z) {
		return loaded(key).getBlockEntity(local(x, key.getX()), y, local(z, key.getZ()));
	}

	@Override
	public synchronized void onClaim(String bot, ChunkKey key, int claim) {
		Entry entry = chunks.get(key);

		if (entry == null) {
			entry = new Entry(bot);
			entry.holders.put(bot, claim);
			chunks.put(key, entry);
			channel.requestChunk(bot, key, claim);
			return;
		}

		if (entry.holders.containsKey(bot)) {
			throw new IllegalStateException(bot + " claimed " + key + " while already holding it");
		}

		entry.holders.put(bot, claim);
	}

	@Override
	public synchronized void onChunkData(String bot, ChunkKey key, String mcVersion, ChunkData data) {
		final Entry entry = held(bot, key);

		if (!bot.equals(entry.owner) || entry.data != null) {
			throw new IllegalStateException(bot + " sent " + key + " unasked (owner " + entry.owner
					+ (entry.data != null ? ", already loaded)" : ")"));
		}

		entry.data = data;
		entry.mcVersion = mcVersion;
	}

	@Override
	public synchronized void onBlockUpdate(String bot, ChunkKey key, List<BlockChange> changes) {
		final Entry entry = held(bot, key);

		if (entry.data == null) {
			return;
		}

		for (BlockChange change : changes) {
			entry.data.setState(local(change.getX(), key.getX()), change.getY(), local(change.getZ(), key.getZ()),
					change.getState());
		}
	}

	@Override
	public synchronized void onBlockEntityUpdate(String bot, ChunkKey key, int x, int y, int z, String type, byte[] nbt) {
		final Entry entry = held(bot, key);

		if (entry.data != null) {
			entry.data.putBlockEntity(new ChunkData.BlockEntity(local(x, key.getX()), y, local(z, key.getZ()), type, nbt));
		}
	}

	@Override
	public synchronized void onBlockEntityRemove(String bot, ChunkKey key, int x, int y, int z) {
		final Entry entry = held(bot, key);

		if (entry.data != null) {
			entry.data.removeBlockEntity(local(x, key.getX()), y, local(z, key.getZ()));
		}
	}

	@Override
	public synchronized void onUnload(String bot, ChunkKey key) {
		release(bot, key, held(bot, key));
	}

	@Override
	public synchronized void onBotGone(String bot) {
		for (Map.Entry<ChunkKey, Entry> chunk : new ArrayList<Map.Entry<ChunkKey, Entry>>(chunks.entrySet())) {
			if (chunk.getValue().holders.containsKey(bot)) {
				release(bot, chunk.getKey(), chunk.getValue());
			}
		}
	}

	@Override
	public synchronized void onClosed() {
		for (Map.Entry<ChunkKey, Entry> chunk : chunks.entrySet()) {
			if (chunk.getValue().data != null) {
				snapshots.save(chunk.getKey(), chunk.getValue().mcVersion, chunk.getValue().data);
			}
		}

		chunks.clear();
	}

	private void release(String bot, ChunkKey key, Entry entry) {
		entry.holders.remove(bot);

		if (entry.holders.isEmpty()) {
			chunks.remove(key);

			// Still waiting for its blocks: nothing to keep.
			if (entry.data != null) {
				snapshots.save(key, entry.mcVersion, entry.data);
			}

			return;
		}

		if (bot.equals(entry.owner)) {
			final Map.Entry<String, Integer> next = entry.holders.entrySet().iterator().next();
			entry.owner = next.getKey();

			if (entry.data == null) {
				channel.requestChunk(next.getKey(), key, next.getValue());
			}
		}
	}

	private Entry entry(ChunkKey key) {
		final Entry entry = chunks.get(key);

		if (entry == null) {
			throw new IllegalStateException(key + " is not in the registry");
		}

		return entry;
	}

	private ChunkData loaded(ChunkKey key) {
		final Entry entry = entry(key);

		if (entry.data == null) {
			throw new IllegalStateException(key + " is still waiting for its blocks");
		}

		return entry.data;
	}

	private Entry held(String bot, ChunkKey key) {
		final Entry entry = entry(key);

		if (!entry.holders.containsKey(bot)) {
			throw new IllegalStateException(bot + " does not hold " + key);
		}

		return entry;
	}

	private static int local(int coordinate, int chunk) {
		final int local = coordinate - (chunk << 4);

		if (local < 0 || local > 15) {
			throw new IllegalArgumentException("coordinate " + coordinate + " is outside chunk " + chunk);
		}

		return local;
	}

	private static final class Entry {
		/** Holder to its claim id, in claim order: the next owner is the oldest holder. */
		final Map<String, Integer> holders = new LinkedHashMap<String, Integer>();
		String owner;
		/** Null until the owner's blocks arrive. */
		ChunkData data;
		String mcVersion;

		Entry(String owner) {
			this.owner = owner;
		}
	}
}
