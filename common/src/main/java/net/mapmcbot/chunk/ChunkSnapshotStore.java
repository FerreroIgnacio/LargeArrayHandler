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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The last state of every chunk that left the registry, one file each under
 * root/server/dimension/x.z.chunk: magic, format version, save time (epoch ms), mcVersion, server,
 * dimension, chunk x, chunk z, then the data as {@link ChunkProtocol#writeData} writes it.
 *
 * FORMAT_VERSION goes up with every change to this layout or to the data; snapshots of any other
 * version are deleted when the store opens.
 */
public final class ChunkSnapshotStore {
	public static final int FORMAT_VERSION = 1;

	private static final int MAGIC = 0x4D434348; // "MCCH"
	private static final String SUFFIX = ".chunk";

	private final File root;

	public ChunkSnapshotStore(File root) {
		this.root = root;
		discardOutdated();
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
				ChunkProtocol.writeString(out, mcVersion);
				ChunkProtocol.writeString(out, key.getServer());
				ChunkProtocol.writeString(out, key.getDimension());
				out.writeInt(key.getX());
				out.writeInt(key.getZ());
				ChunkProtocol.writeData(out, data);
			}

			Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}

	/** Null when the chunk has no snapshot. */
	public Snapshot load(ChunkKey key) {
		final File file = file(key);

		if (!file.isFile()) {
			return null;
		}

		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
			readVersion(in, file);
			final Instant savedAt = Instant.ofEpochMilli(in.readLong());
			final String mcVersion = ChunkProtocol.readString(in);
			final ChunkKey stored = new ChunkKey(ChunkProtocol.readString(in), ChunkProtocol.readString(in),
					in.readInt(), in.readInt());

			if (!stored.equals(key)) {
				throw new IllegalStateException(file + " holds " + stored + ", not " + key);
			}

			final ChunkData data = ChunkProtocol.readData(in);

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
