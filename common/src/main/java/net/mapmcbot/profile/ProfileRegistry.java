package net.mapmcbot.profile;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import net.mapmcbot.fleet.FleetChannel;

/**
 * CPU and memory of the mod (Java) and of the fleet (node), whole, per thread and per bot: a
 * {@link ProfileReport} each time one is taken, kept for the profiler screen and always appended to
 * the log file, whether the screen is open or not.
 *
 * Nothing is sampled on a timer. A profile is taken when someone asks for one (the screen opening,
 * its refresh, the player leaving the world), every FRAMES_PER_PROFILE frames through the channel,
 * and when the fleet takes one on an event of its own (a bot in a world or gone, a new formation):
 * the fleet's part comes from the fleet, the mod's is read as it arrives. With no fleet running,
 * the mod's part alone.
 *
 * Thread-safe: the channel thread feeds it, anyone may read it.
 *
 * SINGLETON: one per game; a second registry throws.
 */
public final class ProfileRegistry implements ProfileListener {
	private static final AtomicBoolean CREATED = new AtomicBoolean();

	/** Reports kept for the screen. */
	private static final int HISTORY = 120;

	/**
	 * Rates are worked out against the newest report at least this much older (ms): two events close
	 * together (a formation sends every bot) would give rates over a few milliseconds, all noise.
	 */
	private static final long MIN_INTERVAL = 1000;

	/** A profile is taken every this many frames through the channel, either way: as often as the fleet is busy, never while it is idle. */
	private static final long FRAMES_PER_PROFILE = 5000;

	/** Frames since the last profile taken for them. */
	private final AtomicLong frames = new AtomicLong();

	private final FleetChannel channel;
	/** Per bot that claimed them: {chunks, block entities, bytes of their NBT} held by the chunk registry. */
	private final Supplier<Map<String, long[]>> chunksByBot;
	private final BufferedWriter log;
	private final File logFile;
	private final LinkedList<ProfileReport> history = new LinkedList<ProfileReport>();
	private final com.sun.management.ThreadMXBean threads;
	private final com.sun.management.OperatingSystemMXBean system;

	private int lastRequest;
	/** The request whose profile has not arrived, 0 for none. */
	private volatile int waitingFor;

	public ProfileRegistry(FleetChannel channel, File logFile, Supplier<Map<String, long[]>> chunksByBot) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("ProfileRegistry is a singleton: one was already created");
		}

		if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean)
				|| !(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean)) {
			throw new IllegalStateException("the profiler reads CPU and allocations through com.sun.management, which this JVM ("
					+ System.getProperty("java.vm.name") + ") does not have");
		}

		threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		system = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

		if (!threads.isThreadCpuTimeSupported() || !threads.isThreadAllocatedMemorySupported()) {
			throw new IllegalStateException("this JVM cannot tell the CPU time or the allocations of its threads");
		}

		threads.setThreadCpuTimeEnabled(true);
		threads.setThreadAllocatedMemoryEnabled(true);

		this.channel = channel;
		this.chunksByBot = chunksByBot;
		this.logFile = logFile;

		if (!logFile.getParentFile().isDirectory() && !logFile.getParentFile().mkdirs()) {
			throw new IllegalStateException("cannot create " + logFile.getParentFile());
		}

		try {
			this.log = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
			log.write("# session " + LocalDateTime.now() + ": time, reason, scope, name, unit, value, rate (ms: % of a core, n and bytes: per second)");
			log.newLine();
			log.flush();
		} catch (IOException e) {
			throw new UncheckedIOException("could not open " + logFile, e);
		}

		channel.setProfileListener(this);
	}

	public File getLogFile() {
		return logFile;
	}

	/** Whether the fleet is up to be profiled too; without it a profile is the mod's alone. */
	public boolean isFleetRunning() {
		return channel.isRunning();
	}

	/** Whether the profile asked for last has not arrived yet. */
	public boolean isWaiting() {
		return waitingFor != 0;
	}

	/** Takes a profile: the fleet's arrives on the channel's thread (see onProfile); without a fleet, the mod's now. */
	public void request(String reason) {
		final int id;

		synchronized (this) {
			if (!channel.isRunning()) {
				record(reason, new ArrayList<ProfileReport.Row>());
				return;
			}

			id = ++lastRequest;
			waitingFor = id;
		}

		channel.requestProfile(id, reason);
	}

	/** The newest profile, null before the first. */
	public synchronized ProfileReport getLatest() {
		return history.isEmpty() ? null : history.getLast();
	}

	/** The profiles so far, oldest first. */
	public synchronized List<ProfileReport> getHistory() {
		return new ArrayList<ProfileReport>(history);
	}

	@Override
	public synchronized void onProfile(int id, String reason, String text) {
		record(reason, ProfileReport.parse(text));

		if (id != 0 && id == waitingFor) {
			waitingFor = 0;
		}
	}

	@Override
	public void onFrame() {
		if (frames.incrementAndGet() % FRAMES_PER_PROFILE != 0) {
			return;
		}

		// One still on its way covers these frames too; the next one is FRAMES_PER_PROFILE frames on.
		if (!isWaiting()) {
			request(FRAMES_PER_PROFILE + " frames");
		}
	}

	private synchronized void record(String reason, List<ProfileReport.Row> rows) {
		rows.addAll(javaRows());
		final long now = System.currentTimeMillis();
		ProfileReport base = history.isEmpty() ? null : history.getFirst();

		for (ProfileReport before : history) {
			if (now - before.getTime() >= MIN_INTERVAL) {
				base = before;
			}
		}

		final ProfileReport report = new ProfileReport(now, reason, rows, base);
		history.add(report);

		if (history.size() > HISTORY) {
			history.removeFirst();
		}

		write(report);
	}

	/** The mod's own rows: the JVM whole, each of its threads, and each bot's frames (see FleetChannel#channelRows). */
	private List<ProfileReport.Row> javaRows() {
		final List<ProfileReport.Row> rows = new ArrayList<ProfileReport.Row>();
		final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		final MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
		long gcCount = 0;
		long gcTime = 0;

		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			gcCount += gc.getCollectionCount();
			gcTime += gc.getCollectionTime();
		}

		rows.add(new ProfileReport.Row("java", "cpu", ProfileReport.MS, system.getProcessCpuTime() / 1e6));
		rows.add(new ProfileReport.Row("java", "gc.time", ProfileReport.MS, gcTime));
		rows.add(new ProfileReport.Row("java", "gc.count", ProfileReport.COUNT, gcCount));
		rows.add(new ProfileReport.Row("java", "mem.heapUsed", ProfileReport.BYTES, heap.getUsed()));
		rows.add(new ProfileReport.Row("java", "mem.heapCommitted", ProfileReport.BYTES, heap.getCommitted()));
		rows.add(new ProfileReport.Row("java", "mem.heapMax", ProfileReport.BYTES, heap.getMax()));
		rows.add(new ProfileReport.Row("java", "mem.nonHeapUsed", ProfileReport.BYTES, nonHeap.getUsed()));
		rows.add(new ProfileReport.Row("java", "mem.physicalFree", ProfileReport.BYTES, system.getFreePhysicalMemorySize()));
		rows.add(new ProfileReport.Row("java", "mem.physicalTotal", ProfileReport.BYTES, system.getTotalPhysicalMemorySize()));
		rows.add(new ProfileReport.Row("java", "uptime.s", ProfileReport.NOW, ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0));
		rows.add(new ProfileReport.Row("java", "cores", ProfileReport.NOW, Runtime.getRuntime().availableProcessors()));
		rows.add(new ProfileReport.Row("java", "threads", ProfileReport.NOW, threads.getThreadCount()));

		final long[] ids = threads.getAllThreadIds();
		final ThreadInfo[] infos = threads.getThreadInfo(ids);
		final long[] cpu = threads.getThreadCpuTime(ids);
		final long[] allocated = threads.getThreadAllocatedBytes(ids);

		for (int i = 0; i < ids.length; i++) {
			// Gone between the calls: nothing left of it to count.
			if (infos[i] == null || cpu[i] < 0 || allocated[i] < 0) {
				continue;
			}

			// The id keeps two threads of the same name apart, and each one's rates its own.
			final String scope = "java.thread:" + infos[i].getThreadName().replace('\t', ' ').replace('\n', ' ') + " (" + ids[i] + ")";
			rows.add(new ProfileReport.Row(scope, "cpu", ProfileReport.MS, cpu[i] / 1e6));
			rows.add(new ProfileReport.Row(scope, "alloc", ProfileReport.BYTES_SO_FAR, allocated[i]));
		}

		rows.addAll(channel.channelRows());

		for (Map.Entry<String, long[]> bot : chunksByBot.get().entrySet()) {
			final String scope = "java.bot:" + bot.getKey();
			rows.add(new ProfileReport.Row(scope, "chunks", ProfileReport.NOW, bot.getValue()[0]));
			rows.add(new ProfileReport.Row(scope, "blockEntities", ProfileReport.NOW, bot.getValue()[1]));
			rows.add(new ProfileReport.Row(scope, "mem.blockEntities", ProfileReport.BYTES, bot.getValue()[2]));
		}

		return rows;
	}

	/** Appends the report to the log: a summary line, then a line per row. Flushed: reports are rare. */
	private void write(ProfileReport report) {
		final String time = LocalDateTime.ofInstant(Instant.ofEpochMilli(report.getTime()), ZoneId.systemDefault()).toString();
		final String reason = report.getReason().replace('\t', ' ').replace('\n', ' ');

		try {
			log.write("# " + time + " " + reason + ": " + summary(report));
			log.newLine();

			for (String scope : report.scopes("")) {
				for (ProfileReport.Row row : report.rows(scope).values()) {
					log.write(time + "\t" + reason + "\t" + scope + "\t" + row.getName() + "\t" + row.getUnit() + "\t" + number(row.getValue())
							+ "\t" + (Double.isNaN(row.getRate()) ? "" : number(row.getRate())));
					log.newLine();
				}
			}

			log.flush();
		} catch (IOException e) {
			throw new UncheckedIOException("could not write " + logFile, e);
		}
	}

	/** The processes whole, in a line: CPU since the report before (on the first, the average since each started), memory now. */
	public static String summary(ProfileReport report) {
		final StringBuilder out = new StringBuilder();
		out.append(String.format(Locale.ROOT, "java cpu %s, heap %s of %s", cpu(report, "java", "cpu"),
				megabytes(report.value("java", "mem.heapUsed")), megabytes(report.value("java", "mem.heapMax"))));

		if (report.row("node", "mem.rss") != null) {
			out.append(String.format(Locale.ROOT, " | node cpu %s, rss %s, bots %d", cpu(report, "node", "cpu.user", "cpu.system"),
					megabytes(report.value("node", "mem.rss")), (int) report.value("node", "bots")));
		} else {
			out.append(" | no fleet");
		}

		if (report.hasRates()) {
			out.append(String.format(Locale.ROOT, " | over %.1f s", report.getInterval() / 1000.0));
		}

		return out.toString();
	}

	/** CPU of a process, the names added up: as % of a core since the report before, or its average since it started ("avg"). */
	private static String cpu(ProfileReport report, String scope, String... names) {
		double value = 0;

		for (String name : names) {
			value += report.hasRates() ? report.rate(scope, name) : report.value(scope, name);
		}

		if (report.hasRates()) {
			return percent(value);
		}

		return percent(value / (report.value(scope, "uptime.s") * 1000) * 100) + " avg";
	}

	private static String percent(double value) {
		return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f%%", value);
	}

	private static String megabytes(double bytes) {
		return String.format(Locale.ROOT, "%.0f MB", bytes / (1024 * 1024));
	}

	private static String number(double value) {
		return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : String.format(Locale.ROOT, "%.3f", value);
	}
}
