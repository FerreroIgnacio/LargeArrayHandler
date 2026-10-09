package net.mapmcbot.area;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.mapmcbot.bot.BotInventory;

/**
 * The chests of one world the mod knows of: each one (a double chest is one, of two blocks) with the
 * last contents a bot saw in it, and whether those can have changed since (dirty). A chest is clean
 * only while it is known exactly: seen by a bot and, since, never opened by a player that is not a
 * bot nor let go by the fleet. One that has no contents yet is dirty.
 *
 * Kept in a tab-separated file, one chest per line: its blocks (x,y,z joined by ;), dirty (0 or 1),
 * and its contents (- for none yet): each slot as name,count,metadata,nbt in hex, empty for nothing,
 * joined by ;. Written the moment it changes.
 *
 * Thread-safe: the channel thread feeds it, the screens read it.
 */
public final class ChestStore {
	/** One chest: its blocks, its last contents (null before any) and whether they can be stale. */
	public static final class Chest {
		private final List<int[]> blocks;
		private List<BotInventory.Item> contents;
		private boolean dirty;

		Chest(List<int[]> blocks, List<BotInventory.Item> contents, boolean dirty) {
			this.blocks = Collections.unmodifiableList(blocks);
			this.contents = contents;
			this.dirty = dirty;
		}

		public List<int[]> getBlocks() {
			return blocks;
		}

		/** The slots as last seen, null before any; empty slots null. */
		public List<BotInventory.Item> getContents() {
			return contents == null ? null : Collections.unmodifiableList(contents);
		}

		public boolean isDirty() {
			return dirty;
		}

		/** Whether any of its blocks is inside the area. */
		public boolean in(Area area) {
			for (int[] block : blocks) {
				if (area.contains(block[0], block[1], block[2])) {
					return true;
				}
			}

			return false;
		}

		@Override
		public String toString() {
			final StringBuilder out = new StringBuilder("chest");

			for (int[] block : blocks) {
				out.append(' ').append(block[0]).append(',').append(block[1]).append(',').append(block[2]);
			}

			return out.toString();
		}
	}

	private final File file;
	/** Each block of each chest -> the chest. */
	private final Map<String, Chest> byBlock = new HashMap<String, Chest>();

	public ChestStore(File file) {
		this.file = file;
		load();
	}

	private static String key(int x, int y, int z) {
		return x + "," + y + "," + z;
	}

	/** The chest with a block at x, y, z; null for none known. */
	public synchronized Chest at(int x, int y, int z) {
		return byBlock.get(key(x, y, z));
	}

	/** Every chest known, each once. */
	public synchronized List<Chest> all() {
		return new ArrayList<Chest>(new LinkedHashSet<Chest>(byBlock.values()));
	}

	/** The chests with a block inside the area. */
	public synchronized List<Chest> in(Area area) {
		final List<Chest> found = new ArrayList<Chest>();

		for (Chest chest : all()) {
			if (chest.in(area)) {
				found.add(chest);
			}
		}

		return found;
	}

	/**
	 * The chest made of exactly these blocks: the one known, or a new one with no contents yet in
	 * place of any it overlaps (a single chest grown into a double, a double broken in two).
	 */
	public synchronized Chest chestOf(List<int[]> blocks) {
		final Chest known = byBlock.get(key(blocks.get(0)[0], blocks.get(0)[1], blocks.get(0)[2]));

		if (known != null && sameBlocks(known.blocks, blocks)) {
			return known;
		}

		for (int[] block : blocks) {
			final Chest overlapping = byBlock.get(key(block[0], block[1], block[2]));

			if (overlapping != null) {
				removeChest(overlapping);
			}
		}

		final Chest chest = new Chest(new ArrayList<int[]>(blocks), null, true);

		for (int[] block : blocks) {
			byBlock.put(key(block[0], block[1], block[2]), chest);
		}

		save();
		return chest;
	}

	/** The chest is no longer there. */
	public synchronized void remove(Chest chest) {
		removeChest(chest);
		save();
	}

	private void removeChest(Chest chest) {
		for (int[] block : chest.blocks) {
			byBlock.remove(key(block[0], block[1], block[2]));
		}
	}

	/** Its contents may have changed unseen. */
	public synchronized void markDirty(Chest chest) {
		if (!chest.dirty) {
			chest.dirty = true;
			save();
		}
	}

	/** Its contents as a bot sees them now: known exactly from here on. */
	public synchronized void see(Chest chest, List<BotInventory.Item> contents) {
		chest.contents = new ArrayList<BotInventory.Item>(contents);
		chest.dirty = false;
		save();
	}

	private static boolean sameBlocks(List<int[]> a, List<int[]> b) {
		if (a.size() != b.size()) {
			return false;
		}

		final Set<String> keys = new HashSet<String>();

		for (int[] block : a) {
			keys.add(key(block[0], block[1], block[2]));
		}

		for (int[] block : b) {
			if (!keys.contains(key(block[0], block[1], block[2]))) {
				return false;
			}
		}

		return true;
	}

	private void load() {
		if (!file.isFile()) {
			return;
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				final Chest chest = parse(line);

				for (int[] block : chest.blocks) {
					if (byBlock.put(key(block[0], block[1], block[2]), chest) != null) {
						throw new IllegalStateException("Two chests at " + key(block[0], block[1], block[2]) + " in " + file);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	private Chest parse(String line) {
		final String[] parts = line.split("\t", -1);

		if (parts.length != 3 || !(parts[1].equals("0") || parts[1].equals("1"))) {
			throw new IllegalStateException("Malformed chest line in " + file + ": " + line);
		}

		final List<int[]> blocks = new ArrayList<int[]>();

		for (String block : parts[0].split(";")) {
			final String[] xyz = block.split(",");

			if (xyz.length != 3) {
				throw new IllegalStateException("Malformed chest block in " + file + ": " + line);
			}

			blocks.add(new int[] {Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])});
		}

		List<BotInventory.Item> contents = null;

		if (!parts[2].equals("-")) {
			contents = new ArrayList<BotInventory.Item>();

			for (String slot : parts[2].split(";", -1)) {
				contents.add(slot.isEmpty() ? null : parseItem(slot, line));
			}
		}

		return new Chest(blocks, contents, parts[1].equals("1") || contents == null);
	}

	private BotInventory.Item parseItem(String slot, String line) {
		final String[] fields = slot.split(",", -1);

		if (fields.length != 4 || fields[3].length() % 2 != 0) {
			throw new IllegalStateException("Malformed chest slot in " + file + ": " + line);
		}

		final byte[] nbt = new byte[fields[3].length() / 2];

		for (int i = 0; i < nbt.length; i++) {
			nbt[i] = (byte) Integer.parseInt(fields[3].substring(i * 2, i * 2 + 2), 16);
		}

		return new BotInventory.Item(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), nbt);
	}

	private void save() {
		final File parent = file.getParentFile();

		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IllegalStateException("Could not create " + parent);
		}

		try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
			for (Chest chest : all()) {
				final StringBuilder line = new StringBuilder();

				for (int i = 0; i < chest.blocks.size(); i++) {
					final int[] block = chest.blocks.get(i);
					line.append(i == 0 ? "" : ";").append(key(block[0], block[1], block[2]));
				}

				line.append('\t').append(chest.dirty ? '1' : '0').append('\t');

				if (chest.contents == null) {
					line.append('-');
				} else {
					for (int i = 0; i < chest.contents.size(); i++) {
						final BotInventory.Item item = chest.contents.get(i);
						line.append(i == 0 ? "" : ";");

						if (item != null) {
							line.append(item.getName()).append(',').append(item.getCount()).append(',').append(item.getMetadata()).append(',');

							for (byte b : item.getNbt()) {
								line.append(String.format("%02x", b & 0xFF));
							}
						}
					}
				}

				writer.write(line.append('\n').toString());
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}
}
