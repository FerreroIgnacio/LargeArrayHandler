package net.mapmcbot.chunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks of one chunk column, the same for every Minecraft version: 16-high sections from
 * minY up, each a palette of block states as strings (minecraft:chest[facing=north], properties
 * sorted) plus one palette index per block, and the block entities with their NBT. Light,
 * heightmaps, biomes and entities are left out on purpose.
 *
 * Coordinates are local to the column: x and z 0..15, y the world y.
 */
public final class ChunkData {
	public static final int SECTION_HEIGHT = 16;
	public static final int SECTION_VOLUME = 16 * 16 * SECTION_HEIGHT;

	private final int minY;
	private final int height;
	private final Section[] sections;
	private final Map<Long, BlockEntity> blockEntities = new LinkedHashMap<Long, BlockEntity>();

	public ChunkData(int minY, int height, List<Section> sections, Collection<BlockEntity> blockEntities) {
		if (height <= 0 || height % SECTION_HEIGHT != 0 || sections.size() != height / SECTION_HEIGHT) {
			throw new IllegalArgumentException("height " + height + " does not match " + sections.size() + " sections");
		}

		this.minY = minY;
		this.height = height;
		this.sections = sections.toArray(new Section[0]);

		for (BlockEntity entity : blockEntities) {
			checkPosition(entity.x, entity.y, entity.z);

			if (this.blockEntities.put(key(entity.x, entity.y, entity.z), entity) != null) {
				throw new IllegalArgumentException("two block entities at " + entity.x + "," + entity.y + "," + entity.z);
			}
		}
	}

	public int getMinY() {
		return minY;
	}

	public int getHeight() {
		return height;
	}

	public int getSectionCount() {
		return sections.length;
	}

	public Section getSection(int index) {
		return sections[index];
	}

	public String getState(int x, int y, int z) {
		checkPosition(x, y, z);
		return sections[(y - minY) >> 4].get(index(x, y, z));
	}

	/**
	 * Returns whether anything changed. Turning the block into a different block drops its block
	 * entity, as the game does; the server sends the new one separately.
	 */
	public boolean setState(int x, int y, int z, String state) {
		final String old = getState(x, y, z);

		if (!sections[(y - minY) >> 4].set(index(x, y, z), state)) {
			return false;
		}

		if (!blockName(old).equals(blockName(state))) {
			blockEntities.remove(key(x, y, z));
		}

		return true;
	}

	public Collection<BlockEntity> getBlockEntities() {
		return Collections.unmodifiableCollection(blockEntities.values());
	}

	/** Null when there is no block entity there. */
	public BlockEntity getBlockEntity(int x, int y, int z) {
		checkPosition(x, y, z);
		return blockEntities.get(key(x, y, z));
	}

	public boolean putBlockEntity(BlockEntity entity) {
		checkPosition(entity.x, entity.y, entity.z);
		return !entity.equals(blockEntities.put(key(entity.x, entity.y, entity.z), entity));
	}

	public boolean removeBlockEntity(int x, int y, int z) {
		checkPosition(x, y, z);
		return blockEntities.remove(key(x, y, z)) != null;
	}

	/** Index of a block inside its section: y, then z, then x. */
	public static int index(int x, int y, int z) {
		return ((y & 15) << 8) | (z << 4) | x;
	}

	private void checkPosition(int x, int y, int z) {
		if (x < 0 || x > 15 || z < 0 || z > 15 || y < minY || y >= minY + height) {
			throw new IllegalArgumentException("position " + x + "," + y + "," + z + " is outside the chunk (y "
					+ minY + " to " + (minY + height - 1) + ")");
		}
	}

	private static long key(int x, int y, int z) {
		return ((long) y << 8) | (z << 4) | x;
	}

	private static String blockName(String state) {
		final int bracket = state.indexOf('[');
		return bracket < 0 ? state : state.substring(0, bracket);
	}

	/** 16x16x16 blocks as indices into a palette that only grows. */
	public static final class Section {
		public static final int MAX_PALETTE = 0xFFFF;

		private final List<String> palette;
		private final Map<String, Integer> lookup = new HashMap<String, Integer>();
		private final char[] indices;

		public Section(List<String> palette, char[] indices) {
			if (palette.isEmpty() || palette.size() > MAX_PALETTE) {
				throw new IllegalArgumentException("section palette of " + palette.size() + " states");
			}

			if (indices.length != SECTION_VOLUME) {
				throw new IllegalArgumentException("section with " + indices.length + " blocks");
			}

			this.palette = new ArrayList<String>(palette);
			this.indices = indices;

			for (int i = 0; i < palette.size(); i++) {
				if (lookup.put(palette.get(i), i) != null) {
					throw new IllegalArgumentException("state " + palette.get(i) + " twice in a section palette");
				}
			}

			for (char index : indices) {
				if (index >= palette.size()) {
					throw new IllegalArgumentException("palette index " + (int) index + " past " + palette.size() + " states");
				}
			}
		}

		public List<String> getPalette() {
			return Collections.unmodifiableList(palette);
		}

		public int getIndex(int block) {
			return indices[block];
		}

		public String get(int block) {
			return palette.get(indices[block]);
		}

		boolean set(int block, String state) {
			Integer id = lookup.get(state);

			if (id == null) {
				if (palette.size() == MAX_PALETTE) {
					throw new IllegalStateException("section palette is full");
				}

				id = palette.size();
				palette.add(state);
				lookup.put(state, id);
			}

			if (indices[block] == id) {
				return false;
			}

			indices[block] = (char) id.intValue();
			return true;
		}
	}

	/** A block entity: local position, type (minecraft:chest) and its NBT, uncompressed big-endian. */
	public static final class BlockEntity {
		private final int x;
		private final int y;
		private final int z;
		private final String type;
		private final byte[] nbt;

		public BlockEntity(int x, int y, int z, String type, byte[] nbt) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.type = type;
			this.nbt = nbt;
		}

		public int getX() {
			return x;
		}

		public int getY() {
			return y;
		}

		public int getZ() {
			return z;
		}

		public String getType() {
			return type;
		}

		public byte[] getNbt() {
			return nbt.clone();
		}

		byte[] nbt() {
			return nbt;
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof BlockEntity)) {
				return false;
			}

			final BlockEntity entity = (BlockEntity) other;
			return x == entity.x && y == entity.y && z == entity.z && type.equals(entity.type)
					&& Arrays.equals(nbt, entity.nbt);
		}

		@Override
		public int hashCode() {
			return ((x * 31 + y) * 31 + z) * 31 + Arrays.hashCode(nbt);
		}
	}
}
