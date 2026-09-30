package net.mapmcbot.chunk;

/** A block that changed to `state`, at world coordinates. */
public final class BlockChange {
	private final int x;
	private final int y;
	private final int z;
	private final String state;

	public BlockChange(int x, int y, int z, String state) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.state = state;
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

	public String getState() {
		return state;
	}
}
