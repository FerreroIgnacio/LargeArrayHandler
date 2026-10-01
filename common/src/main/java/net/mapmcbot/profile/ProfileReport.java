package net.mapmcbot.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One profile of the mod and the fleet, CPU and memory: for each scope the rows it recorded. Immutable.
 *
 * Scopes: "java" and "node" (each process whole), "java.thread:name (id)" and "node.thread:fleet",
 * "node.thread:pool:N", "node.thread:path:N" (each thread), "java.bot:name" and "node.bot:name" (each
 * bot, on either side), "java.channel" (the fleet's own frames on the mod's side).
 *
 * Units (see bot/profiler.js): ms, n and bytes only go up, and their rate is worked out against the
 * report before (ms as % of one core, n and bytes per second); B (bytes), # (a count) and % are
 * readings taken with the report.
 */
public final class ProfileReport {
	public static final String MS = "ms";
	public static final String COUNT = "n";
	public static final String BYTES_SO_FAR = "bytes";
	public static final String BYTES = "B";
	public static final String NOW = "#";
	public static final String PERCENT = "%";

	private final long time;
	private final String reason;
	/** Milliseconds since the report before; 0 for the first, which has no rates. */
	private final long interval;
	private final Map<String, Map<String, Row>> scopes = new LinkedHashMap<String, Map<String, Row>>();

	/** `previous` the report before, null for the first; it is dropped once the rates are worked out. */
	ProfileReport(long time, String reason, List<Row> rows, ProfileReport previous) {
		this.time = time;
		this.reason = reason;
		this.interval = previous == null ? 0 : time - previous.time;

		for (Row row : rows) {
			Map<String, Row> named = scopes.get(row.scope);

			if (named == null) {
				named = new LinkedHashMap<String, Row>();
				scopes.put(row.scope, named);
			}

			if (named.put(row.name, row) != null) {
				throw new IllegalStateException("profile scope " + row.scope + " has " + row.name + " twice");
			}
		}

		// Not kept: every report would hold on to all those before it.
		for (Map<String, Row> named : scopes.values()) {
			for (Row row : named.values()) {
				row.rate = rateOf(row, previous);
			}
		}
	}

	/** The fleet's rows, a line each: scope, name, unit, value, tab-separated. */
	static List<Row> parse(String text) {
		final List<Row> rows = new ArrayList<Row>();

		for (String line : text.split("\n")) {
			if (line.isEmpty()) {
				continue;
			}

			final String[] parts = line.split("\t", -1);

			if (parts.length != 4) {
				throw new IllegalStateException("bad profile line, " + parts.length + " fields instead of 4: " + line);
			}

			try {
				rows.add(new Row(parts[0], parts[1], parts[2], Double.parseDouble(parts[3])));
			} catch (NumberFormatException e) {
				throw new IllegalStateException("bad number in profile line: " + line, e);
			}
		}

		return rows;
	}

	private double rateOf(Row row, ProfileReport previous) {
		final boolean time = row.unit.equals(MS);

		if (!time && !row.unit.equals(COUNT) && !row.unit.equals(BYTES_SO_FAR)) {
			return Double.NaN;
		}

		if (!hasRates()) {
			return Double.NaN;
		}

		// New since the report before (a bot, a thread): all of it happened in between.
		final Row before = previous.row(row.scope, row.name);
		final double delta = row.value - (before == null ? 0 : before.value);
		return time ? delta / interval * 100 : delta / interval * 1000;
	}

	/** Whether there was a report before to work the rates out against. */
	public boolean hasRates() {
		return interval > 0;
	}

	/** When the report was put together, in milliseconds since the epoch. */
	public long getTime() {
		return time;
	}

	public String getReason() {
		return reason;
	}

	/** Milliseconds since the report before; 0 for the first. */
	public long getInterval() {
		return interval;
	}

	/** The scopes starting with `prefix` ("" for all), in the order they came. */
	public List<String> scopes(String prefix) {
		final List<String> names = new ArrayList<String>();

		for (String scope : scopes.keySet()) {
			if (scope.startsWith(prefix)) {
				names.add(scope);
			}
		}

		return names;
	}

	/** The rows of the scope; empty when it has none. */
	public Map<String, Row> rows(String scope) {
		final Map<String, Row> rows = scopes.get(scope);
		return rows == null ? Collections.<String, Row>emptyMap() : Collections.unmodifiableMap(rows);
	}

	/** The row, null when the scope never recorded it. */
	public Row row(String scope, String name) {
		return rows(scope).get(name);
	}

	/** Its value; a row nobody recorded is zero: nothing of it happened (a bot with no search yet). */
	public double value(String scope, String name) {
		final Row row = row(scope, name);
		return row == null ? 0 : row.value;
	}

	/** Its rate since the report before (see the units); NaN for the first report or a reading. */
	public double rate(String scope, String name) {
		final Row row = row(scope, name);

		if (row == null) {
			return hasRates() ? 0 : Double.NaN;
		}

		return row.rate;
	}

	public static final class Row {
		private final String scope;
		private final String name;
		private final String unit;
		private final double value;
		private double rate = Double.NaN;

		public Row(String scope, String name, String unit, double value) {
			this.scope = scope;
			this.name = name;
			this.unit = unit;
			this.value = value;
		}

		public String getScope() {
			return scope;
		}

		public String getName() {
			return name;
		}

		public String getUnit() {
			return unit;
		}

		public double getValue() {
			return value;
		}

		/** See {@link ProfileReport#rate}. */
		public double getRate() {
			return rate;
		}
	}
}
