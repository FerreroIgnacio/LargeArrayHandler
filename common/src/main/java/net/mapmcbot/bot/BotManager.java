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
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.mapmcbot.chunk.ChunkChannel;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkProtocol;

/**
 * The mineflayer bots, all in one node process (the fleet, fleet.js) on a pool of threads sharing their chunks, running from a folder in the
 * run directory. The fleet connects back to a local socket; bots are spawned and quit, and their
 * chunks reported, over it with {@link ChunkProtocol} frames. That socket is the registry's
 * {@link ChunkChannel}.
 *
 * The scripts ship inside the jar and are unpacked there because npm needs a real folder to install
 * into. The first bot started does the setup (unpack, npm install when mineflayer is missing, start
 * the fleet), on its own thread so the game never waits on it.
 */
public final class BotManager implements ChunkChannel {
	private static final String[] RESOURCES = {"fleet.js", "poolThread.js", "sharedChunks.js", "communalPaths.js", "bot.js", "serializer.js", "protocol.js", "states.js", "package.json"};

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

	private final File directory;

	/** The bots spawned and not yet gone; the start threads and the fleet reader touch it too. */
	private final Set<String> bots = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	/** The bots in the world (BOT_SPAWNED) and not yet gone. */
	private final Set<String> spawned = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	/** The path each walking bot is on, from its PATH frames. */
	private final Map<String, int[]> paths = new ConcurrentHashMap<String, int[]>();

	private final Object writeLock = new Object();

	private volatile ChunkChannel.Listener listener;
	private volatile boolean closing;
	private volatile Process fleet;
	private volatile OutputStream out;
	private volatile Thread reader;

	/**
	 * Every frame through the socket, one line each, in socket.log of the fleet's folder. Buffered and
	 * written out when the fleet goes away or the game exits, never per frame: a flush per frame held
	 * up the reader and, in send, the write lock.
	 */
	private volatile PrintWriter socketLog;

	public BotManager(File directory) {
		this.directory = directory;
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mapmcbot-fleet-shutdown"));
	}

	public Set<String> getBots() {
		return Collections.unmodifiableSet(bots);
	}

	/** connecting, idle or walking. */
	public String getStatus(String bot) {
		if (!spawned.contains(bot)) {
			return "connecting";
		}

		return paths.containsKey(bot) ? "walking" : "idle";
	}

	/** The paths being walked, by bot: {target x, y, z, node x, y, z, ...}. */
	public Map<String, int[]> getPaths() {
		return Collections.unmodifiableMap(paths);
	}

	/** Starts a bot that joins host:port as `name`, in the background. */
	public void create(final String name, final String host, final int port) {
		final Thread thread = new Thread(() -> {
			try {
				startFleet();
			} catch (IOException e) {
				throw new UncheckedIOException(name + " could not start: no bot fleet", e);
			} catch (InterruptedException e) {
				throw new IllegalStateException(name + " could not start: interrupted", e);
			}

			if (!bots.add(name)) {
				throw new IllegalStateException("a bot named " + name + " is already running");
			}

			send(ChunkProtocol.spawn(name, host, port));
		}, "mapmcbot-start-" + name);

		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * The bot walks to x,y,z, or next to it when another bot holds that block. `id` is the
	 * formation's, the same for every bot sent to it and higher than any before.
	 */
	public void formation(final String name, final int id, final int x, final int y, final int z) {
		if (!bots.contains(name)) {
			throw new IllegalStateException("formation for " + name + ": no such bot");
		}

		send(ChunkProtocol.formation(name, id, x, y, z));
	}

	/** Each bot leaves; its chunks are released when the fleet reports it gone. The fleet stays up. */
	public void stopAll() {
		for (String bot : bots) {
			send(ChunkProtocol.quit(bot));
		}

		bots.clear();
		spawned.clear();
		paths.clear();
	}

	@Override
	public void setListener(ChunkChannel.Listener listener) {
		if (this.listener != null) {
			throw new IllegalStateException("the bot fleet already has a chunk listener");
		}

		this.listener = listener;
	}

	@Override
	public void requestChunk(String bot, ChunkKey key, int claim) {
		send(ChunkProtocol.requestChunk(bot, key, claim));
	}

	private void send(byte[] frame) {
		synchronized (writeLock) {
			if (out == null) {
				throw new IllegalStateException("the bot fleet is not running");
			}

			try {
				logFrame("->", frame, 4);
				out.write(frame);
				out.flush();
			} catch (IOException e) {
				throw new UncheckedIOException("could not write to the bot fleet", e);
			}
		}
	}

	/** Once per manager; synchronized so bots started together wait on the one setup. */
	private synchronized void startFleet() throws IOException, InterruptedException {
		if (fleet != null) {
			return;
		}

		extractScripts();

		if (!new File(directory, "node_modules/mineflayer").isDirectory() || !new File(directory, "node_modules/mineflayer-pathfinder").isDirectory()) {
			System.out.println("[mapmcbot] installing mineflayer in " + directory);
			final int exit = new ProcessBuilder(WINDOWS ? "npm.cmd" : "npm", "install", "--no-audit", "--no-fund")
					.directory(directory).inheritIO().start().waitFor();

			if (exit != 0) {
				throw new IOException("npm install failed (exit " + exit + ")");
			}
		}

		socketLog = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(directory, "socket.log"), false), StandardCharsets.UTF_8), 1 << 16));
		final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		final Process process = new ProcessBuilder("node", "fleet.js", Integer.toString(server.getLocalPort()))
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

		reader = new Thread(() -> read(in), "mapmcbot-fleet-reader");
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

			while ((frame = ChunkProtocol.readFrame(in)) != null) {
				logFrame("<-", frame, 0);

				// The path being walked is for the renderer only; the registry never sees it.
				if (frame[0] == ChunkProtocol.PATH) {
					final String bot = ChunkProtocol.botGoneName(frame);
					final int[] path = ChunkProtocol.pathOf(frame);

					if (path == null) {
						paths.remove(bot);
					} else {
						paths.put(bot, path);
					}

					continue;
				}

				// Status only; the registry never sees it either.
				if (frame[0] == ChunkProtocol.BOT_SPAWNED) {
					spawned.add(ChunkProtocol.botGoneName(frame));
					continue;
				}

				if (frame[0] == ChunkProtocol.BOT_GONE) {
					final String gone = ChunkProtocol.botGoneName(frame);
					bots.remove(gone);
					spawned.remove(gone);
					paths.remove(gone);
				}

				ChunkProtocol.dispatch(frame, listener());
			}
		} catch (IOException e) {
			failure = e;
		}

		flushSocketLog();

		// Every bot went with the fleet, on purpose or not.
		bots.clear();
		spawned.clear();
		paths.clear();
		listener().onClosed();

		if (closing) {
			return;
		}

		if (failure != null) {
			throw new UncheckedIOException("lost the connection to the bot fleet", failure);
		}

		throw new IllegalStateException("the bot fleet closed its connection");
	}

	/** Type, bot and size of a frame; `offset` skips the length a written frame still has. */
	private void logFrame(String direction, byte[] frame, int offset) {
		final int type = frame[offset] & 0xFF;
		final int nameLength = (frame[offset + 1] & 0xFF) << 8 | frame[offset + 2] & 0xFF;
		final String bot = new String(frame, offset + 3, nameLength, StandardCharsets.UTF_8);

		synchronized (socketLog) {
			socketLog.println(LocalTime.now() + " " + direction + " type=" + type + " bot=" + bot + " bytes=" + (frame.length - offset));
		}
	}

	/** Writes out what socket.log holds (checkError flushes); PrintWriter keeps its write errors to itself, so one is thrown here. */
	private void flushSocketLog() {
		synchronized (socketLog) {
			if (socketLog.checkError()) {
				throw new UncheckedIOException(new IOException("could not write socket.log in " + directory));
			}
		}
	}

	private ChunkChannel.Listener listener() {
		final ChunkChannel.Listener current = listener;

		if (current == null) {
			throw new IllegalStateException("the bot fleet has no chunk listener");
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
