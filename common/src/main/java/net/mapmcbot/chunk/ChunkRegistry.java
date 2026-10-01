package net.mapmcbot.chunk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.mapmcbot.fleet.FleetChannel;
import net.mapmcbot.fleet.FleetProtocol;

/**
 * The chunks the bot fleet has loaded, shared by all of its bots.
 *
 * The fleet claims a chunk once, when its first bot gets it, into a slot of the columns file this
 * registry maps; the blocks are read straight off the slot once the fleet says it is ready, always
 * as the fleet's bots last saw them. Its snapshot goes to disk from the slot then, and again on
 * every change the fleet reports while it is loaded. It unloads it once, when its last bot lets it
 * go: a last snapshot, and the slot back to the fleet. Only the block entities come as messages.
 *
 * The fleet keeps chunks no bot holds in their slot for its path searches, and asks for those a
 * search wanted: their snapshot, from this or an earlier game, goes into the free slot the fleet
 * names. Those are the fleet's, not in the registry.
 *
 * Thread-safe: the channel thread feeds it, anyone may read it.
 *
 * SINGLETON: one per game, holding every server and dimension (ChunkKey carries both); a second
 * registry throws.
 */
public final class ChunkRegistry implements ChunkListener {
	private static final AtomicBoolean CREATED = new AtomicBoolean();

	private static final boolean USE_CHUNK_SNAPSHOT_OPTIMIZATION = false;

	private final FleetChannel channel;
	private final ChunkSnapshotStore snapshots;
	/** The fleet's columns file, little-endian (see FleetProtocol); written only into slots the fleet asks to load. */
	private final ByteBuffer columns;
	private final Map<ChunkKey, Entry> chunks = new HashMap<ChunkKey, Entry>();
	/** State id -> name, from the fleet before any claim. */
	private String[] stateNames;
	/** Name -> its lowest state id, to write snapshots back into slots. */
	private final Map<String, Integer> stateIds = new HashMap<String, Integer>();

	public ChunkRegistry(FleetChannel channel, ChunkSnapshotStore snapshots) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("ChunkRegistry is a singleton: one was already created");
		}

		this.channel = channel;
		this.snapshots = snapshots;
		this.columns = map(channel);
		channel.setChunkListener(this);
	}

	/**
	 * Per bot, the chunks held that it claimed (the fleet's first bot to get each): {chunks, block
	 * entities in them, bytes of their NBT}. For the profile; the blocks are in the columns file, not here.
	 */
	public synchronized Map<String, long[]> chunksByBot() {
		final Map<String, long[]> byBot = new HashMap<String, long[]>();

		for (Entry entry : chunks.values()) {
			long[] counts = byBot.get(entry.bot);

			if (counts == null) {
				counts = new long[3];
				byBot.put(entry.bot, counts);
			}

			counts[0]++;
			counts[1] += entry.blockEntities.size();

			for (ChunkData.BlockEntity entity : entry.blockEntities.values()) {
				counts[2] += entity.getNbt().length;
			}
		}

		return byBot;
	}

	/** Whether the chunk is held and its blocks are in its slot. */
	public synchronized boolean isLoaded(ChunkKey key) {
		final Entry entry = chunks.get(key);
		return entry != null && entry.ready;
	}

	/** World coordinates. */
	public synchronized String getState(ChunkKey key, int x, int y, int z) {
		final Entry entry = loaded(key);
		return ChunkSnapshotStore.stateName(stateNames, stateId(entry, local(x, key.getX()), y, local(z, key.getZ())), key);
	}

	/** World coordinates; null when there is no block entity there, or the block is no longer of its type. */
	public synchronized ChunkData.BlockEntity getBlockEntity(ChunkKey key, int x, int y, int z) {
		final Entry entry = loaded(key);
		final ChunkData.BlockEntity entity = entry.blockEntities.get(blockKey(local(x, key.getX()), y, local(z, key.getZ())));

		if (entity == null || !ChunkData.blockName(getState(key, x, y, z)).equals(entity.getType())) {
			return null;
		}

		return entity;
	}

	@Override
	public synchronized void onStateNames(String[] names) {
		if (stateNames != null) {
			throw new IllegalStateException("the fleet named the state ids twice");
		}

		stateNames = names;

		for (int id = 0; id < names.length; id++) {
			if (names[id] != null && !stateIds.containsKey(names[id])) {
				stateIds.put(names[id], id);
			}
		}
	}

	@Override
	public synchronized void onLoadSnapshot(ChunkKey key, int slot) {
		if (stateNames == null) {
			throw new IllegalStateException(key + " asked for before the fleet named the state ids");
		}

		if (chunks.containsKey(key)) {
			throw new IllegalStateException("the fleet asked for the snapshot of " + key + ", which it holds");
		}

		for (Map.Entry<ChunkKey, Entry> held : chunks.entrySet()) {
			if (held.getValue().slot == slot) {
				throw new IllegalStateException(key + " to be loaded into slot " + slot + ", which " + held.getKey() + " holds");
			}
		}
		if(!USE_CHUNK_SNAPSHOT_OPTIMIZATION){
			channel.loaded(key, slot, null);
			return;
		}

		final ChunkSnapshotStore.Snapshot snapshot = snapshots.load(key);

		if (snapshot == null) {
			channel.loaded(key, slot, null);
			return;
		}

		final ChunkData data = snapshot.getData();

		if (data.getMinY() != 0 || data.getHeight() != 256) {
			throw new IllegalStateException("snapshot of " + key + " is y " + data.getMinY() + " to "
					+ (data.getMinY() + data.getHeight() - 1) + ", a slot holds 0 to 255");
		}

		final int base = slot * FleetProtocol.COLUMN_SLOT_SIZE;

		for (int s = 0; s < data.getSectionCount(); s++) {
			final ChunkData.Section section = data.getSection(s);
			final int[] ids = new int[section.getPalette().size()];

			for (int i = 0; i < ids.length; i++) {
				final String name = section.getPalette().get(i);
				final Integer id = stateIds.get(name);

				if (id == null) {
					throw new IllegalStateException("snapshot of " + key + " holds " + name + ", which has no state id");
				}

				ids[i] = id;
			}

			for (int i = 0; i < ChunkData.SECTION_VOLUME; i++) {
				columns.putShort(base + FleetProtocol.COLUMN_STATES + 2 * (s * ChunkData.SECTION_VOLUME + i),
						(short) ids[section.getIndex(i)]);
			}
		}

		// Snapshots keep no biomes: plains, as the fleet fills them before a column arrives.
		for (int i = 0; i < 256; i++) {
			columns.put(base + FleetProtocol.COLUMN_BIOMES + i, (byte) 1);
		}

		channel.loaded(key, slot, snapshot.getMcVersion());
	}

	@Override
	public synchronized void onClaim(String bot, ChunkKey key, int claim, int slot, String mcVersion) {
		if (stateNames == null) {
			throw new IllegalStateException(key + " claimed (by " + bot + ") before the fleet named the state ids");
		}

		if (chunks.containsKey(key)) {
			throw new IllegalStateException(key + " claimed again (by " + bot + ") without an unload");
		}

		for (Map.Entry<ChunkKey, Entry> held : chunks.entrySet()) {
			if (held.getValue().slot == slot) {
				throw new IllegalStateException(key + " claimed (by " + bot + ") into slot " + slot + ", which " + held.getKey() + " holds");
			}
		}

		chunks.put(key, new Entry(bot, claim, slot, mcVersion));
	}

	@Override
	public synchronized void onReady(String bot, ChunkKey key, int claim) {
		final Entry entry = entry(key);

		if (claim != entry.claim) {
			throw new IllegalStateException(bot + " readied " + key + " as claim " + claim + ", held as claim " + entry.claim);
		}

		if (entry.ready) {
			throw new IllegalStateException(bot + " readied " + key + " (claim " + claim + ") twice");
		}

		entry.ready = true;
		snapshot(key, entry);
	}

	@Override
	public synchronized void onChanged(String bot, ChunkKey key) {
		final Entry entry = entry(key);

		if (!entry.ready) {
			throw new IllegalStateException(bot + " changed " + key + " before it was readied");
		}

		snapshot(key, entry);
	}

	@Override
	public synchronized void onBlockEntityUpdate(String bot, ChunkKey key, int x, int y, int z, String type, byte[] nbt) {
		final int localX = local(x, key.getX());
		final int localZ = local(z, key.getZ());
		entry(key).blockEntities.put(blockKey(localX, y, localZ), new ChunkData.BlockEntity(localX, y, localZ, type, nbt));
	}

	@Override
	public synchronized void onBlockEntityRemove(String bot, ChunkKey key, int x, int y, int z) {
		entry(key).blockEntities.remove(blockKey(local(x, key.getX()), y, local(z, key.getZ())));
	}

	/** The snapshot from the slot, then the slot back to the fleet. */
	@Override
	public synchronized void onUnload(String bot, ChunkKey key) {
		final Entry entry = entry(key);
		chunks.remove(key);

		// Never loaded into its slot: nothing to keep.
		if (entry.ready) {
			snapshot(key, entry);
		}

		channel.release(entry.bot, entry.slot);
	}

	/** The fleet is gone: every slot is the registry's, nothing to release. */
	@Override
	public synchronized void onClosed() {
		for (Map.Entry<ChunkKey, Entry> chunk : chunks.entrySet()) {
			if (chunk.getValue().ready) {
				snapshot(chunk.getKey(), chunk.getValue());
			}
		}

		chunks.clear();
	}

	private void snapshot(ChunkKey key, Entry entry) {
		snapshots.saveSlot(key, entry.mcVersion, columns, entry.slot, stateNames, entry.blockEntities.values());
	}

	private int stateId(Entry entry, int x, int y, int z) {
		if (y < 0 || y > 255) {
			throw new IllegalArgumentException("y " + y + " is outside the column (0 to 255)");
		}

		return columns.getShort(entry.slot * FleetProtocol.COLUMN_SLOT_SIZE + FleetProtocol.COLUMN_STATES
				+ 2 * (y << 8 | z << 4 | x)) & 0xFFFF;
	}

	private static ByteBuffer map(FleetChannel channel) {
		final long size = (long) FleetProtocol.COLUMN_SLOTS * FleetProtocol.COLUMN_SLOT_SIZE;

		try (FileChannel file = FileChannel.open(channel.columns().toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			if (file.size() != size) {
				throw new IllegalStateException(channel.columns() + " is " + file.size() + " bytes, not " + size);
			}

			return file.map(FileChannel.MapMode.READ_WRITE, 0, size).order(ByteOrder.LITTLE_ENDIAN);
		} catch (IOException e) {
			throw new UncheckedIOException("could not map " + channel.columns(), e);
		}
	}

	private Entry entry(ChunkKey key) {
		final Entry entry = chunks.get(key);

		if (entry == null) {
			throw new IllegalStateException(key + " is not in the registry");
		}

		return entry;
	}

	private Entry loaded(ChunkKey key) {
		final Entry entry = entry(key);

		if (!entry.ready) {
			throw new IllegalStateException(key + " is still being loaded into its slot");
		}

		return entry;
	}

	private static long blockKey(int x, int y, int z) {
		return ((long) y << 8) | (z << 4) | x;
	}

	private static int local(int coordinate, int chunk) {
		final int local = coordinate - (chunk << 4);

		if (local < 0 || local > 15) {
			throw new IllegalArgumentException("coordinate " + coordinate + " is outside chunk " + chunk);
		}

		return local;
	}

	private static final class Entry {
		/** The claim's, for the fleet's logs when the slot goes back. */
		final String bot;
		/** The fleet's id for this load of the chunk. */
		final int claim;
		final int slot;
		final String mcVersion;
		/** Local position -> block entity, as the fleet reported them. */
		final Map<Long, ChunkData.BlockEntity> blockEntities = new LinkedHashMap<Long, ChunkData.BlockEntity>();
		/** Whether the slot holds the chunk yet. */
		boolean ready;

		Entry(String bot, int claim, int slot, String mcVersion) {
			this.bot = bot;
			this.claim = claim;
			this.slot = slot;
			this.mcVersion = mcVersion;
		}
	}
}
