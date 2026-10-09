package net.mapmcbot.job;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;

/**
 * Runs the job on the bots and records its steps. A bot runs the steps in order: each is sent when
 * the bot reports idle after the one before; an error in one stops the bot there, its state showing
 * it. Nothing is timed: the bot's own reports drive it.
 *
 * A step the fleet does not run (a grab) is the mod's: its Resolver does it on the bot with
 * primitives of its own, and says when it is done or failed.
 *
 * A primitive ordered from outside to a bot running the job takes it over: the job ends there.
 * Recording is one step at a time: armed, the next order to a bot is added to the job (the stop
 * primitive never is), and it disarms.
 */
public final class JobRunner implements BotRegistry.StateListener, BotRegistry.ActionListener {
	/** Does the steps the fleet does not run. */
	public interface Resolver {
		/** Whether the step is one of its own. */
		boolean handles(String json);

		/** Starts the step on the bot; its end goes to the listener, once. */
		void start(String bot, String json, Listener listener);
	}

	/** The end of a step the resolver did. */
	public interface Listener {
		void done();

		void failed();
	}

	private final BotRegistry bots;
	private final JobStore job;
	private Resolver resolver;

	/** The index of the step each running bot is on. */
	private final Map<String, Integer> running = new ConcurrentHashMap<String, Integer>();
	/** The running bots whose step is the resolver's: their states are its, not the job's. */
	private final Set<String> resolving = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean armed = new AtomicBoolean();

	public JobRunner(BotRegistry bots, JobStore job) {
		this.bots = bots;
		this.job = job;
		bots.addStateListener(this);
		bots.addActionListener(this);
	}

	public void setResolver(Resolver resolver) {
		this.resolver = resolver;
	}

	public JobStore getJob() {
		return job;
	}

	public boolean isArmed() {
		return armed.get();
	}

	public void setArmed(boolean value) {
		armed.set(value);
	}

	public boolean isRunning(String bot) {
		running.keySet().retainAll(bots.getBots());
		return running.containsKey(bot);
	}

	/** The step the bot is on, -1 when it does not run the job. */
	public int stepOf(String bot) {
		return isRunning(bot) ? running.get(bot) : -1;
	}

	public void start(String bot) {
		if (job.size() == 0) {
			throw new IllegalStateException("the job has no steps to start " + bot + " on");
		}

		if (running.putIfAbsent(bot, 0) != null) {
			throw new IllegalStateException(bot + " is already running the job");
		}

		send(bot, 0);
	}

	public void stop(String bot) {
		if (running.remove(bot) == null) {
			throw new IllegalStateException(bot + " is not running the job");
		}

		resolving.remove(bot);
		bots.action(bot, "{\"action\":\"stop\"}");
	}

	/**
	 * An order from outside that the fleet does not run (a grab started by hand): it takes the bot
	 * over from the job as a primitive does, and is recorded when armed.
	 */
	public void ordered(String bot, String json) {
		onAction(bot, json, false);
	}

	private void send(String bot, int index) {
		final String step = job.get(index);

		try {
			if (resolver != null && resolver.handles(step)) {
				resolving.add(bot);
				resolver.start(bot, step, new Listener() {
					@Override
					public void done() {
						resolving.remove(bot);

						if (running.get(bot) != null && running.get(bot) == index) {
							next(bot, index);
						}
					}

					@Override
					public void failed() {
						resolving.remove(bot);
						running.remove(bot, index);
					}
				});
			} else {
				bots.actionInternal(bot, step);
			}
		} catch (RuntimeException e) {
			running.remove(bot);
			resolving.remove(bot);
			throw e;
		}
	}

	private void next(String bot, int index) {
		final int next = index + 1;

		if (next >= job.size()) {
			running.remove(bot);
		} else {
			running.put(bot, next);
			send(bot, next);
		}
	}

	@Override
	public void onState(String bot, BotStatus status) {
		final Integer index = running.get(bot);

		if (index == null || resolving.contains(bot)) {
			return;
		}

		if (status.getKind() == BotStatus.Kind.ERROR) {
			running.remove(bot);
		} else if (status.getKind() == BotStatus.Kind.IDLE) {
			next(bot, index);
		}
	}

	@Override
	public void onAction(String bot, String json, boolean internal) {
		if (internal) {
			return;
		}

		// An order from outside takes the bot over from the job; it is not recorded.
		if (running.remove(bot) != null) {
			resolving.remove(bot);
			return;
		}

		if (!armed.get() || json.contains("\"action\":\"stop\"")) {
			return;
		}

		if (armed.compareAndSet(true, false)) {
			job.add(json);
		}
	}
}
