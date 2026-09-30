package net.mapmcbot.area;

import java.util.UUID;

/**
 * A named, coloured box in the world.
 *
 * Corners are stored normalised, so min is always the lower corner on every axis whichever order
 * the two points were picked in. The id is generated once and survives renames, so anything that
 * points at an area later (bot targets) does not break when it is renamed.
 */
public final class Area {
	private final String id;
	private String name;
	private int color;

	private int minX;
	private int minY;
	private int minZ;
	private int maxX;
	private int maxY;
	private int maxZ;

	public Area(String name, int color, int x1, int y1, int z1, int x2, int y2, int z2) {
		this(UUID.randomUUID().toString(), name, color, x1, y1, z1, x2, y2, z2);
	}

	public Area(String id, String name, int color, int x1, int y1, int z1, int x2, int y2, int z2) {
		this.id = id;
		this.name = name;
		setColor(color);
		setCorners(x1, y1, z1, x2, y2, z2);
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	/** RGB, no alpha. */
	public int getColor() {
		return color;
	}

	public void setColor(int color) {
		this.color = color & 0xFFFFFF;
	}

	public void setCorners(int x1, int y1, int z1, int x2, int y2, int z2) {
		this.minX = Math.min(x1, x2);
		this.minY = Math.min(y1, y2);
		this.minZ = Math.min(z1, z2);
		this.maxX = Math.max(x1, x2);
		this.maxY = Math.max(y1, y2);
		this.maxZ = Math.max(z1, z2);
	}

	public int getMinX() {
		return minX;
	}

	public int getMinY() {
		return minY;
	}

	public int getMinZ() {
		return minZ;
	}

	public int getMaxX() {
		return maxX;
	}

	public int getMaxY() {
		return maxY;
	}

	public int getMaxZ() {
		return maxZ;
	}

	public int getWidth() {
		return maxX - minX + 1;
	}

	public int getHeight() {
		return maxY - minY + 1;
	}

	public int getDepth() {
		return maxZ - minZ + 1;
	}

	public long getVolume() {
		return (long) getWidth() * getHeight() * getDepth();
	}

	/** Inclusive on every face. */
	public boolean contains(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}
}
