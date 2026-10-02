package net.mapmcbot.fleet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkData;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/**
 * The binary messages between the mod and the bot fleet, mirrored by bot/protocol.js, and the
 * layout of columns.bin, mirrored by bot/sharedChunks.js.
 *
 * A frame is a u32 length followed by that many bytes: a u8 type and its payload. Everything is
 * big-endian; a string is a u16 byte length and UTF-8. Every message starts with the bot's name;
 * chunk messages go on with the rest of their header: server (host:port), dimension, chunk x,
 * chunk z (i32 each).
 *
 * The columns' blocks never go through the socket: the fleet loads each into a slot of columns.bin,
 * which the mod maps. A slot is COLUMN_SLOT_SIZE bytes, little-endian: a header (unused), the state
 * id (u16, id << 4 | meta) of every block at COLUMN_STATES + 2 * (y << 8 | z << 4 | x), the biomes
 * (u8, z << 4 | x) at COLUMN_BIOMES. A section the column lacks is zeros (air).
 *
 * Chunk data (snapshots): i32 minY, i32 height, then height/16 sections bottom up, each a u16
 * palette size, the palette strings and, unless the palette has a single state, 4096 indices (u8
 * while the palette fits in 256, u16 past that) in y, z, x order. Then i32 block entity count, each
 * u8 x, i32 y, u8 z (local), type string, i32 NBT length and the NBT.
 */
public final class FleetProtocol {
	public static final int COLUMN_SLOTS = 4096;
	public static final int COLUMN_STATES = 16;
	public static final int COLUMN_BIOMES = COLUMN_STATES + 16 * ChunkData.SECTION_VOLUME * 2;
	public static final int COLUMN_SLOT_SIZE = COLUMN_BIOMES + 256;

	// Fleet to mod.
	/**
	 * header, i32 claim id, i32 slot, mcVersion string: the fleet's first bot got the column (the
	 * header's bot), once for the whole fleet, and loads it into the slot.
	 */
	public static final int CLAIM = 1;
	/** header, i32 claim id: the column is in its slot, to be read from now on. */
	public static final int READY = 2;
	/** header: the readied column's blocks or block entities changed. */
	public static final int CHANGED = 3;
	/** header, i32 x, y, z (world), u8 present; when present the type string, i32 NBT length and the NBT. */
	public static final int BLOCK_ENTITY_UPDATE = 4;
	/** header: the fleet's last bot let the column go; its slot waits on a RELEASE. */
	public static final int UNLOAD = 5;
	/** bot name. */
	public static final int BOT_GONE = 6;
	/** bot name, u8 walking; when walking i32 target x, y, z, i32 node count, each i32 x, y, z. */
	public static final int PATH = 7;
	/** bot name: in the world, ready for orders. */
	public static final int BOT_SPAWNED = 8;
	/** bot name (empty), i32 count, each u16 state id and its name. Once, first of all. */
	public static final int STATE_NAMES = 9;
	/** bot name (empty), key, i32 slot: read the column's snapshot into the free slot; answered with LOADED. */
	public static final int LOAD = 10;
	/**
	 * bot name (empty), i32 request id (0 when the fleet took it on an event of its own), reason
	 * string, i32 length and the profile's rows, UTF-8 (see bot/profiler.js).
	 */
	public static final int PROFILE = 11;

	// Mod to fleet.
	/** bot name (the claim's), i32 slot: the mod is done with the unloaded column's slot. */
	public static final int RELEASE = 16;
	/** bot name (empty), key, i32 slot, u8 found; when found the snapshot's mcVersion string. */
	public static final int LOADED = 20;
	/** bot name, host string, i32 port. */
	public static final int SPAWN = 17;
	/** bot name. */
	public static final int QUIT = 18;
	/**
	 * bot name, i32 formation id, i32 x, i32 y, i32 z: the block to stand on, or the nearest free one
	 * next to it. One id per #formation, higher than the last: the fleet works the spots out once per id.
	 */
	public static final int FORMATION = 19;
	/** bot name (empty), i32 request id, reason string: the fleet answers with a PROFILE of that id. */
	public static final int PROFILE_REQUEST = 21;

	private static final int MAX_FRAME = 64 * 1024 * 1024;

	private FleetProtocol() {
	}

	/** The next frame without its length, or null when the stream ends cleanly between frames. */
	public static byte[] readFrame(InputStream stream) throws IOException {
		final int first = stream.read();

		if (first < 0) {
			return null;
		}

		final DataInputStream in = new DataInputStream(stream);
		final int length = first << 24 | in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8 | in.readUnsignedByte();

		if (length <= 0 || length > MAX_FRAME) {
			throw new IOException("bad frame length " + length);
		}

		final byte[] frame = new byte[length];
		in.readFully(frame);
		return frame;
	}

	/** Decodes a fleet-to-mod frame and hands it to the listener of its domain: chunks, bots or profiles. */
	public static void dispatch(byte[] frame, ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		try {
			final DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
			final int type = in.readUnsignedByte();
			final String bot = readString(in);

			switch (type) {
				case CLAIM: {
					final ChunkKey key = readKey(in);
					final int claim = in.readInt();
					final int slot = in.readInt();
					final String mcVersion = readString(in);
					end(in, type);

					if (slot < 0 || slot >= COLUMN_SLOTS) {
						throw new IOException(key + " claimed into slot " + slot + ", outside 0.." + (COLUMN_SLOTS - 1));
					}

					chunks.onClaim(bot, key, claim, slot, mcVersion);
					break;
				}

				case CHANGED: {
					final ChunkKey key = readKey(in);
					end(in, type);
					chunks.onChanged(bot, key);
					break;
				}

				case LOAD: {
					final ChunkKey key = readKey(in);
					final int slot = in.readInt();
					end(in, type);

					if (slot < 0 || slot >= COLUMN_SLOTS) {
						throw new IOException(key + " to be loaded into slot " + slot + ", outside 0.." + (COLUMN_SLOTS - 1));
					}

					chunks.onLoadSnapshot(key, slot);
					break;
				}

				case READY: {
					final ChunkKey key = readKey(in);
					final int claim = in.readInt();
					end(in, type);
					chunks.onReady(bot, key, claim);
					break;
				}

				case STATE_NAMES: {
					final int count = in.readInt();

					if (count < 0 || count > 0x10000) {
						throw new IOException("bad state name count " + count);
					}

					final String[] names = new String[0x10000];

					for (int i = 0; i < count; i++) {
						final int id = in.readUnsignedShort();
						final String name = readString(in);

						if (names[id] != null) {
							throw new IOException("state id " + id + " named twice: " + names[id] + ", " + name);
						}

						names[id] = name;
					}

					end(in, type);
					chunks.onStateNames(names);
					break;
				}

				case BLOCK_ENTITY_UPDATE: {
					final ChunkKey key = readKey(in);
					final int x = in.readInt();
					final int y = in.readInt();
					final int z = in.readInt();

					if (in.readBoolean()) {
						final String entityType = readString(in);
						final byte[] nbt = readBytes(in);
						end(in, type);
						chunks.onBlockEntityUpdate(bot, key, x, y, z, entityType, nbt);
					} else {
						end(in, type);
						chunks.onBlockEntityRemove(bot, key, x, y, z);
					}
					break;
				}

				case UNLOAD: {
					final ChunkKey key = readKey(in);
					end(in, type);
					chunks.onUnload(bot, key);
					break;
				}

				case BOT_GONE:
					end(in, type);
					bots.onGone(bot);
					break;

				case PATH:
					if (!in.readBoolean()) {
						end(in, type);
						bots.onPathCleared(bot);
						break;
					}

					final boolean waiting = in.readBoolean();
					final int[] path = readPath(in);
					end(in, type);
					bots.onPath(bot, path, waiting);
					break;

				case BOT_SPAWNED:
					end(in, type);
					bots.onSpawned(bot);
					break;

				case PROFILE: {
					final int id = in.readInt();
					final String reason = readString(in);
					final String text = new String(readBytes(in), StandardCharsets.UTF_8);
					end(in, type);
					profiles.onProfile(id, reason, text);
					break;
				}

				default:
					throw new IllegalStateException("unknown message type " + type + " from the bot fleet");
			}
		} catch (IOException e) {
			throw new UncheckedIOException("truncated message from the bot fleet", e);
		}
	}

	/** {target x, y, z, node x, y, z, ...}. */
	private static int[] readPath(DataInput in) throws IOException {
		final int tx = in.readInt();
		final int ty = in.readInt();
		final int tz = in.readInt();
		final int count = in.readInt();

		if (count < 0) {
			throw new IOException("bad path length " + count);
		}

		final int[] result = new int[3 + 3 * count];
		result[0] = tx;
		result[1] = ty;
		result[2] = tz;

		for (int i = 3; i < result.length; i++) {
			result[i] = in.readInt();
		}

		return result;
	}

	/** mcVersion: the snapshot's, null when there is none. */
	public static byte[] loaded(ChunkKey key, int slot, String mcVersion) {
		final Frame frame = new Frame(LOADED);

		try {
			writeString(frame.out, "");
			writeKey(frame.out, key);
			frame.out.writeInt(slot);
			frame.out.writeBoolean(mcVersion != null);

			if (mcVersion != null) {
				writeString(frame.out, mcVersion);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return frame.bytes();
	}

	public static byte[] release(String bot, int slot) {
		final Frame frame = new Frame(RELEASE);

		try {
			writeString(frame.out, bot);
			frame.out.writeInt(slot);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return frame.bytes();
	}

	public static byte[] profileRequest(int id, String reason) {
		final Frame frame = new Frame(PROFILE_REQUEST);

		try {
			writeString(frame.out, "");
			frame.out.writeInt(id);
			writeString(frame.out, reason);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return frame.bytes();
	}

	public static byte[] spawn(String bot, String host, int port) {
		final Frame frame = new Frame(SPAWN);

		try {
			writeString(frame.out, bot);
			writeString(frame.out, host);
			frame.out.writeInt(port);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return frame.bytes();
	}

	public static byte[] quit(String bot) {
		final Frame frame = new Frame(QUIT);

		try {
			writeString(frame.out, bot);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return frame.bytes();
	}

	public static byte[] formation(String bot, int id, int x, int y, int z) {
		final Frame frame = new Frame(FORMATION);

		try {
			writeString(frame.out, bot);
			frame.out.writeInt(id);
			frame.out.writeInt(x);
			frame.out.writeInt(y);
			frame.out.writeInt(z);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return frame.bytes();
	}

	public static void writeData(DataOutput out, ChunkData data) throws IOException {
		out.writeInt(data.getMinY());
		out.writeInt(data.getHeight());

		for (int s = 0; s < data.getSectionCount(); s++) {
			final ChunkData.Section section = data.getSection(s);
			final List<String> palette = section.getPalette();
			out.writeShort(palette.size());

			for (String state : palette) {
				writeString(out, state);
			}

			if (palette.size() == 1) {
				continue;
			}

			final boolean wide = palette.size() > 256;

			for (int i = 0; i < ChunkData.SECTION_VOLUME; i++) {
				if (wide) {
					out.writeShort(section.getIndex(i));
				} else {
					out.writeByte(section.getIndex(i));
				}
			}
		}

		out.writeInt(data.getBlockEntities().size());

		for (ChunkData.BlockEntity entity : data.getBlockEntities()) {
			out.writeByte(entity.getX());
			out.writeInt(entity.getY());
			out.writeByte(entity.getZ());
			writeString(out, entity.getType());
			final byte[] nbt = entity.getNbt();
			out.writeInt(nbt.length);
			out.write(nbt);
		}
	}

	public static ChunkData readData(DataInput in) throws IOException {
		final int minY = in.readInt();
		final int height = in.readInt();

		if (height <= 0 || height % ChunkData.SECTION_HEIGHT != 0) {
			throw new IOException("bad chunk height " + height);
		}

		final List<ChunkData.Section> sections = new ArrayList<ChunkData.Section>(height / ChunkData.SECTION_HEIGHT);

		for (int s = 0; s < height / ChunkData.SECTION_HEIGHT; s++) {
			final int size = in.readUnsignedShort();
			final List<String> palette = new ArrayList<String>(size);

			for (int i = 0; i < size; i++) {
				palette.add(readString(in));
			}

			final char[] indices = new char[ChunkData.SECTION_VOLUME];

			if (size > 1) {
				final boolean wide = size > 256;

				for (int i = 0; i < indices.length; i++) {
					indices[i] = (char) (wide ? in.readUnsignedShort() : in.readUnsignedByte());
				}
			}

			sections.add(new ChunkData.Section(palette, indices));
		}

		final int count = in.readInt();

		if (count < 0) {
			throw new IOException("bad block entity count " + count);
		}

		final List<ChunkData.BlockEntity> entities = new ArrayList<ChunkData.BlockEntity>(count);

		for (int i = 0; i < count; i++) {
			final int x = in.readUnsignedByte();
			final int y = in.readInt();
			final int z = in.readUnsignedByte();
			entities.add(new ChunkData.BlockEntity(x, y, z, readString(in), readBytes(in)));
		}

		return new ChunkData(minY, height, sections, entities);
	}

	public static void writeString(DataOutput out, String value) throws IOException {
		final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

		if (bytes.length > 0xFFFF) {
			throw new IOException("string of " + bytes.length + " bytes is too long");
		}

		out.writeShort(bytes.length);
		out.write(bytes);
	}

	public static String readString(DataInput in) throws IOException {
		final byte[] bytes = new byte[in.readUnsignedShort()];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void writeKey(DataOutput out, ChunkKey key) throws IOException {
		writeString(out, key.getServer());
		writeString(out, key.getDimension());
		out.writeInt(key.getX());
		out.writeInt(key.getZ());
	}

	private static ChunkKey readKey(DataInput in) throws IOException {
		return new ChunkKey(readString(in), readString(in), in.readInt(), in.readInt());
	}

	private static byte[] readBytes(DataInput in) throws IOException {
		final int length = in.readInt();

		if (length < 0 || length > MAX_FRAME) {
			throw new IOException("bad byte array length " + length);
		}

		final byte[] bytes = new byte[length];
		in.readFully(bytes);
		return bytes;
	}

	private static void end(DataInputStream in, int type) throws IOException {
		if (in.available() != 0) {
			throw new IOException(in.available() + " stray bytes after message type " + type);
		}
	}

	/** A frame being written: the length is prefixed once the body is known. */
	private static final class Frame {
		private final ByteArrayOutputStream body = new ByteArrayOutputStream();
		private final DataOutputStream out = new DataOutputStream(body);

		Frame(int type) {
			body.write(type);
		}

		byte[] bytes() {
			final byte[] payload = body.toByteArray();
			final byte[] frame = new byte[4 + payload.length];
			frame[0] = (byte) (payload.length >>> 24);
			frame[1] = (byte) (payload.length >>> 16);
			frame[2] = (byte) (payload.length >>> 8);
			frame[3] = (byte) payload.length;
			System.arraycopy(payload, 0, frame, 4, payload.length);
			return frame;
		}
	}
}
