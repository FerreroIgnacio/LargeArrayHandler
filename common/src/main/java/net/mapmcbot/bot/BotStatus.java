package net.mapmcbot.bot;

/**
 * What a bot is doing, as the fleet reports it (STATE): idle, doing a primitive, or in error in
 * one with the whole message (what was expected, what was found). An error stays until the bot's
 * next primitive; the bot is neither retried nor reset.
 *
 * Immutable: a new one comes with each change.
 */
public final class BotStatus {
	public enum Kind {
		IDLE, DOING, ERROR
	}

	public static final BotStatus IDLE = new BotStatus(Kind.IDLE, "", "");

	private final Kind kind;
	private final String primitive;
	private final String message;

	public BotStatus(Kind kind, String primitive, String message) {
		this.kind = kind;
		this.primitive = primitive;
		this.message = message;
	}

	public Kind getKind() {
		return kind;
	}

	public String getPrimitive() {
		return primitive;
	}

	/** Why the primitive failed; empty unless in error. */
	public String getMessage() {
		return message;
	}

	/** idle, doing goto, error goto: expected a path to 1,2,3, found none. */
	@Override
	public String toString() {
		switch (kind) {
			case DOING:
				return "doing " + primitive;
			case ERROR:
				return "error " + primitive + ": " + message;
			default:
				return "idle";
		}
	}
}
