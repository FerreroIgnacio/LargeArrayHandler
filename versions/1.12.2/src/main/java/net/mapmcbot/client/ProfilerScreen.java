package net.mapmcbot.client;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import net.mapmcbot.profile.ProfileRegistry;
import net.mapmcbot.profile.ProfileReport;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * CPU and memory of the mod (Java) and of the fleet (node), whole, per thread and per bot: the
 * newest profile of the {@link ProfileRegistry}. Opened with Z (Z or escape close it).
 *
 * A profile is taken as the screen opens and on Refresh (R), never on a timer; the fleet takes its
 * own on its events (a bot in or out, a new formation), and the screen shows each as it comes. CPU is
 * what was spent between the newest profile and the one before, as % of one core; memory is the
 * reading taken with the newest. Every profile also goes to the log (bot/profile.log).
 */
public class ProfilerScreen extends Screen {
	private static final String[] TABS = {"Fleet", "Bots", "Threads"};
	private static final int BUTTON_REFRESH = 10;
	private static final int BUTTON_DONE = 11;

	private static final int ROW = 11;
	private static final int TOP = 48;
	private static final int CHART_HEIGHT = 50;

	private static final int PANEL = 0xC0101216;
	private static final int HEADER = 0xFF232830;
	private static final int STRIPE = 0x18FFFFFF;
	private static final int TEXT = 0xFFE6E8EB;
	private static final int DIM = 0xFF8A919C;
	private static final int JAVA = 0xFFE07B39;
	private static final int NODE = 0xFF4CAF50;
	private static final int WARN = 0xFFFFB300;
	private static final int BAD = 0xFFE53935;

	/** Kept between openings. */
	private static int tab;

	private final ProfileRegistry profile;
	private int scroll;

	public ProfilerScreen() {
		profile = MapMcBotClient.profile();
		profile.request("profiler opened");
	}

	@Override
	public void init() {
		buttons.clear();
		final int width = 60;

		for (int i = 0; i < TABS.length; i++) {
			addButton(new ButtonWidget(i, 6 + i * (width + 4), 24, width, 20, TABS[i]));
		}

		addButton(new ButtonWidget(BUTTON_REFRESH, this.width - 6 - 2 * width - 4, 24, width, 20, "Refresh"));
		addButton(new ButtonWidget(BUTTON_DONE, this.width - 6 - width, 24, width, 20, "Done"));
		selectTab(tab);
	}

	@Override
	public boolean shouldPauseGame() {
		return false;
	}

	private void selectTab(int index) {
		tab = index;
		scroll = 0;

		for (int i = 0; i < buttons.size(); i++) {
			final ButtonWidget button = buttons.get(i);

			if (button.id < TABS.length) {
				button.active = button.id != tab;
			}
		}
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id < TABS.length) {
			selectTab(button.id);
		} else if (button.id == BUTTON_REFRESH) {
			profile.request("profiler refresh");
		} else if (button.id == BUTTON_DONE) {
			client.setScreen(null);
		}
	}

	@Override
	protected void keyPressed(char character, int keyCode) {
		if (keyCode == Keyboard.KEY_Z) {
			client.setScreen(null);
			return;
		}

		if (keyCode == Keyboard.KEY_R && !profile.isWaiting()) {
			profile.request("profiler refresh");
			return;
		}

		if (keyCode == Keyboard.KEY_TAB) {
			selectTab((tab + 1) % TABS.length);
			return;
		}

		super.keyPressed(character, keyCode);
	}

	@Override
	public void handleMouse() {
		super.handleMouse();
		final int wheel = Mouse.getDWheel();

		if (wheel != 0) {
			// Clamped by the next frame's draw.
			scroll += wheel > 0 ? -3 : 3;
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		renderBackground();
		fill(0, 0, width, height, PANEL);

		for (int i = 0; i < buttons.size(); i++) {
			if (buttons.get(i).id == BUTTON_REFRESH) {
				buttons.get(i).active = !profile.isWaiting();
			}
		}

		final ProfileReport report = profile.getLatest();
		textRenderer.draw("Profiler", 6, 6, TEXT);

		if (report == null) {
			textRenderer.draw("Waiting for the first profile...", 60, 6, DIM);
		} else {
			final String when = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(report.getTime()));
			final String over = report.hasRates() ? String.format(Locale.ROOT, ", CPU over the last %.1f s", report.getInterval() / 1000.0)
					: ", first profile: CPU as average since start";
			textRenderer.draw(when + "  " + report.getReason() + over + (profile.isWaiting() ? "  (asking...)" : ""), 60, 6, DIM);
		}

		textRenderer.draw("Log: " + profile.getLogFile().getPath(), 6, height - 10, DIM);

		if (report != null) {
			if (tab == 0) {
				drawFleet(report);
			} else if (tab == 1) {
				drawTable(botTable(report));
			} else {
				drawTable(threadTable(report));
			}
		}

		super.render(mouseX, mouseY, tickDelta);
	}

	// Fleet tab: each process whole, then their CPU over the profiles kept.

	private void drawFleet(ProfileReport r) {
		final int half = width / 2;
		int y = TOP;
		final List<String[]> java = new ArrayList<String[]>();
		java.add(row("CPU", cpu(r, "java", "cpu")));
		java.add(row("  bots' frames", pct(sumRate(r, "java.bot:", "cpu.frames") + r.rate("java.channel", "cpu.frames"))));
		java.add(row("Heap", bytes(r.value("java", "mem.heapUsed")) + " / " + bytes(r.value("java", "mem.heapCommitted")) + " (max " + bytes(r.value("java", "mem.heapMax")) + ")"));
		java.add(row("Non-heap", bytes(r.value("java", "mem.nonHeapUsed"))));
		java.add(row("Allocations", perSecond(sumRate(r, "java.thread:", "alloc"), true)));
		java.add(row("GC", pct(r.rate("java", "gc.time")) + " of the time, " + perSecond(r.rate("java", "gc.count"), false)));
		java.add(row("Threads", (int) r.value("java", "threads") + " (" + (int) r.value("java", "cores") + " cores)"));
		java.add(row("RAM free", bytes(r.value("java", "mem.physicalFree")) + " of " + bytes(r.value("java", "mem.physicalTotal"))));

		final List<String[]> node = new ArrayList<String[]>();

		if (r.row("node", "mem.rss") == null) {
			node.add(row("No fleet running", "start a bot"));
		} else {
			node.add(row("CPU", nodeCpu(r)));
			node.add(row("  bots' handlers", pct(sumRate(r, "node.bot:", "cpu.handlers"))));
			node.add(row("  path searches", pct(sumRate(r, "node.bot:", "cpu.path")) + ", " + perSecond(sumRate(r, "node.bot:", "path.searches"), false)));
			node.add(row("RSS", bytes(r.value("node", "mem.rss"))));
			node.add(row("Heap", bytes(sumValue(r, "node.thread:", "mem.heapUsed")) + " / " + bytes(sumValue(r, "node.thread:", "mem.heapTotal"))));
			node.add(row("Off heap", bytes(sumValue(r, "node.thread:", "mem.external")) + " (buffers " + bytes(sumValue(r, "node.thread:", "mem.arrayBuffers")) + ")"));
			node.add(row("GC", pct(sumRate(r, "node.thread:", "gc.time")) + " of a core, " + perSecond(sumRate(r, "node.thread:", "gc.count"), false)));
			node.add(row("Threads", (int) r.value("node", "threads") + ", bots " + (int) r.value("node", "bots")));
		}

		y = drawPairs("Mod (Java)", JAVA, java, 6, half - 6, y);
		final int nodeEnd = drawPairs("Fleet (node)", NODE, node, half + 6, width - 6, TOP);
		drawChart(Math.max(y, nodeEnd) + 8);
	}

	private static String[] row(String label, String value) {
		return new String[] {label, value};
	}

	private int drawPairs(String title, int color, List<String[]> pairs, int left, int right, int top) {
		fill(left, top, right, top + ROW + 1, HEADER);
		textRenderer.draw(title, left + 3, top + 2, color);
		int y = top + ROW + 3;

		for (String[] pair : pairs) {
			textRenderer.draw(pair[0], left + 3, y, DIM);
			textRenderer.draw(pair[1], left + 95, y, TEXT);
			y += ROW;
		}

		return y;
	}

	/** CPU of both processes for each profile kept that has rates, as bars, newest on the right. */
	private void drawChart(int top) {
		if (top + CHART_HEIGHT + 14 > height - 14) {
			return;
		}

		final List<ProfileReport> history = profile.getHistory();
		final int left = 6;
		final int right = width - 6;
		final int bottom = top + CHART_HEIGHT;
		fill(left, top, right, bottom, HEADER);
		textRenderer.draw("CPU per profile (% of one core):", left, bottom + 3, DIM);
		textRenderer.draw("java", left + 175, bottom + 3, JAVA);
		textRenderer.draw("node", left + 200, bottom + 3, NODE);

		final List<double[]> points = new ArrayList<double[]>();
		double max = 100;

		for (ProfileReport report : history) {
			if (!report.hasRates()) {
				continue;
			}

			final double java = report.rate("java", "cpu");
			final double node = report.row("node", "cpu.user") == null ? 0 : report.rate("node", "cpu.user") + report.rate("node", "cpu.system");
			points.add(new double[] {java, node});
			max = Math.max(max, Math.max(java, node));
		}

		textRenderer.draw(String.format(Locale.ROOT, "%.0f%%", max), right - 30, top + 2, DIM);

		if (points.isEmpty()) {
			textRenderer.draw("Needs two profiles", left + 4, top + 4, DIM);
			return;
		}

		final int slot = Math.max(4, Math.min(20, (right - left - 4) / points.size()));
		final int first = Math.max(0, points.size() - (right - left - 4) / slot);
		int x = left + 2;

		for (int i = first; i < points.size(); i++) {
			final int half = (slot - 2) / 2;
			final int java = (int) Math.round(points.get(i)[0] / max * (CHART_HEIGHT - 2));
			final int node = (int) Math.round(points.get(i)[1] / max * (CHART_HEIGHT - 2));
			fill(x, bottom - 1 - java, x + half, bottom - 1, JAVA);
			fill(x + half, bottom - 1 - node, x + 2 * half, bottom - 1, NODE);
			x += slot;
		}
	}

	// Bots and Threads tabs: tables, scrolled with the wheel.

	/** A table: column titles and weights, rows of cells; a row with a null first cell is a section title (its second cell). */
	private static final class Table {
		final String[] titles;
		final int[] weights;
		final List<String[]> rows = new ArrayList<String[]>();

		Table(String[] titles, int[] weights) {
			this.titles = titles;
			this.weights = weights;
		}
	}

	private Table botTable(ProfileReport r) {
		final Table table = new Table(new String[] {"Bot", "Thread", "Node CPU", "Paths", "Java CPU", "Heap ~", "Java alloc", "Net in", "Columns", "Entities", "Chunks"},
				new int[] {16, 7, 9, 9, 9, 9, 10, 10, 8, 8, 8});
		final TreeSet<String> names = new TreeSet<String>();

		for (String scope : r.scopes("node.bot:")) {
			names.add(scope.substring("node.bot:".length()));
		}

		for (String scope : r.scopes("java.bot:")) {
			names.add(scope.substring("java.bot:".length()));
		}

		final List<String> sorted = new ArrayList<String>(names);
		Collections.sort(sorted, new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				return Double.compare(botCpu(r, b), botCpu(r, a));
			}
		});

		table.rows.add(new String[] {"All (" + sorted.size() + ")", "", pct(sumRate(r, "node.bot:", "cpu.handlers")), pct(sumRate(r, "node.bot:", "cpu.path")),
				pct(sumRate(r, "java.bot:", "cpu.frames")), bytes(sumValue(r, "node.bot:", "mem.heapShare")), perSecond(sumRate(r, "java.bot:", "alloc"), true),
				perSecond(sumRate(r, "node.bot:", "net.in"), true), count(sumValue(r, "node.bot:", "columns")), count(sumValue(r, "node.bot:", "entities")),
				count(sumValue(r, "java.bot:", "chunks"))});

		for (String bot : sorted) {
			final String node = "node.bot:" + bot;
			final String java = "java.bot:" + bot;
			table.rows.add(new String[] {bot, r.row(node, "thread") == null ? "-" : "pool " + (int) r.value(node, "thread"), pct(r.rate(node, "cpu.handlers")),
					pct(r.rate(node, "cpu.path")), pct(r.rate(java, "cpu.frames")), bytes(r.value(node, "mem.heapShare")), perSecond(r.rate(java, "alloc"), true),
					perSecond(r.rate(node, "net.in"), true), count(r.value(node, "columns")), count(r.value(node, "entities")), count(r.value(java, "chunks"))});
		}

		return table;
	}

	private static double botCpu(ProfileReport r, String bot) {
		return zero(r.rate("node.bot:" + bot, "cpu.handlers")) + zero(r.rate("node.bot:" + bot, "cpu.path")) + zero(r.rate("java.bot:" + bot, "cpu.frames"));
	}

	private Table threadTable(final ProfileReport r) {
		final Table table = new Table(new String[] {"Thread", "CPU", "GC", "Heap", "Allocated", "CPU total", "Load"},
				new int[] {30, 9, 9, 12, 12, 10, 12});

		if (r.row("node", "mem.rss") != null) {
			table.rows.add(new String[] {null, "Fleet (node)"});

			for (String scope : r.scopes("node.thread:")) {
				final String load = r.row(scope, "bots") != null ? (int) r.value(scope, "bots") + " bots"
						: r.row(scope, "queue") != null ? (int) r.value(scope, "queue") + " queued" : (int) r.value(scope, "columns") + " cols";
				// Node: the time the thread's event loop was busy (see profiler.js).
				table.rows.add(new String[] {scope.substring("node.thread:".length()), pct(r.rate(scope, "cpu")), pct(r.rate(scope, "gc.time")),
						bytes(r.value(scope, "mem.heapUsed")) + "/" + bytes(r.value(scope, "mem.heapTotal")), "-", seconds(r.value(scope, "cpu")), load});
			}
		}

		table.rows.add(new String[] {null, "Mod (Java), busiest first"});
		final List<String> threads = r.scopes("java.thread:");
		Collections.sort(threads, new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				final int byRate = Double.compare(zero(r.rate(b, "cpu")), zero(r.rate(a, "cpu")));
				return byRate != 0 ? byRate : Double.compare(r.value(b, "cpu"), r.value(a, "cpu"));
			}
		});

		for (String scope : threads) {
			table.rows.add(new String[] {scope.substring("java.thread:".length()), pct(r.rate(scope, "cpu")), "-", "-",
					perSecond(r.rate(scope, "alloc"), true), seconds(r.value(scope, "cpu")), ""});
		}

		return table;
	}

	private void drawTable(Table table) {
		final int left = 6;
		final int right = width - 6;
		final int bottom = height - 14;
		final int[] xs = new int[table.titles.length + 1];
		int total = 0;

		for (int weight : table.weights) {
			total += weight;
		}

		xs[0] = left;

		for (int i = 0, sum = 0; i < table.weights.length; i++) {
			sum += table.weights[i];
			xs[i + 1] = left + (right - left) * sum / total;
		}

		fill(left, TOP, right, TOP + ROW + 1, HEADER);

		for (int c = 0; c < table.titles.length; c++) {
			textRenderer.draw(table.titles[c], xs[c] + 2, TOP + 2, DIM);
		}

		final int visible = Math.max(1, (bottom - TOP - ROW - 2) / ROW);
		scroll = Math.max(0, Math.min(scroll, table.rows.size() - visible));
		int y = TOP + ROW + 3;

		for (int i = scroll; i < table.rows.size() && i < scroll + visible; i++) {
			final String[] cells = table.rows.get(i);

			if (cells[0] == null) {
				textRenderer.draw(cells[1], left + 2, y, cells[1].startsWith("Fleet") ? NODE : JAVA);
				y += ROW;
				continue;
			}

			if (i % 2 == 0) {
				fill(left, y - 1, right, y + ROW - 1, STRIPE);
			}

			for (int c = 0; c < cells.length; c++) {
				textRenderer.draw(trim(cells[c], xs[c + 1] - xs[c] - 4), xs[c] + 2, y, c == 0 ? TEXT : colorOf(cells[c]));
			}

			y += ROW;
		}

		if (table.rows.size() > visible) {
			textRenderer.draw((scroll + 1) + "-" + Math.min(table.rows.size(), scroll + visible) + " of " + table.rows.size() + " (wheel)", right - 110, height - 10, DIM);
		}
	}

	/** A CPU percentage past a core's half shows yellow, past a whole core red. */
	private static int colorOf(String cell) {
		if (!cell.endsWith("%")) {
			return TEXT;
		}

		final double value = Double.parseDouble(cell.substring(0, cell.length() - 1));
		return value >= 90 ? BAD : value >= 50 ? WARN : TEXT;
	}

	private String trim(String text, int maxWidth) {
		if (textRenderer.getStringWidth(text) <= maxWidth) {
			return text;
		}

		String out = text;

		while (out.length() > 1 && textRenderer.getStringWidth(out + "..") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}

		return out + "..";
	}

	// Numbers.

	/** CPU of a process: since the profile before, or on the first, its average since it started. */
	private static String cpu(ProfileReport r, String scope, String name) {
		if (r.hasRates()) {
			return pct(r.rate(scope, name));
		}

		return pct(r.value(scope, name) / (r.value(scope, "uptime.s") * 1000) * 100) + " (avg)";
	}

	private static String nodeCpu(ProfileReport r) {
		if (r.hasRates()) {
			return pct(r.rate("node", "cpu.user") + r.rate("node", "cpu.system")) + " (sys " + pct(r.rate("node", "cpu.system")) + ")";
		}

		return pct((r.value("node", "cpu.user") + r.value("node", "cpu.system")) / (r.value("node", "uptime.s") * 1000) * 100) + " (avg)";
	}

	private static double sumRate(ProfileReport r, String prefix, String name) {
		double sum = 0;
		boolean any = false;

		for (String scope : r.scopes(prefix)) {
			if (r.row(scope, name) != null) {
				sum += zero(r.rate(scope, name));
				any = true;
			}
		}

		return any && r.hasRates() ? sum : any ? Double.NaN : 0;
	}

	private static double sumValue(ProfileReport r, String prefix, String name) {
		double sum = 0;

		for (String scope : r.scopes(prefix)) {
			sum += r.value(scope, name);
		}

		return sum;
	}

	private static double zero(double value) {
		return Double.isNaN(value) ? 0 : value;
	}

	private static String pct(double value) {
		return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f%%", value);
	}

	private static String count(double value) {
		return Long.toString(Math.round(value));
	}

	private static String seconds(double ms) {
		return String.format(Locale.ROOT, "%.1fs", ms / 1000);
	}

	private static String bytes(double value) {
		if (Double.isNaN(value)) {
			return "-";
		}

		if (value >= 1024L * 1024 * 1024) {
			return String.format(Locale.ROOT, "%.2fG", value / (1024.0 * 1024 * 1024));
		}

		if (value >= 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1fM", value / (1024.0 * 1024));
		}

		return String.format(Locale.ROOT, "%.0fK", value / 1024);
	}

	private static String perSecond(double value, boolean inBytes) {
		if (Double.isNaN(value)) {
			return "-";
		}

		return (inBytes ? bytes(value) : String.format(Locale.ROOT, "%.1f", value)) + "/s";
	}
}
