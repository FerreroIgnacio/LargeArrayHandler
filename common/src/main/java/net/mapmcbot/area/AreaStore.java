package net.mapmcbot.area;

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
 * The areas of one world, kept in a tab-separated file: id, name, colour (hex) and six
 * coordinates per line. Names are escaped so a tab or newline in one cannot break the file.
 * Lines from the original mod carry a tenth field (the quarry flag); they still load, the flag is
 * dropped.
 */
public final class AreaStore {
	private final File file;
	private final List<Area> areas = new ArrayList<Area>();
	private boolean dirty;

	public AreaStore(File file) {
		this.file = file;
		load();
	}

	/** Live list; change it through add and remove so the file stays in step. */
	public List<Area> getAreas() {
		return areas;
	}

	public void add(Area area) {
		areas.add(area);
		markDirty();
	}

	public void remove(Area area) {
		if (areas.remove(area)) {
			markDirty();
		}
	}

	/** Writes the change to disk right away, so an area survives a crash. */
	public void markDirty() {
		dirty = true;
		save();
	}

	private void load() {
		if (!file.isFile()) {
			return;
		}

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				areas.add(parse(line));
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	private Area parse(String line) {
		final String[] parts = line.split("\t", -1);

		if (parts.length != 9 && parts.length != 10) {
			throw new IllegalStateException("Malformed area line in " + file + ": " + line);
		}

		return new Area(parts[0], unescape(parts[1]), Integer.parseInt(parts[2], 16),
				Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
				Integer.parseInt(parts[6]), Integer.parseInt(parts[7]), Integer.parseInt(parts[8]));
	}

	public void save() {
		if (!dirty) {
			return;
		}

		final File parent = file.getParentFile();

		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IllegalStateException("Could not create " + parent);
		}

		try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
			for (int i = 0; i < areas.size(); i++) {
				final Area area = areas.get(i);

				writer.write(area.getId() + '\t' + escape(area.getName()) + '\t' + Integer.toHexString(area.getColor())
						+ '\t' + area.getMinX() + '\t' + area.getMinY() + '\t' + area.getMinZ()
						+ '\t' + area.getMaxX() + '\t' + area.getMaxY() + '\t' + area.getMaxZ() + '\n');
			}

			dirty = false;
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
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
