package net.mapmcbot.bot;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.fleet.FleetChannel;
import net.mapmcbot.fleet.FleetProtocol;
import net.mapmcbot.profile.ProfileListener;
import net.mapmcbot.profile.ProfileReport;

/**
 * The mineflayer bots, all in one node process (the fleet, fleet.js) on a pool of threads sharing their chunks, running from a folder in the
 * run directory. The fleet connects back to a local socket; bots are spawned and quit, and their
 * chunks reported, over it with {@link FleetProtocol} frames. That socket is the {@link FleetChannel}:
 * this class only runs the process and the socket, what the fleet reports goes to the chunk and
 * bot listeners (ChunkRegistry, BotRegistry). The chunks' blocks go through columns.bin in that
 * folder instead, made anew with the manager and mapped by the fleet and the chunk registry.
 *
 * The scripts ship inside the jar and are unpacked there because npm needs a real folder to install
 * into. The first bot started does the setup (unpack, npm install when mineflayer is missing, start
 * the fleet), on its own thread so the game never waits on it.
 *
 * SINGLETON: one fleet per game; a second manager throws.
 */
public final class BotManager implements FleetChannel {
	private static final AtomicBoolean CREATED = new AtomicBoolean();

	private static final String[] RESOURCES = {"fleet.js", "poolThread.js", "sharedChunks.js", "bot.js", "actions.js", "profiler.js", "protocol.js", "states.js", "relaySupervisor.js", "columnSerializer.js", "package.json"};

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

	private final File directory;
	private final File columns;

	private final Object writeLock = new Object();

	private volatile ChunkListener chunkListener;
	private volatile BotListener botListener;
	private volatile ProfileListener profileListener;

	/** HotSpot's: CPU time and allocations of the reader thread, per frame. */
	private final com.sun.management.ThreadMXBean threadBean;

	/** What each bot's frames cost the mod, by bot name; "" for the fleet's own frames. */
	private final Map<String, Traffic> traffic = new ConcurrentHashMap<String, Traffic>();
	private volatile boolean closing;
	private volatile Process fleet;
	private volatile OutputStream out;
	private volatile Thread reader;
	/** Told when the reader dies of an exception, which the frames after it never get past. */
	private volatile Consumer<Throwable> readerFailure;

	public void setReaderFailure(Consumer<Throwable> listener) {
		this.readerFailure = listener;
	}

	/**
	 * Every frame through the socket, one line each, in socket.log of the fleet's folder. Buffered and
	 * written out every SOCKET_LOG_LINES lines, when the fleet goes away and when the game exits, never
	 * per frame: a flush per frame held up the reader and, in send, the write lock. A game killed
	 * outright loses at most the last SOCKET_LOG_LINES lines.
	 */
	private volatile PrintWriter socketLog;

	private static final int SOCKET_LOG_LINES = 1000;

	/** Lines in socket.log since it was last written out; guarded by socketLog. */
	private int socketLogPending;

	public BotManager(File directory) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("BotManager is a singleton: one was already created");
		}

		if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean)) {
			throw new IllegalStateException("the profiler reads the CPU and allocations of the fleet's frames through com.sun.management, which this JVM ("
					+ System.getProperty("java.vm.name") + ") does not have");
		}

		threadBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

		if (!threadBean.isCurrentThreadCpuTimeSupported() || !threadBean.isThreadAllocatedMemorySupported()) {
			throw new IllegalStateException("this JVM cannot tell the CPU time or the allocations of a thread");
		}

		threadBean.setThreadCpuTimeEnabled(true);
		threadBean.setThreadAllocatedMemoryEnabled(true);

		this.directory = directory;
		this.columns = new File(directory, "columns.bin");
		createColumns();
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mapmcbot-fleet-shutdown"));
	}

	@Override
	public void setChunkListener(ChunkListener listener) {
		if (chunkListener != null) {
			throw new IllegalStateException("the bot fleet already has a chunk listener");
		}

		chunkListener = listener;
	}

	@Override
	public void setBotListener(BotListener listener) {
		if (botListener != null) {
			throw new IllegalStateException("the bot fleet already has a bot listener");
		}

		botListener = listener;
	}

	@Override
	public void setProfileListener(ProfileListener listener) {
		if (profileListener != null) {
			throw new IllegalStateException("the bot fleet already has a profile listener");
		}

		profileListener = listener;
	}

	@Override
	public boolean isRunning() {
		return out != null && !closing;
	}

	@Override
	public void requestProfile(int id, String reason) {
		send(FleetProtocol.profileRequest(id, reason));
	}

	@Override
	public List<ProfileReport.Row> channelRows() {
		final List<ProfileReport.Row> rows = new ArrayList<ProfileReport.Row>();

		for (Map.Entry<String, Traffic> entry : traffic.entrySet()) {
			final String scope = entry.getKey().isEmpty() ? "java.channel" : "java.bot:" + entry.getKey();
			final Traffic t = entry.getValue();
			rows.add(new ProfileReport.Row(scope, "cpu.frames", ProfileReport.MS, t.cpu.get() / 1e6));
			rows.add(new ProfileReport.Row(scope, "alloc", ProfileReport.BYTES_SO_FAR, t.allocated.get()));
			rows.add(new ProfileReport.Row(scope, "frames.in", ProfileReport.COUNT, t.framesIn.get()));
			rows.add(new ProfileReport.Row(scope, "net.in", ProfileReport.BYTES_SO_FAR, t.bytesIn.get()));
			rows.add(new ProfileReport.Row(scope, "frames.out", ProfileReport.COUNT, t.framesOut.get()));
			rows.add(new ProfileReport.Row(scope, "net.out", ProfileReport.BYTES_SO_FAR, t.bytesOut.get()));
		}

		return rows;
	}

	private Traffic traffic(String bot) {
		return traffic.computeIfAbsent(bot, name -> new Traffic());
	}

	/** In the background: the first bot starts the fleet, on its own thread so the game never waits on it. */
	@Override
	public void spawn(final String bot, final String host, final int port) {
		final Thread thread = new Thread(() -> {
			try {
				startFleet();
			} catch (IOException e) {
				throw new UncheckedIOException(bot + " could not start: no bot fleet", e);
			} catch (InterruptedException e) {
				throw new IllegalStateException(bot + " could not start: interrupted", e);
			}

			send(FleetProtocol.spawn(bot, host, port));
		}, "mapmcbot-start-" + bot);

		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public void quit(String bot) {
		send(FleetProtocol.quit(bot));
	}

	@Override
	public void windowClick(String bot, int slot, int button, int mode, int id) {
		send(FleetProtocol.windowClick(bot, slot, button, mode, id));
	}

	@Override
	public void tradeSelect(String bot, int trade) {
		send(FleetProtocol.tradeSelect(bot, trade));
	}

	@Override
	public void action(String bot, String json) {
		send(FleetProtocol.action(bot, json));
	}

	@Override
	public void gotoSpot(String bot, int x, int y, int z) {
		send(FleetProtocol.gotoSpot(bot, x, y, z));
	}

	@Override
	public File columns() {
		return columns;
	}

	@Override
	public void release(String bot, int slot) {
		send(FleetProtocol.release(bot, slot));
	}

	@Override
	public void loaded(ChunkKey key, int slot, String mcVersion) {
		send(FleetProtocol.loaded(key, slot, mcVersion));
	}

	/** Every slot zeros, the last game's columns gone. */
	private void createColumns() {
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IllegalStateException("cannot create " + directory);
		}

		try {
			Files.deleteIfExists(columns.toPath());

			try (RandomAccessFile file = new RandomAccessFile(columns, "rw")) {
				file.setLength((long) FleetProtocol.COLUMN_SLOTS * FleetProtocol.COLUMN_SLOT_SIZE);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("could not create " + columns, e);
		}
	}

	private void send(byte[] frame) {
		synchronized (writeLock) {
			if (out == null) {
				throw new IllegalStateException("the bot fleet is not running");
			}

			try {
				logFrame("->", frame, 4);
				final Traffic t = traffic(botOf(frame, 4));
				t.framesOut.incrementAndGet();
				t.bytesOut.addAndGet(frame.length);
				out.write(frame);
				out.flush();
			} catch (IOException e) {
				throw new UncheckedIOException("could not write to the bot fleet", e);
			}
		}

		// Outside the lock: the profile it may ask for is a frame of its own.
		profileListener().onFrame();
	}

	/** Once per manager; synchronized so bots started together wait on the one setup. */
	private synchronized void startFleet() throws IOException, InterruptedException {
		if (fleet != null) {
			return;
		}

		extractScripts();

		if (!new File(directory, "node_modules/mineflayer").isDirectory() || !new File(directory, "node_modules/mineflayer-pathfinder").isDirectory()
				|| !new File(directory, "node_modules/@riaskov/mmap-io").isDirectory()) {
			System.out.println("[mapmcbot] installing mineflayer in " + directory);
			final int exit = new ProcessBuilder(WINDOWS ? "npm.cmd" : "npm", "install", "--no-audit", "--no-fund")
					.directory(directory).inheritIO().start().waitFor();

			if (exit != 0) {
				throw new IOException("npm install failed (exit " + exit + ")");
			}
		}

		socketLog = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(directory, "socket.log"), false), StandardCharsets.UTF_8), 1 << 16));
		final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		final Process process = new ProcessBuilder("node", "fleet.js", Integer.toString(server.getLocalPort()), columns.getAbsolutePath())
				.directory(directory).redirectErrorStream(true).start();
		pump(process, server);

		final Socket socket;

		// The pump closes the server socket if node dies first, which ends this accept.
		try {
			socket = server.accept();
		} finally {
			server.close();
		}

		socket.setTcpNoDelay(true);
		final InputStream in = new BufferedInputStream(socket.getInputStream());
		out = new BufferedOutputStream(socket.getOutputStream());
		fleet = process;

		reader = new Thread(() -> {
			try {
				read(in);
			} catch (RuntimeException e) {
				final Consumer<Throwable> listener = readerFailure;

				if (listener != null) {
					listener.accept(e);
				}

				throw e;
			}
		}, "mapmcbot-fleet-reader");
		reader.setDaemon(true);
		reader.start();
	}

	/**
	 * Fleet output to the game log and, each line with its time, to fleet.log of the fleet's folder
	 * (the game log can drown in a map's own output); the fleet exiting on its own is a failure.
	 * Written out line by line: the fleet says little, and its last words matter most.
	 */
		private void pump(final Process process, final ServerSocket server) {
		final Thread pump = new Thread(() -> {
			try (BufferedReader lines = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
					BufferedWriter fleetLog = new BufferedWriter(new OutputStreamWriter(
							new FileOutputStream(new File(directory, "fleet.log"), false), StandardCharsets.UTF_8))) {
				String line;

				while ((line = lines.readLine()) != null) {
					System.out.println("[mapmcbot fleet] " + line);
					fleetLog.write(LocalTime.now() + " " + line);
					fleetLog.newLine();
					fleetLog.flush();
				}

				server.close();
			} catch (IOException e) {
				throw new UncheckedIOException("lost the bot fleet's output", e);
			}

			final int exit;

			try {
				exit = process.waitFor();
			} catch (InterruptedException e) {
				throw new IllegalStateException("interrupted waiting for the bot fleet", e);
			}

			if (!closing) {
				throw new IllegalStateException("the bot fleet exited on its own (exit " + exit + ")");
			}
		}, "mapmcbot-fleet-output");

		pump.setDaemon(true);
		pump.start();
	}

	private void read(InputStream in) {
		IOException failure = null;

		try {
			byte[] frame;

			while ((frame = FleetProtocol.readFrame(in)) != null) {
				logFrame("<-", frame, 0);
				// What the frame cost here, the snapshots it makes the chunk registry write included.
				final long cpu = threadBean.getCurrentThreadCpuTime();
				final long allocated = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
				FleetProtocol.dispatch(frame, chunkListener(), botListener(), profileListener());
				profileListener().onFrame();
				final String bot = botOf(frame, 0);

				// Gone: nothing more of it to count.
				if ((frame[0] & 0xFF) == FleetProtocol.BOT_GONE) {
					traffic.remove(bot);
					continue;
				}

				final Traffic t = traffic(bot);
				t.cpu.addAndGet(threadBean.getCurrentThreadCpuTime() - cpu);
				t.allocated.addAndGet(threadBean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated);
				t.framesIn.incrementAndGet();
				t.bytesIn.addAndGet(frame.length + 4);
			}
		} catch (IOException e) {
			failure = e;
		}

		flushSocketLog();

		// Every bot went with the fleet, on purpose or not.
		botListener().onClosed();
		chunkListener().onClosed();

		if (closing) {
			return;
		}

		if (failure != null) {
			throw new UncheckedIOException("lost the connection to the bot fleet", failure);
		}

		throw new IllegalStateException("the bot fleet closed its connection");
	}

	/** The bot a frame is about, "" for the fleet's own; `offset` skips the length a written frame still has. */
	private static String botOf(byte[] frame, int offset) {
		final int nameLength = (frame[offset + 1] & 0xFF) << 8 | frame[offset + 2] & 0xFF;
		return new String(frame, offset + 3, nameLength, StandardCharsets.UTF_8);
	}

	/** Type, bot and size of a frame; `offset` skips the length a written frame still has. */
	private void logFrame(String direction, byte[] frame, int offset) {
		final int type = frame[offset] & 0xFF;
		final String bot = botOf(frame, offset);

		synchronized (socketLog) {
			socketLog.println(LocalTime.now() + " " + direction + " type=" + type + " bot=" + bot + " bytes=" + (frame.length - offset));

			if (++socketLogPending >= SOCKET_LOG_LINES) {
				flushSocketLog();
			}
		}
	}

	/** Writes out what socket.log holds (checkError flushes); PrintWriter keeps its write errors to itself, so one is thrown here. */
	private void flushSocketLog() {
		synchronized (socketLog) {
			socketLogPending = 0;

			if (socketLog.checkError()) {
				throw new UncheckedIOException(new IOException("could not write socket.log in " + directory));
			}
		}
	}

	private ChunkListener chunkListener() {
		final ChunkListener current = chunkListener;

		if (current == null) {
			throw new IllegalStateException("the bot fleet has no chunk listener");
		}

		return current;
	}

	private ProfileListener profileListener() {
		final ProfileListener current = profileListener;

		if (current == null) {
			throw new IllegalStateException("the bot fleet has no profile listener");
		}

		return current;
	}

	private BotListener botListener() {
		final BotListener current = botListener;

		if (current == null) {
			throw new IllegalStateException("the bot fleet has no bot listener");
		}

		return current;
	}

	/** JVM exit: kill the fleet and wait for the reader to hand every chunk to its snapshot. */
	private void shutdown() {
		closing = true;
		final Process process = fleet;

		if (process == null) {
			return;
		}

		process.destroy();

		try {
			reader.join();
		} catch (InterruptedException e) {
			throw new IllegalStateException("interrupted waiting for the bot fleet reader", e);
		}

		flushSocketLog();
	}

	/** Overwrites only what changed, so a mod update reaches the scripts without touching the rest. */
	private void extractScripts() throws IOException {
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IOException("cannot create " + directory);
		}

		for (String resource : RESOURCES) {
			final byte[] bytes;

			try (InputStream in = BotManager.class.getResourceAsStream("/bot/" + resource)) {
				if (in == null) {
					throw new IOException("missing /bot/" + resource + " in the jar");
				}

				bytes = readAll(in);
			}

			final File target = new File(directory, resource);

			if (!target.isFile() || !Arrays.equals(Files.readAllBytes(target.toPath()), bytes)) {
				Files.write(target.toPath(), bytes);
			}
		}
	}

	/** What one bot's frames (or the fleet's own) cost the mod so far: reader thread CPU (ns), allocations, frames and bytes. */
	private static final class Traffic {
		final AtomicLong cpu = new AtomicLong();
		final AtomicLong allocated = new AtomicLong();
		final AtomicLong framesIn = new AtomicLong();
		final AtomicLong bytesIn = new AtomicLong();
		final AtomicLong framesOut = new AtomicLong();
		final AtomicLong bytesOut = new AtomicLong();
	}

	private static byte[] readAll(InputStream in) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		int read;

		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}

		return out.toByteArray();
	}
}
