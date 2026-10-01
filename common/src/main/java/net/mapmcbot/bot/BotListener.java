package net.mapmcbot.bot;

/** What the fleet reports of its bots, called on the channel's thread. */
public interface BotListener {
	/** In the world, ready for orders. */
	void onSpawned(String bot);

	/** The path being walked: {target x, y, z, node x, y, z, ...}, sent again on each of its updates. */
	void onPath(String bot, int[] path);

	/** Not walking a path any more. */
	void onPathCleared(String bot);

	/** Left the server; its chunks are the fleet's, unloaded as the fleet's last bot lets each go. */
	void onGone(String bot);

	/** Every bot is gone at once. */
	void onClosed();
}
