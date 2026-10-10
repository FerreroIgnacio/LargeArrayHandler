package net.mapmcbot.chunk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.mapmcbot.fleet.FleetProtocol;

/**
 * The last state of every chunk that left the registry, one file each under
 * root/server/dimension/x.z.chunk: magic, format version, save time (epoch ms), mcVersion, server,
 * dimension, chunk x, chunk z, then the data as {@link FleetProtocol#writeData} writes it.
 *
 * FORMAT_VERSION goes up with every change to this layout or to the data; snapshots of any other
 * version are deleted when the store opens.
 *
 * SINGLETON: one per game, the registry's; a second store throws.
 */
public final class ChunkSnapshotStore {
	private static final AtomicBoolean CREATED = new AtomicBoolean();

	public static final int FORMAT_VERSION = 1;

	private static final int MAGIC = 0x4D434348; // "MCCH"
	private static final String SUFFIX = ".chunk";

	private final File root;
	/** Held by the sweep from reading a snapshot's version to deleting it, and by a save as it moves its file in: the sweep never deletes one saved after it read. */
	private final Object swapLock = new Object();

	/**
	 * Outdated snapshots go on a thread of their own: with thousands of files the sweep would hold up
	 * whoever opens the store (the game's thread). Saving meanwhile is safe: the sweep only deletes
	 * snapshots of another format version, and only whole .chunk files, never the .tmp being written;
	 * a .chunk moved in while it looks at one waits for it (swapLock).
	 */
	public ChunkSnapshotStore(File root) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("ChunkSnapshotStore is a singleton: one was already created");
		}

		this.root = root;
		final Thread sweep = new Thread(this::discardOutdated, "mapmcbot-snapshot-sweep");
		sweep.setDaemon(true);
		sweep.start();
	}

	/**
	 * The chunk in `slot` of the fleet's columns file (see FleetProtocol), its state ids named by
	 * `names`. Only the block entities still on a block of their type are kept: the block changed
	 * since drops them, as the game does.
	 */
	public void saveSlot(ChunkKey key, String mcVersion, ByteBuffer columns, int slot, String[] names,
			Collection<ChunkData.BlockEntity> blockEntities) {
		final int base = slot * FleetProtocol.COLUMN_SLOT_SIZE + FleetProtocol.COLUMN_STATES;
		final int sectionCount = 16;
		final List<ChunkData.Section> sections = new ArrayList<ChunkData.Section>(sectionCount);
		// State id -> its index in the palette of the section being read, -1 while not in it.
		final int[] indexOf = new int[0x10000];
		Arrays.fill(indexOf, -1);

		for (int s = 0; s < sectionCount; s++) {
			final List<Integer> ids = new ArrayList<Integer>();
			final char[] indices = new char[ChunkData.SECTION_VOLUME];

			for (int i = 0; i < ChunkData.SECTION_VOLUME; i++) {
				final int id = columns.getShort(base + 2 * (s * ChunkData.SECTION_VOLUME + i)) & 0xFFFF;

				if (indexOf[id] < 0) {
					indexOf[id] = ids.size();
					ids.add(id);
				}

				indices[i] = (char) indexOf[id];
			}

			// Two ids may name the same state (legacy metadata the flattening drops): one entry each name.
			final List<String> palette = new ArrayList<String>();
			final Map<String, Integer> lookup = new HashMap<String, Integer>();
			final char[] remap = new char[ids.size()];

			for (int k = 0; k < ids.size(); k++) {
				final String name = stateName(names, ids.get(k), key);
				Integer entry = lookup.get(name);

				if (entry == null) {
					entry = palette.size();
					palette.add(name);
					lookup.put(name, entry);
				}

				remap[k] = (char) entry.intValue();
				indexOf[ids.get(k)] = -1;
			}

			for (int i = 0; i < indices.length; i++) {
				indices[i] = remap[indices[i]];
			}

			sections.add(new ChunkData.Section(palette, indices));
		}

		final ChunkData data = new ChunkData(0, sectionCount * ChunkData.SECTION_HEIGHT, sections,
				new ArrayList<ChunkData.BlockEntity>());

		for (ChunkData.BlockEntity entity : blockEntities) {
			if (ChunkData.blockName(data.getState(entity.getX(), entity.getY(), entity.getZ())).equals(entity.getType())) {
				data.putBlockEntity(entity);
			}
		}

		save(key, mcVersion, data);
	}

	static String stateName(String[] names, int id, ChunkKey key) {
		final String name = names[id];

		if (name == null) {
			throw new IllegalStateException(key + " holds state id " + id + " (" + (id >> 4) + ":" + (id & 15)
					+ "), which has no name");
		}

		return name;
	}

	/** Written to a temporary file and moved over the old one, so a crash never leaves half a snapshot. */
	public void save(ChunkKey key, String mcVersion, ChunkData data) {
		final File file = file(key);
		final File parent = file.getParentFile();

		if (!parent.isDirectory() && !parent.mkdirs()) {
			throw new IllegalStateException("Could not create " + parent);
		}

		final File temporary = new File(parent, file.getName() + ".tmp");

		try {
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
				out.writeInt(MAGIC);
				out.writeInt(FORMAT_VERSION);
				out.writeLong(System.currentTimeMillis());
				FleetProtocol.writeString(out, mcVersion);
				FleetProtocol.writeString(out, key.getServer());
				FleetProtocol.writeString(out, key.getDimension());
				out.writeInt(key.getX());
				out.writeInt(key.getZ());
				FleetProtocol.writeData(out, data);
			}

			synchronized (swapLock) {
				Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}

	/** Null when the chunk has no snapshot, or only one of another format version the sweep has yet to delete. */
	public Snapshot load(ChunkKey key) {
		final File file = file(key);

		if (!file.isFile()) {
			return null;
		}

		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			if (readVersion(in, file) != FORMAT_VERSION) {
				return null;
			}

			final Instant savedAt = Instant.ofEpochMilli(in.readLong());
			final String mcVersion = FleetProtocol.readString(in);
			final ChunkKey stored = new ChunkKey(FleetProtocol.readString(in), FleetProtocol.readString(in),
					in.readInt(), in.readInt());

			if (!stored.equals(key)) {
				throw new IllegalStateException(file + " holds " + stored + ", not " + key);
			}

			final ChunkData data = FleetProtocol.readData(in);

			if (in.read() != -1) {
				throw new IllegalStateException("stray bytes at the end of " + file);
			}

			return new Snapshot(savedAt, mcVersion, key, data);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	private void discardOutdated() {
		if (!root.isDirectory()) {
			return;
		}

		final List<Path> files;

		try (Stream<Path> walk = Files.walk(root.toPath())) {
			files = walk.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).collect(Collectors.toList());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not list " + root, e);
		}

		for (Path path : files) {
			synchronized (swapLock) {
				final int version;

				try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path.toFile())))) {
					version = readVersion(in, path.toFile());
				} catch (IOException e) {
					throw new UncheckedIOException("Could not read " + path, e);
				}

				if (version != FORMAT_VERSION) {
					try {
						Files.delete(path);
					} catch (IOException e) {
						throw new UncheckedIOException("Could not delete outdated snapshot " + path, e);
					}
				}
			}
		}
	}

	private static int readVersion(DataInputStream in, File file) throws IOException {
		if (in.readInt() != MAGIC) {
			throw new IllegalStateException(file + " is not a chunk snapshot");
		}

		return in.readInt();
	}

	private File file(ChunkKey key) {
		return new File(root, sanitize(key.getServer()) + "/" + sanitize(key.getDimension()) + "/"
				+ key.getX() + "." + key.getZ() + SUFFIX);
	}

	private static String sanitize(String raw) {
		final StringBuilder out = new StringBuilder(raw.length());

		for (int i = 0; i < raw.length(); i++) {
			final char c = raw.charAt(i);
			out.append(Character.isLetterOrDigit(c) || c == '-' || c == '.' ? c : '_');
		}

		return out.toString();
	}

	public static final class Snapshot {
		private final Instant savedAt;
		private final String mcVersion;
		private final ChunkKey key;
		private final ChunkData data;

		Snapshot(Instant savedAt, String mcVersion, ChunkKey key, ChunkData data) {
			this.savedAt = savedAt;
			this.mcVersion = mcVersion;
			this.key = key;
			this.data = data;
		}

		public Instant getSavedAt() {
			return savedAt;
		}

		public String getMcVersion() {
			return mcVersion;
		}

		public ChunkKey getKey() {
			return key;
		}

		public ChunkData getData() {
			return data;
		}
	}
}
