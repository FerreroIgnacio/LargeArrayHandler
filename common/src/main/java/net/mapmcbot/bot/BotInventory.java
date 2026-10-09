package net.mapmcbot.bot;

import java.util.Collections;
import java.util.List;

/**
 * A bot's inventory as its own window holds it, as the fleet last reported it (INVENTORY): every
 * slot by window id (46 from 1.9, 45 before), the item on its cursor and the hotbar slot in its hand.
 * Neutral of the Minecraft version: each version of the mod makes its own items of it.
 *
 * Immutable: a new one comes with each change.
 */
public final class BotInventory {
	/** An item: its registry name ("minecraft:stone"), count, metadata (0 from 1.13) and NBT (empty for none). */
	public static final class Item {
		private final String name;
		private final int count;
		private final int metadata;
		private final byte[] nbt;

		public Item(String name, int count, int metadata, byte[] nbt) {
			this.name = name;
			this.count = count;
			this.metadata = metadata;
			this.nbt = nbt;
		}

		public String getName() {
			return name;
		}

		public int getCount() {
			return count;
		}

		public int getMetadata() {
			return metadata;
		}

		/** A named root compound, big-endian; empty when the item has none. */
		public byte[] getNbt() {
			return nbt;
		}
	}

	private final int held;
	private final int lastClick;
	private final Item cursor;
	private final List<Item> slots;

	public BotInventory(int held, int lastClick, Item cursor, List<Item> slots) {
		this.held = held;
		this.lastClick = lastClick;
		this.cursor = cursor;
		this.slots = Collections.unmodifiableList(slots);
	}

	/** The hotbar slot in its hand, 0-8. */
	public int getHeld() {
		return held;
	}

	/** The id of the last click on it the bot did (see FleetProtocol#WINDOW_CLICK), 0 before any. */
	public int getLastClick() {
		return lastClick;
	}

	/** The item on its cursor, null when none. */
	public Item getCursor() {
		return cursor;
	}

	/** Every slot by window id, null for an empty one. */
	public List<Item> getSlots() {
		return slots;
	}
}
