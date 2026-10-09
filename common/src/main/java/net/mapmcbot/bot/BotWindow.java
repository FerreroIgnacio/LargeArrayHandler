package net.mapmcbot.bot;

import java.util.Collections;
import java.util.List;

/**
 * The window a bot has open, as the fleet last reported it (WINDOW): its type, its own slots and
 * after them the bot's inventory, every slot's item. Neutral of the Minecraft version.
 *
 * Immutable: a new one comes with each change.
 */
public final class BotWindow {
	private final String type;
	private final int containerSlots;
	private final BotInventory.Item cursor;
	private final List<BotInventory.Item> slots;
	private final int[] properties;
	private final List<Trade> trades;

	public BotWindow(String type, int containerSlots, BotInventory.Item cursor, List<BotInventory.Item> slots, int[] properties, List<Trade> trades) {
		this.type = type;
		this.containerSlots = containerSlots;
		this.cursor = cursor;
		this.slots = Collections.unmodifiableList(slots);
		this.properties = properties.clone();
		this.trades = Collections.unmodifiableList(trades);
	}

	/** The registry name of its type: minecraft:chest, minecraft:furnace. */
	public String getType() {
		return type;
	}

	/** How many of the slots are the window's own; the bot's inventory is the rest. */
	public int getContainerSlots() {
		return containerSlots;
	}

	/** The item on the bot's cursor, null for none. */
	public BotInventory.Item getCursor() {
		return cursor;
	}

	/** Every slot's item by window slot, null for an empty one. */
	public List<BotInventory.Item> getSlots() {
		return slots;
	}

	/**
	 * The window's property by index as the server last set it (a furnace's burn and cook times, a
	 * brewing stand's brew time and fuel), 0 for one it has not set, as the game takes it.
	 */
	public int getProperty(int index) {
		return index < properties.length ? properties[index] : 0;
	}

	/** A villager's trades as the server last listed them, empty for any other window. */
	public List<Trade> getTrades() {
		return trades;
	}

	/** One of a villager's trades: what it takes (the second null for none), what it gives, and whether it is used up. */
	public static final class Trade {
		private final BotInventory.Item first;
		private final BotInventory.Item second;
		private final BotInventory.Item result;
		private final boolean disabled;

		public Trade(BotInventory.Item first, BotInventory.Item second, BotInventory.Item result, boolean disabled) {
			this.first = first;
			this.second = second;
			this.result = result;
			this.disabled = disabled;
		}

		public BotInventory.Item getFirst() {
			return first;
		}

		public BotInventory.Item getSecond() {
			return second;
		}

		public BotInventory.Item getResult() {
			return result;
		}

		public boolean isDisabled() {
			return disabled;
		}
	}
}
