package net.mapmcbot.bot;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.mapmcbot.fleet.FleetProtocol;
import net.mapmcbot.profile.ProfileListener;

/**
 * The relays: fleets (fleet.js --relay) on other machines, left running, that the mod connects to
 * when it starts to run more bots there. They are listed in a file, one host[:port] a line
 * (relays.txt in the mod's folder, # for comments; the port is DEFAULT_PORT when left out). The
 * mod only knocks: a relay that is up takes the connection, one that is not is said so in the log
 * and skipped. Each relay connected gets a rid, 1, 2, ... (0 is the mod's own fleet), and talks the
 * same {@link FleetProtocol} frames as that one, except the ones about columns: a relay's world is
 * its own and none of it goes to the mod, so such a frame from a relay is a failure.
 *
 * The first frame to a relay is HELLO with the commit the mod was built at; it is not connected until
 * it answers WELCOME. A relay at another commit says UPDATING and updates itself first (git fetch and
 * reset --hard to it, then it restarts on the same connection, see bot/fleet.js), or answers
 * UPDATE_FAILED when it cannot get to it (a commit not pushed, say) and stays up
 * for the next knock; why is kept in its crash log. Until connected, its {@link #pending} state says
 * which of these it is in. A relay is handed bots from the knock on, as one connected: their orders
 * wait in it and go once it is WELCOME, and should it fail before that they go to another fleet.
 *
 * Connecting happens on the mod's start ({@link #start}) and when asked for again
 * ({@link #connectMissing}), for a relay started later; never on a timer.
 */
public final class RelayHub {
	/** The port a relay listens on, unless its line in the file says another. */
	public static final int DEFAULT_PORT = 16767;

	/** What the profiler hears of the relays, called on the relay's reader thread. */
	public interface ProfileSink {
		/** A relay's profile (same rows as the mod's own fleet's; see bot/profiler.js). */
		void onRelayProfile(int rid, String reason, String text);

		void onRelayGone(int rid);
	}

	private final File file;
	/** The relays (host:port) being connected to or connected. */
	private final Set<String> active = ConcurrentHashMap.newKeySet();
	/** The relays knocked on and not failed, by rid: starting (connecting or updating) or connected. */
	private final Map<Integer, Relay> relays = new ConcurrentHashMap<Integer, Relay>();
	private final AtomicInteger nextRid = new AtomicInteger(1);
	/** The relays (host:port) knocked on and not connected: connecting, updating, update failed or crashed. */
	private final Map<String, String> pending = new ConcurrentHashMap<String, String>();

	private volatile BotRegistry bots;
	private volatile ProfileSink profiles;
	private volatile boolean started;

	public RelayHub(File file) {
		this.file = file;
	}

	/** Once, before start. */
	void setBots(BotRegistry bots) {
		if (this.bots != null) {
			throw new IllegalStateException("the relay hub already has a bot registry");
		}

		this.bots = bots;
	}

	/** Once, before start. */
	public void setProfileSink(ProfileSink profiles) {
		if (this.profiles != null) {
			throw new IllegalStateException("the relay hub already has a profile sink");
		}

		this.profiles = profiles;
	}

	/** Connects to the relays listed, once the registries that hear them are set. */
	public void start() {
		if (bots == null || profiles == null) {
			throw new IllegalStateException("the relay hub starts after its bot registry and profile sink are set");
		}

		if (started) {
			throw new IllegalStateException("the relay hub already started");
		}

		started = true;
		connectMissing();
	}

	/** Knocks on every relay listed that is not connected yet; each answers on its own thread. */
	public void connectMissing() {
		for (String address : addresses()) {
			if (!active.add(address)) {
				continue;
			}

			pending.put(address, "connecting");
			final Relay relay = new Relay(nextRid.getAndIncrement(), address);
			relays.put(relay.rid, relay);
			final Thread connect = new Thread(() -> connect(relay), "mapmcbot-relay-connect-" + address);
			connect.setDaemon(true);
			connect.start();
		}
	}

	/** The lines of the file as host:port; none when there is no file. */
	private List<String> addresses() {
		if (!file.isFile()) {
			return Collections.emptyList();
		}

		final List<String> addresses = new ArrayList<String>();

		try {
			for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
				final String entry = line.trim();

				if (entry.isEmpty() || entry.startsWith("#")) {
					continue;
				}

				final int colon = entry.lastIndexOf(':');

				if (colon < 0) {
					addresses.add(entry + ":" + DEFAULT_PORT);
					continue;
				}

				try {
					Integer.parseInt(entry.substring(colon + 1));
				} catch (NumberFormatException e) {
					throw new IllegalStateException(file + ": bad port in the line \"" + entry + "\"", e);
				}

				addresses.add(entry);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("could not read " + file, e);
		}

		return addresses;
	}

	private void connect(Relay relay) {
		final String address = relay.address;
		boolean welcomed = false;

		try {
			try {
				relay.open();
			} catch (IOException e) {
				active.remove(address);
				pending.remove(address);
				System.out.println("[mapmcbot] relay " + address + " is not alive: " + e);
				return;
			}

			// Blocks this thread until the relay runs the mod's commit, which may take it a reset and a restart.
			relay.write(FleetProtocol.hello(commit()));

			if (!relay.awaitWelcome()) {
				return;
			}

			pending.remove(address);
			System.out.println("[mapmcbot] relay " + relay.rid + " (" + address + ") is alive and connected");
			relay.welcome();
			welcomed = true;
			relay.startReading();
		} catch (IOException e) {
			throw new UncheckedIOException("could not talk to relay " + address, e);
		} finally {
			if (!welcomed) {
				// Its bots never got there: another fleet runs them.
				synchronized (bots) {
					relays.remove(relay.rid);
					bots.onRelayFailed(relay.rid);
				}

				relay.close();
			}
		}
	}

	/** The rids of the relays starting or connected, ascending: each takes bots. */
	public List<Integer> rids() {
		final List<Integer> rids = new ArrayList<Integer>(relays.keySet());
		Collections.sort(rids);
		return rids;
	}

	/** The commit the mod was built at (mapmcbot-commit.txt, written by the build). */
	private static String commit() {
		try (InputStream in = RelayHub.class.getResourceAsStream("/mapmcbot-commit.txt")) {
			if (in == null) {
				throw new IllegalStateException("missing /mapmcbot-commit.txt in the jar: the build writes it");
			}

			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buffer = new byte[64];
			int read;

			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}

			return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			throw new UncheckedIOException("could not read /mapmcbot-commit.txt in the jar", e);
		}
	}

	/** The relays knocked on and not connected yet, host:port -> what they are at; for the overlay. */
	public Map<String, String> pending() {
		return pending;
	}

	/**
	 * "localhost" is the relay's own machine there: a server of this one is at the address the relay
	 * reached the mod by, known once it is connected.
	 */
	public void spawn(int rid, String bot, String host, int port) {
		final Relay relay = relay(rid);
		relay.order(() -> FleetProtocol.spawn(bot, isLoopback(host) ? relay.socket.getLocalAddress().getHostAddress() : host, port));
	}

	private static boolean isLoopback(String host) {
		return host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("::1");
	}

	public void quit(int rid, String bot) {
		relay(rid).send(FleetProtocol.quit(bot));
	}

	public void windowClick(int rid, String bot, int slot, int button, int mode, int id) {
		relay(rid).send(FleetProtocol.windowClick(bot, slot, button, mode, id));
	}

	public void tradeSelect(int rid, String bot, int trade) {
		relay(rid).send(FleetProtocol.tradeSelect(bot, trade));
	}

	public void action(int rid, String bot, String json) {
		relay(rid).send(FleetProtocol.action(bot, json));
	}

	public void gotoSpot(int rid, String bot, int x, int y, int z) {
		relay(rid).send(FleetProtocol.gotoSpot(bot, x, y, z));
	}

	/** Asks every relay connected for its profile; each answers with a PROFILE the sink hears. */
	public void requestProfile(String reason) {
		for (Relay relay : relays.values()) {
			relay.sendIfWelcomed(FleetProtocol.profileRequest(0, reason));
		}
	}

	private Relay relay(int rid) {
		final Relay relay = relays.get(rid);

		if (relay == null) {
			throw new IllegalStateException("relay " + rid + " is neither starting nor connected");
		}

		return relay;
	}

	/** A frame put together when it goes: one waiting for the WELCOME may need the connection. */
	private interface Order {
		byte[] frame();
	}

	private final class Relay {
		private final int rid;
		private final String address;
		private final Object writeLock = new Object();
		/** The orders given while it starts, in order; null once WELCOME sent them. Under writeLock. */
		private List<Order> waiting = new ArrayList<Order>();

		private volatile Socket socket;
		private InputStream in;
		private OutputStream out;

		Relay(int rid, String address) {
			this.rid = rid;
			this.address = address;
		}

		void open() throws IOException {
			final int colon = address.lastIndexOf(':');
			final Socket opened = new Socket(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
			opened.setTcpNoDelay(true);
			in = new BufferedInputStream(opened.getInputStream());
			out = new BufferedOutputStream(opened.getOutputStream());
			socket = opened;
		}

		/** Sent now when connected, else once it is. */
		void order(Order order) {
			synchronized (writeLock) {
				if (waiting != null) {
					waiting.add(order);
					return;
				}

				write(order.frame());
			}
		}

		void send(byte[] frame) {
			order(() -> frame);
		}

		/** Dropped while it starts: for what only makes sense now. */
		void sendIfWelcomed(byte[] frame) {
			synchronized (writeLock) {
				if (waiting == null) {
					write(frame);
				}
			}
		}

		/** The orders that waited go, and the next ones straight away. */
		void welcome() {
			synchronized (writeLock) {
				for (Order order : waiting) {
					write(order.frame());
				}

				waiting = null;
			}
		}

		void close() {
			if (socket == null) {
				return;
			}

			try {
				socket.close();
			} catch (IOException e) {
				throw new UncheckedIOException("could not close the connection to relay " + rid, e);
			}
		}

		/**
		 * The relay's answer to HELLO: true on WELCOME, after any UPDATING; false on UPDATE_FAILED, the
		 * relay up but not at the mod's commit. Anything else is no relay of this mod's, a failure.
		 */
		boolean awaitWelcome() throws IOException {
			byte[] frame;

			while ((frame = FleetProtocol.readFrame(in)) != null && (frame[0] & 0xFF) == FleetProtocol.UPDATING) {
				pending.put(address, "updating");
				System.out.println("[mapmcbot] relay " + rid + " (" + address + ") is updating: " + FleetProtocol.relayText(frame));
			}

			if (frame != null && (frame[0] & 0xFF) == FleetProtocol.WELCOME) {
				return true;
			}

			active.remove(address);

			if (frame != null && (frame[0] & 0xFF) == FleetProtocol.UPDATE_FAILED) {
				pending.put(address, "update failed");
				keepCrash(FleetProtocol.relayText(frame));
				socket.close();
				return false;
			}

			// The relay stays up whatever went wrong: this knock is over, the next one tries again.
			if (frame != null && (frame[0] & 0xFF) == FleetProtocol.CRASH) {
				pending.put(address, "error");
				keepCrash(FleetProtocol.crashText(frame));
				socket.close();
				return false;
			}

			pending.put(address, "crashed");

			if (frame == null) {
				throw new IllegalStateException("relay " + rid + " (" + address + ") closed its connection before its WELCOME");
			}

			throw new IllegalStateException("relay " + rid + " (" + address + ") answered HELLO with type " + (frame[0] & 0xFF) + ", not WELCOME");
		}

		void write(byte[] frame) {
			synchronized (writeLock) {
				try {
					out.write(frame);
					out.flush();
				} catch (IOException e) {
					throw new UncheckedIOException("could not write to relay " + rid, e);
				}
			}
		}

		void startReading() {
			final Thread reader = new Thread(this::read, "mapmcbot-relay-reader-" + rid);
			reader.setDaemon(true);
			reader.start();
		}

		/** The relay's errors, next to relays.txt and one after the other, for whoever debugs them: the relay's own terminal is on another machine. */
		private void keepCrash(String text) {
			final File crash = new File(file.getAbsoluteFile().getParentFile(), "crash-relay" + rid + ".log");

			try {
				Files.write(crash.toPath(), (java.time.LocalDateTime.now() + " relay " + rid + " (" + address + "):\n" + text + "\n").getBytes(StandardCharsets.UTF_8),
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				throw new UncheckedIOException("could not write " + crash, e);
			}

			System.out.println("[mapmcbot] relay " + rid + " had an error, it is in " + crash);
		}

		private void read() {
			final ProfileListener listener = new ProfileListener() {
				@Override
				public void onProfile(int id, String reason, String text) {
					profiles.onRelayProfile(rid, reason, text);
				}

				@Override
				public void onFrame() {
				}
			};

			IOException failure = null;

			try {
				byte[] frame;

				while ((frame = FleetProtocol.readFrame(in)) != null) {
					if ((frame[0] & 0xFF) == FleetProtocol.CRASH) {
						keepCrash(FleetProtocol.crashText(frame));
						continue;
					}

					if (FleetProtocol.isChunkFrame(frame[0] & 0xFF)) {
						throw new IllegalStateException("relay " + rid + " sent a frame about columns (type " + (frame[0] & 0xFF) + "): a relay's world is its own");
					}

					FleetProtocol.dispatch(frame, null, bots, listener);
				}
			} catch (IOException e) {
				failure = e;
			} finally {
				// However it ended, its bots are gone with it.
				synchronized (bots) {
					relays.remove(rid);
					bots.onRelayClosed(rid);
				}

				active.remove(address);
				profiles.onRelayGone(rid);
			}

			if (failure != null) {
				throw new UncheckedIOException("lost the connection to relay " + rid, failure);
			}

			throw new IllegalStateException("relay " + rid + " closed its connection");
		}
	}
}
