package net.mapmcbot.job;

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
import java.util.List;

/**
 * The one job, global to every bot: its steps in order, each a primitive as the JSON the fleet takes
 * (see FleetProtocol#ACTION), one per line of the file. Written the moment it changes.
 *
 * Thread-safe: the UI edits it, the runner reads it from the channel's thread.
 */
public final class JobStore {
	private final File file;
	private final List<String> steps = new ArrayList<String>();

	public JobStore(File file) {
		this.file = file;
		load();
	}

	/** A copy of the steps. */
	public synchronized List<String> getSteps() {
		return new ArrayList<String>(steps);
	}

	public synchronized int size() {
		return steps.size();
	}

	public synchronized String get(int index) {
		return steps.get(index);
	}

	public synchronized void add(String json) {
		if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
			throw new IllegalArgumentException("a step is one line of JSON: " + json);
		}

		steps.add(json);
		save();
	}

	public synchronized void remove(int index) {
		steps.remove(index);
		save();
	}

	/** Moves the step at index by delta places; the ends stay where they are. */
	public synchronized void move(int index, int delta) {
		final int to = index + delta;

		if (to < 0 || to >= steps.size()) {
			return;
		}

		steps.add(to, steps.remove(index));
		save();
	}

	private void load() {
		if (!file.isFile()) {
			return;
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				steps.add(line);
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
			for (String step : steps) {
				writer.write(step);
				writer.write('\n');
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}
}
