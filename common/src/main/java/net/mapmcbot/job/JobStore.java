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
 * The jobs, global to every bot, in order: each a name and its steps, each a primitive as the JSON the
 * fleet takes (see FleetProtocol#ACTION). In the file a job is a line "# name" then its steps, one per
 * line; steps before any name (the file of the single job there was) are a first job of their own.
 * Written the moment anything changes. There is always at least one job.
 *
 * Thread-safe: the UI edits it, the runner reads it from the channel's thread.
 */
public final class JobStore {
	private static final String NAME = "# ";

	/** A job: its name and its steps. Locked by its store. */
	public final class Job {
		private String name;
		private final List<String> steps = new ArrayList<String>();

		private Job(String name) {
			this.name = name;
		}

		public String getName() {
			synchronized (JobStore.this) {
				return name;
			}
		}

		public void setName(String value) {
			if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
				throw new IllegalArgumentException("a job's name is one line: " + value);
			}

			synchronized (JobStore.this) {
				name = value;
				save();
			}
		}

		/** A copy of the steps. */
		public List<String> getSteps() {
			synchronized (JobStore.this) {
				return new ArrayList<String>(steps);
			}
		}

		public int size() {
			synchronized (JobStore.this) {
				return steps.size();
			}
		}

		public String get(int index) {
			synchronized (JobStore.this) {
				return steps.get(index);
			}
		}

		public void add(String json) {
			if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
				throw new IllegalArgumentException("a step is one line of JSON: " + json);
			}

			synchronized (JobStore.this) {
				steps.add(json);
				save();
			}
		}

		public void remove(int index) {
			synchronized (JobStore.this) {
				steps.remove(index);
				save();
			}
		}

		/** Puts a copy of the step at index right after it. */
		public void duplicate(int index) {
			synchronized (JobStore.this) {
				steps.add(index + 1, steps.get(index));
				save();
			}
		}

		/** Takes the step at from and puts it where to is, the others making room. */
		public void moveTo(int from, int to) {
			synchronized (JobStore.this) {
				if (from == to || from < 0 || to < 0 || from >= steps.size() || to >= steps.size()) {
					return;
				}

				steps.add(to, steps.remove(from));
				save();
			}
		}

		/** Moves the step at index by delta places; the ends stay where they are. */
		public void move(int index, int delta) {
			synchronized (JobStore.this) {
				final int to = index + delta;

				if (to < 0 || to >= steps.size()) {
					return;
				}

				steps.add(to, steps.remove(index));
				save();
			}
		}
	}

	private final File file;
	private final List<Job> jobs = new ArrayList<Job>();

	public JobStore(File file) {
		this.file = file;
		load();

		if (jobs.isEmpty()) {
			jobs.add(new Job(defaultName(1)));
		}
	}

	public synchronized int count() {
		return jobs.size();
	}

	public synchronized Job get(int index) {
		return jobs.get(index);
	}

	public synchronized int indexOf(Job job) {
		return jobs.indexOf(job);
	}

	/** A new empty job at the end, named by its place. */
	public synchronized Job create() {
		final Job job = new Job(defaultName(jobs.size() + 1));
		jobs.add(job);
		save();
		return job;
	}

	/** Removes the job; the last one left is emptied instead. */
	public synchronized void remove(Job job) {
		if (jobs.size() == 1 && jobs.get(0) == job) {
			job.steps.clear();
		} else {
			jobs.remove(job);
		}

		save();
	}

	private static String defaultName(int number) {
		return "Job " + number;
	}

	private void load() {
		if (!file.isFile()) {
			return;
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			Job job = null;
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.startsWith(NAME)) {
					job = new Job(line.substring(NAME.length()));
					jobs.add(job);
				} else {
					if (job == null) {
						job = new Job(defaultName(1));
						jobs.add(job);
					}

					job.steps.add(line);
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
			for (Job job : jobs) {
				writer.write(NAME);
				writer.write(job.name);
				writer.write('\n');

				for (String step : job.steps) {
					writer.write(step);
					writer.write('\n');
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}
}
