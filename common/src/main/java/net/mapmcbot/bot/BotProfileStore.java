package net.mapmcbot.bot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bot profiles, kept in a tab-separated file written the moment one changes. A line is either
 * "P name linkedAccount skinName defaultCrackedPassword lastServer" or "J name server joinedAt
 * password"; an empty linked account or last server is null. Fields are escaped like AreaStore's.
 */
public final class BotProfileStore {
	private final File file;
	private final Map<String, BotProfile> profiles = new LinkedHashMap<String, BotProfile>();

	public BotProfileStore(File file) {
		this.file = file;
		load();
	}

	/** The profiles in the order they were created. */
	public synchronized List<BotProfile> getProfiles() {
		return Collections.unmodifiableList(new ArrayList<BotProfile>(profiles.values()));
	}

	public synchronized BotProfile get(String name) {
		final BotProfile profile = profiles.get(name);

		if (profile == null) {
			throw new IllegalStateException("no bot profile named " + name);
		}

		return profile;
	}

	public synchronized boolean has(String name) {
		return profiles.containsKey(name);
	}

	public synchronized void add(BotProfile profile) {
		if (profiles.containsKey(profile.getName())) {
			throw new IllegalStateException("a bot profile named " + profile.getName() + " already exists");
		}

		profiles.put(profile.getName(), profile);
		save();
	}

	/** The bot joined the server (host:port) now, with the password it used there. */
	public synchronized void recordJoin(String name, String server, String password) {
		get(name).addJoin(new BotProfile.Join(server, System.currentTimeMillis(), password));
		save();
	}

	private void load() {
		if (!file.isFile()) {
			return;
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				final String[] parts = line.split("\t", -1);

				if (parts[0].equals("P") && parts.length == 6) {
					profiles.put(unescape(parts[1]), new BotProfile(unescape(parts[1]), nullIfEmpty(unescape(parts[2])), unescape(parts[3]), unescape(parts[4]), nullIfEmpty(unescape(parts[5]))));
				} else if (parts[0].equals("J") && parts.length == 5) {
					get(unescape(parts[1])).restoreJoin(new BotProfile.Join(unescape(parts[2]), Long.parseLong(parts[3]), unescape(parts[4])));
				} else {
					throw new IllegalStateException("Malformed bot profile line in " + file + ": " + line);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	private void save() {
		final File parent = file.getParentFile();

		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IllegalStateException("Could not create " + parent);
		}

		try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
			for (BotProfile profile : profiles.values()) {
				writer.write("P\t" + escape(profile.getName()) + '\t' + escape(orEmpty(profile.getLinkedAccount())) + '\t' + escape(profile.getSkinName())
						+ '\t' + escape(profile.getDefaultCrackedPassword()) + '\t' + escape(orEmpty(profile.getLastServer())) + '\n');

				for (BotProfile.Join join : profile.getJoins()) {
					writer.write("J\t" + escape(profile.getName()) + '\t' + escape(join.getServer()) + '\t' + join.getJoinedAt() + '\t' + escape(join.getPassword()) + '\n');
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String nullIfEmpty(String value) {
		return value.isEmpty() ? null : value;
	}

	private static String escape(String raw) {
		return raw.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
	}

	private static String unescape(String raw) {
		final StringBuilder out = new StringBuilder(raw.length());

		for (int i = 0; i < raw.length(); i++) {
			final char c = raw.charAt(i);

			if (c != '\\' || i + 1 >= raw.length()) {
				out.append(c);
				continue;
			}

			final char next = raw.charAt(++i);
			out.append(next == 't' ? '\t' : next == 'n' ? '\n' : next);
		}

		return out.toString();
	}
}
