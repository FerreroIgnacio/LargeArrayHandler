package net.mapmcbot.profile;

/** What the fleet reports of its profile, called on the channel's thread. */
public interface ProfileListener {
	/**
	 * The fleet's profile: `id` the request's, 0 when the fleet took it on an event of its own;
	 * `reason` what it was taken for; `text` its rows, a line each: scope, name, unit, value,
	 * tab-separated (see bot/profiler.js).
	 */
	void onProfile(int id, String reason, String text);
}
