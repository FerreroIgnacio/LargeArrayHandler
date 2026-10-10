package net.mapmcbot.job;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;

/**
 * Runs the jobs on the bots and records their steps. A bot runs the steps of the job it was started
 * on in order: each is sent when the bot reports idle after the one before; an error in one stops the
 * bot there, its state showing it. Looping, it starts over after the last. Nothing is timed: the
 * bot's own reports drive it.
 *
 * A step the fleet does not run (a grab) is the mod's: its Resolver does it on the bot with
 * primitives of its own, and says when it is done or failed.
 *
 * A primitive ordered from outside to a bot running the job takes it over: the job ends there.
 * Recording is one step at a time: armed, the next order to a bot is added to the selected job (the
 * stop primitive never is), and it disarms.
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

	/** Where a running bot is: its job, the step it is on, whether it loops. A new one for each step. */
	private static final class Run {
		final JobStore.Job job;
		final int index;
		final boolean loop;

		Run(JobStore.Job job, int index, boolean loop) {
			this.job = job;
			this.index = index;
			this.loop = loop;
		}
	}

	private final BotRegistry bots;
	private final JobStore jobs;
	/** The job shown, started and recorded into. */
	private volatile JobStore.Job selected;
	private Resolver resolver;

	private final Map<String, Run> running = new ConcurrentHashMap<String, Run>();
	/** The running bots whose step is the resolver's: their states are its, not the job's. */
	private final Set<String> resolving = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean armed = new AtomicBoolean();

	public JobRunner(BotRegistry bots, JobStore jobs) {
		this.bots = bots;
		this.jobs = jobs;
		this.selected = jobs.get(0);
		bots.addStateListener(this);
		bots.addActionListener(this);
	}

	public void setResolver(Resolver resolver) {
		this.resolver = resolver;
	}

	public JobStore getJobs() {
		return jobs;
	}

	public JobStore.Job getSelected() {
		return selected;
	}

	public void select(JobStore.Job job) {
		selected = job;
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

	/** The job the bot runs, null when it runs none. */
	public JobStore.Job jobOf(String bot) {
		final Run run = isRunning(bot) ? running.get(bot) : null;
		return run == null ? null : run.job;
	}

	/** The step the bot is on, -1 when it does not run a job. */
	public int stepOf(String bot) {
		final Run run = isRunning(bot) ? running.get(bot) : null;
		return run == null ? -1 : run.index;
	}

	public boolean isLooping(String bot) {
		final Run run = isRunning(bot) ? running.get(bot) : null;
		return run != null && run.loop;
	}

	/** Starts the selected job on the bot, once or over and over. */
	public void start(String bot, boolean loop) {
		final JobStore.Job job = selected;

		if (job.size() == 0) {
			throw new IllegalStateException("the job has no steps to start " + bot + " on");
		}

		final Run run = new Run(job, 0, loop);

		if (running.putIfAbsent(bot, run) != null) {
			throw new IllegalStateException(bot + " is already running a job");
		}

		send(bot, run);
	}

	public void stop(String bot) {
		if (running.remove(bot) == null) {
			throw new IllegalStateException(bot + " is not running a job");
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

	private void send(String bot, Run run) {
		final String step = run.job.get(run.index);

		try {
			if (resolver != null && resolver.handles(step)) {
				resolving.add(bot);
				resolver.start(bot, step, new Listener() {
					@Override
					public void done() {
						resolving.remove(bot);

						if (running.get(bot) == run) {
							next(bot, run);
						}
					}

					@Override
					public void failed() {
						resolving.remove(bot);
						running.remove(bot, run);
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

	private void next(String bot, Run run) {
		final int size = run.job.size();
		int next = run.index + 1;

		if (next >= size && run.loop) {
			next = 0;
		}

		if (next >= size) {
			running.remove(bot);
		} else {
			final Run after = new Run(run.job, next, run.loop);
			running.put(bot, after);
			send(bot, after);
		}
	}

	@Override
	public void onState(String bot, BotStatus status) {
		final Run run = running.get(bot);

		if (run == null || resolving.contains(bot)) {
			return;
		}

		if (status.getKind() == BotStatus.Kind.ERROR) {
			running.remove(bot);
		} else if (status.getKind() == BotStatus.Kind.IDLE) {
			next(bot, run);
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
			selected.add(json);
		}
	}
}
