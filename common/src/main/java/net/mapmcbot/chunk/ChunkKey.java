package net.mapmcbot.chunk;

/** One chunk column of one server: host:port, dimension (minecraft:overworld, ...) and chunk x/z. */
public final class ChunkKey {
	private final String server;
	private final String dimension;
	private final int x;
	private final int z;

	public ChunkKey(String server, String dimension, int x, int z) {
		if (server == null || server.isEmpty() || dimension == null || dimension.isEmpty()) {
			throw new IllegalArgumentException("chunk key needs a server and a dimension: " + server + " " + dimension);
		}

		this.server = server;
		this.dimension = dimension;
		this.x = x;
		this.z = z;
	}

	public String getServer() {
		return server;
	}

	public String getDimension() {
		return dimension;
	}

	public int getX() {
		return x;
	}

	public int getZ() {
		return z;
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof ChunkKey)) {
			return false;
		}

		final ChunkKey key = (ChunkKey) other;
		return x == key.x && z == key.z && server.equals(key.server) && dimension.equals(key.dimension);
	}

	@Override
	public int hashCode() {
		return ((server.hashCode() * 31 + dimension.hashCode()) * 31 + x) * 31 + z;
	}

	@Override
	public String toString() {
		return server + "/" + dimension + "/" + x + "," + z;
	}
}
