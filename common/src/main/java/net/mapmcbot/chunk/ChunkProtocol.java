package net.mapmcbot.chunk;

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

/**
 * The binary messages between the mod and the bot fleet, mirrored by bot/protocol.js.
 *
 * A frame is a u32 length followed by that many bytes: a u8 type and its payload. Everything is
 * big-endian; a string is a u16 byte length and UTF-8. Chunk messages start with a header: bot
 * name, server (host:port), dimension, chunk x, chunk z (i32 each).
 *
 * Chunk data: i32 minY, i32 height, then height/16 sections bottom up, each a u16 palette size, the
 * palette strings and, unless the palette has a single state, 4096 indices (u8 while the palette
 * fits in 256, u16 past that) in y, z, x order. Then i32 block entity count, each u8 x, i32 y, u8 z
 * (local), type string, i32 NBT length and the NBT.
 */
public final class ChunkProtocol {
	// Fleet to mod.
	/** header, i32 claim id. */
	public static final int CLAIM = 1;
	/** header, mcVersion string, chunk data. */
	public static final int CHUNK_DATA = 2;
	/** header, i32 count, each i32 x, y, z (world) and the state string. */
	public static final int BLOCK_UPDATE = 3;
	/** header, i32 x, y, z (world), u8 present; when present the type string, i32 NBT length and the NBT. */
	public static final int BLOCK_ENTITY_UPDATE = 4;
	/** header. */
	public static final int UNLOAD = 5;
	/** bot name. */
	public static final int BOT_GONE = 6;

	// Mod to fleet.
	/** header, i32 claim id. */
	public static final int REQUEST_CHUNK = 16;
	/** bot name, host string, i32 port. */
	public static final int SPAWN = 17;
	/** bot name. */
	public static final int QUIT = 18;
	/** bot name, i32 x, i32 y, i32 z: the block to stand on, or the nearest free one next to it. */
	public static final int FORMATION = 19;

	private static final int MAX_FRAME = 64 * 1024 * 1024;

	private ChunkProtocol() {
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

	/** Decodes a fleet-to-mod frame and hands it to the listener. */
	public static void dispatch(byte[] frame, ChunkChannel.Listener listener) {
		try {
			final DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
			final int type = in.readUnsignedByte();

			if (type == BOT_GONE) {
				final String bot = readString(in);
				end(in, type);
				listener.onBotGone(bot);
				return;
			}

			final String bot = readString(in);
			final ChunkKey key = readKey(in);

			switch (type) {
				case CLAIM: {
					final int claim = in.readInt();
					end(in, type);
					listener.onClaim(bot, key, claim);
					break;
				}

				case CHUNK_DATA: {
					final String mcVersion = readString(in);
					final ChunkData data = readData(in);
					end(in, type);
					listener.onChunkData(bot, key, mcVersion, data);
					break;
				}

				case BLOCK_UPDATE: {
					final int count = in.readInt();
					final List<BlockChange> changes = new ArrayList<BlockChange>(count);

					for (int i = 0; i < count; i++) {
						changes.add(new BlockChange(in.readInt(), in.readInt(), in.readInt(), readString(in)));
					}

					end(in, type);
					listener.onBlockUpdate(bot, key, changes);
					break;
				}

				case BLOCK_ENTITY_UPDATE: {
					final int x = in.readInt();
					final int y = in.readInt();
					final int z = in.readInt();

					if (in.readBoolean()) {
						final String entityType = readString(in);
						final byte[] nbt = readBytes(in);
						end(in, type);
						listener.onBlockEntityUpdate(bot, key, x, y, z, entityType, nbt);
					} else {
						end(in, type);
						listener.onBlockEntityRemove(bot, key, x, y, z);
					}
					break;
				}

				case UNLOAD:
					end(in, type);
					listener.onUnload(bot, key);
					break;

				default:
					throw new IllegalStateException("unknown message type " + type + " from the bot fleet");
			}
		} catch (IOException e) {
			throw new UncheckedIOException("truncated message from the bot fleet", e);
		}
	}

	/** The bot of a BOT_GONE frame. */
	public static String botGoneName(byte[] frame) {
		try {
			return readString(new DataInputStream(new ByteArrayInputStream(frame, 1, frame.length - 1)));
		} catch (IOException e) {
			throw new UncheckedIOException("truncated BOT_GONE message", e);
		}
	}

	public static byte[] requestChunk(String bot, ChunkKey key, int claim) {
		final Frame frame = new Frame(REQUEST_CHUNK);

		try {
			writeString(frame.out, bot);
			writeKey(frame.out, key);
			frame.out.writeInt(claim);
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

	public static byte[] formation(String bot, int x, int y, int z) {
		final Frame frame = new Frame(FORMATION);

		try {
			writeString(frame.out, bot);
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
			out.writeInt(entity.nbt().length);
			out.write(entity.nbt());
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
