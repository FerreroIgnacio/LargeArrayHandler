package net.mapmcbot.bot;

/** What the fleet reports of its bots, called on the channel's thread. */
public interface BotListener {
	/** In the world, ready for orders. */
	void onSpawned(String bot);

	/** The path being walked: {target x, y, z, node x, y, z, ...}, sent again on each of its updates. */
	void onPath(String bot, int[] path);

	/** Not walking a path any more. */
	void onPathCleared(String bot);

	/** Its inventory window changed: the whole of it now. */
	void onInventory(String bot, BotInventory inventory);

	/** What it is doing now (see BotStatus). */
	void onState(String bot, BotStatus status);

	/** The window it has open changed: the whole of it now, null once none is open. */
	void onWindow(String bot, BotWindow window);

	/** Left the server; its chunks are the fleet's, unloaded as the fleet's last bot lets each go. */
	void onGone(String bot);

	/** Every bot is gone at once. */
	void onClosed();
}
