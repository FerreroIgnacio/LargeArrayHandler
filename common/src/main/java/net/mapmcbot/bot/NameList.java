package net.mapmcbot.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The names the mod gives its bots, so no fleet (main or relay) has to pick one: the mod hands the
 * name over in the SPAWN and takes it back when the bot is gone. A name is in use by one bot at a time.
 *
 * Thread-safe: the game thread takes, the channel thread gives back.
 */
public final class NameList {
	private final List<String> names;
	private final Set<String> taken = new HashSet<String>();

	public NameList(List<String> names) {
		if (names.isEmpty() || new HashSet<String>(names).size() != names.size()) {
			throw new IllegalArgumentException("a name list needs at least one name, none twice");
		}

		this.names = new ArrayList<String>(names);
	}

	/** The first name nobody uses; fails when they are all in use. */
	public synchronized String take() {
		for (String name : names) {
			if (taken.add(name)) {
				return name;
			}
		}

		throw new IllegalStateException("all " + names.size() + " names are in use");
	}

	/** The name is free again. */
	public synchronized void give(String name) {
		if (!taken.remove(name)) {
			throw new IllegalStateException("name " + name + " was not in use");
		}
	}

	public synchronized void giveAll() {
		taken.clear();
	}
}
