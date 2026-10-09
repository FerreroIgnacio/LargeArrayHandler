package net.mapmcbot.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A bot that outlives the session: its name, the account it is linked to (null for none, a cracked
 * one), the skin it wears, the password it uses by default on a cracked server, the last server it
 * joined and every server it ever joined. Changes go through BotProfileStore so the file stays in step.
 */
public final class BotProfile {
	/** One join: the server, when (epoch ms) and the password used there. */
	public static final class Join {
		private final String server;
		private final long joinedAt;
		private final String password;

		public Join(String server, long joinedAt, String password) {
			this.server = server;
			this.joinedAt = joinedAt;
			this.password = password;
		}

		public String getServer() {
			return server;
		}

		public long getJoinedAt() {
			return joinedAt;
		}

		public String getPassword() {
			return password;
		}
	}

	private final String name;
	private final String linkedAccount;
	private final String skinName;
	private final String defaultCrackedPassword;
	private String lastServer;
	private final List<Join> joins = new ArrayList<Join>();

	public BotProfile(String name, String linkedAccount, String skinName, String defaultCrackedPassword, String lastServer) {
		this.name = name;
		this.linkedAccount = linkedAccount;
		this.skinName = skinName;
		this.defaultCrackedPassword = defaultCrackedPassword;
		this.lastServer = lastServer;
	}

	public String getName() {
		return name;
	}

	/** The linked account, null for none (a cracked bot). */
	public String getLinkedAccount() {
		return linkedAccount;
	}

	public String getSkinName() {
		return skinName;
	}

	public String getDefaultCrackedPassword() {
		return defaultCrackedPassword;
	}

	/** The last server joined as host:port, null before the first join. */
	public String getLastServer() {
		return lastServer;
	}

	public List<Join> getJoins() {
		return Collections.unmodifiableList(joins);
	}

	void addJoin(Join join) {
		joins.add(join);
		lastServer = join.getServer();
	}

	void restoreJoin(Join join) {
		joins.add(join);
	}
}
