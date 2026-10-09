package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mapmcbot.area.Area;
import net.mapmcbot.area.ChestStore;
import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;
import net.mapmcbot.bot.BotWindow;
import net.mapmcbot.job.JobRunner;

/**
 * A grab: the bot takes count of an item from the chests of an area, no primitive of the fleet but
 * the mod's, done with the fleet's: { action: grab, area: its id, item: a match (see ItemFilter),
 * count }. Chest by chest, as many as it takes: it walks next to one, opens it, item fills its
 * inventory from it (no more than is still wanted) and closes it, until it has them all.
 *
 * The chests known clean with the item go first, then the dirty ones, those last seen with it before
 * the rest; none left and still short, it fails. Each part is sent as the one before ends, by the
 * bot's state: nothing timed.
 *
 * An index ({ action: index, area }) is the same walk without taking anything: the bot opens each
 * dirty chest of the area (the never seen too) and closes it, the tracker keeping what it saw, until
 * every chest of the area is clean.
 */
final class Grab implements JobRunner.Resolver, BotRegistry.StateListener, BotRegistry.WindowListener, BotRegistry.ActionListener {
	private enum Phase {
		WALK, OPEN, WINDOW, FILL, CLOSE
	}

	private static final class Task {
		final Area area;
		/** Null for an index. */
		final JsonObject item;
		final int count;
		final int target;
		final int had;
		final JobRunner.Listener listener;
		final Set<ChestStore.Chest> visited = new HashSet<ChestStore.Chest>();
		ChestStore.Chest chest;
		Phase phase;
		/** The window as last seen while one was open, its inventory part what the bot has. */
		BotWindow window;

		Task(Area area, JsonObject item, int count, int had, JobRunner.Listener listener) {
			this.area = area;
			this.item = item;
			this.count = count;
			this.had = had;
			this.target = had + count;
			this.listener = listener;
		}
	}

	private final BotRegistry bots;
	private final ChestTracker chests;
	private final Map<String, Task> tasks = new ConcurrentHashMap<String, Task>();
	/** Why each bot's last grab failed, until its next. */
	private final Map<String, String> errors = new ConcurrentHashMap<String, String>();

	Grab(BotRegistry bots, ChestTracker chests) {
		this.bots = bots;
		this.chests = chests;
		bots.addStateListener(this);
		bots.addWindowListener(this);
		bots.addActionListener(this);
	}

	/** The chests of the area the fleets see, into the store (see ChestTracker#scan). */
	void scanChests(Area area) {
		chests.scan(area);
	}

	/** A chest to show while a bot grabs: the one it is going to (current), or one it may look in after. */
	static final class Highlight {
		final String bot;
		final ChestStore.Chest chest;
		final boolean current;

		Highlight(String bot, ChestStore.Chest chest, boolean current) {
			this.bot = bot;
			this.chest = chest;
			this.current = current;
		}
	}

	/** The chests of every grab going on: each one's current, and the ones still to look in. */
	List<Highlight> highlights() {
		final List<Highlight> out = new ArrayList<Highlight>();

		for (Map.Entry<String, Task> entry : tasks.entrySet()) {
			final Task task = entry.getValue();

			if (task.chest != null) {
				out.add(new Highlight(entry.getKey(), task.chest, true));
			}

			for (ChestStore.Chest chest : order(task)) {
				out.add(new Highlight(entry.getKey(), chest, false));
			}
		}

		return out;
	}

	@Override
	public boolean handles(String json) {
		final String action = new JsonParser().parse(json).getAsJsonObject().get("action").getAsString();
		return action.equals("grab") || action.equals("index");
	}

	/** What the bot's grab is doing, or why it failed; null with none. */
	String status(String bot) {
		final Task task = tasks.get(bot);

		if (task != null && task.item == null) {
			return "index " + task.area.getName() + ": " + order(task).size() + " left, " + task.phase.name().toLowerCase() + (task.chest == null ? "" : " " + task.chest);
		}

		if (task != null) {
			return "grab " + ItemSpec.describe(task.item) + " " + (task.window == null ? 0 : held(task) - task.had) + "/" + task.count + ": " + task.phase.name().toLowerCase() + (task.chest == null ? "" : " " + task.chest);
		}

		return errors.containsKey(bot) ? "error " + errors.get(bot) : null;
	}

	@Override
	public void start(String bot, String json, JobRunner.Listener listener) {
		final JsonObject a = new JsonParser().parse(json).getAsJsonObject();
		final String areaId = a.get("area").getAsString();
		Area area = null;

		for (Area one : MapMcBotClient.areas().getAreas()) {
			if (one.getId().equals(areaId)) {
				area = one;
			}
		}

		errors.remove(bot);

		if (area == null) {
			fail(bot, null, listener, a.get("action").getAsString() + ": no area " + areaId);
			return;
		}

		if (a.get("action").getAsString().equals("index")) {
			if (bots.getWindow(bot) != null) {
				fail(bot, null, listener, "index: expected no window open, found " + bots.getWindow(bot).getType());
				return;
			}

			final Task task = new Task(area, null, 0, 0, listener);

			if (tasks.putIfAbsent(bot, task) != null) {
				throw new IllegalStateException(bot + " is already grabbing or indexing");
			}

			chests.scan(area);
			nextChest(bot, task);
			return;
		}

		final int count = a.get("count").getAsInt();

		if (count <= 0) {
			throw new IllegalArgumentException("grab needs a positive count, got " + count);
		}

		final BotInventory inventory = bots.getInventory(bot);

		if (inventory == null) {
			fail(bot, null, listener, "grab: no inventory of " + bot + " yet");
			return;
		}

		if (bots.getWindow(bot) != null) {
			fail(bot, null, listener, "grab: expected no window open, found " + bots.getWindow(bot).getType());
			return;
		}

		// The main inventory and the hotbar, as a window's inventory part has them.
		int had = 0;

		for (BotInventory.Item item : inventory.getSlots().subList(9, 45)) {
			if (ItemSpec.matches(a.getAsJsonObject("item"), item)) {
				had += item.getCount();
			}
		}

		final Task task = new Task(area, a.getAsJsonObject("item"), count, had, listener);

		if (tasks.putIfAbsent(bot, task) != null) {
			throw new IllegalStateException(bot + " is already grabbing");
		}

		chests.scan(area);
		nextChest(bot, task);
	}

	/** The next chest to take from, walked to; none left, the grab fails. */
	private void nextChest(String bot, Task task) {
		final List<ChestStore.Chest> order = order(task);

		// Indexed: every chest of the area seen.
		if (order.isEmpty() && task.item == null) {
			tasks.remove(bot);
			task.listener.done();
			return;
		}

		if (order.isEmpty()) {
			final int got = task.window == null ? 0 : held(task) - task.had;
			fail(bot, task, task.listener, "grab: expected " + task.count + " x " + ItemSpec.describe(task.item) + " in area " + task.area.getName() + ", found " + got + " in its chests");
			return;
		}

		task.chest = order.get(0);
		task.visited.add(task.chest);
		task.phase = Phase.WALK;
		walk(bot, task);
	}

	/** The chests still to look in, in the order they would be: clean with the item, dirty with it, the other dirty ones. */
	private static List<ChestStore.Chest> order(Task task) {
		final List<ChestStore.Chest> withIt = new ArrayList<ChestStore.Chest>();
		final List<ChestStore.Chest> dirtyWithIt = new ArrayList<ChestStore.Chest>();
		final List<ChestStore.Chest> dirty = new ArrayList<ChestStore.Chest>();

		for (ChestStore.Chest chest : MapMcBotClient.chests().in(task.area)) {
			if (task.visited.contains(chest)) {
				continue;
			}

			if (task.item == null) {
				if (chest.isDirty()) {
					dirty.add(chest);
				}

				continue;
			}

			final boolean has = has(chest, task.item);

			if (!chest.isDirty()) {
				if (has) {
					withIt.add(chest);
				}
			} else if (has) {
				dirtyWithIt.add(chest);
			} else {
				dirty.add(chest);
			}
		}

		final List<ChestStore.Chest> order = new ArrayList<ChestStore.Chest>(withIt);
		order.addAll(dirtyWithIt);
		order.addAll(dirty);
		return order;
	}

	/** To the chest, standing where it can open it. */
	private void walk(String bot, Task task) {
		// Standing where a face of it is in reach and in sight, as the interact wants it.
		final int[] block = task.chest.getBlocks().get(0);
		final JsonObject look = new JsonObject();
		look.addProperty("x", block[0]);
		look.addProperty("y", block[1]);
		look.addProperty("z", block[2]);
		final JsonObject a = new JsonObject();
		a.addProperty("action", "goto");
		a.add("look", look);
		bots.actionInternal(bot, a.toString());
	}

	@Override
	public void onState(String bot, BotStatus status) {
		final Task task = tasks.get(bot);

		if (task == null || status.getKind() == BotStatus.Kind.DOING) {
			return;
		}

		if (status.getKind() == BotStatus.Kind.ERROR) {
			fail(bot, task, task.listener, (task.item == null ? "index: " : "grab: ") + status.getPrimitive() + ": " + status.getMessage());
			return;
		}

		switch (task.phase) {
			case WALK: {
				task.phase = Phase.OPEN;
				final int[] block = task.chest.getBlocks().get(0);
				final JsonObject target = new JsonObject();
				target.addProperty("kind", "block");
				target.addProperty("x", block[0]);
				target.addProperty("y", block[1]);
				target.addProperty("z", block[2]);
				final JsonObject a = new JsonObject();
				a.addProperty("action", "interact");
				a.addProperty("button", "right");
				a.add("target", target);
				bots.actionInternal(bot, a.toString());
				return;
			}

			case OPEN: {
				// Its window may come after the interact is over.
				task.phase = Phase.WINDOW;
				final BotWindow window = bots.getWindow(bot);

				if (window != null) {
					fill(bot, task, window);
				}

				return;
			}

			case FILL: {
				task.phase = Phase.CLOSE;
				final JsonObject a = new JsonObject();
				a.addProperty("action", "close_window");
				bots.actionInternal(bot, a.toString());
				return;
			}

			case CLOSE:
				if (task.item != null && held(task) >= task.target) {
					tasks.remove(bot);
					task.listener.done();
				} else {
					nextChest(bot, task);
				}

				return;

			default:
				throw new IllegalStateException(bot + " grabbing went idle while " + task.phase);
		}
	}

	@Override
	public void onWindow(String bot, BotWindow window) {
		final Task task = tasks.get(bot);

		if (task == null || window == null) {
			return;
		}

		task.window = window;

		if (task.phase == Phase.WINDOW) {
			fill(bot, task, window);
		}
	}

	@Override
	public void onAction(String bot, String json, boolean internal) {
		if (internal) {
			return;
		}

		errors.remove(bot);
		final Task task = tasks.remove(bot);

		// An order from outside takes the bot over: the grab is over.
		if (task != null) {
			task.listener.failed();
		}
	}

	/** The open chest's items it has, into the inventory, no more than still wanted; with none, closed. */
	private void fill(String bot, Task task, BotWindow window) {
		// An index only looks: the tracker kept what is in it.
		if (task.item == null) {
			task.phase = Phase.FILL;
			onState(bot, BotStatus.IDLE);
			return;
		}

		final int own = window.getContainerSlots();
		final List<BotInventory.Item> slots = window.getSlots();
		final JsonArray sources = new JsonArray();

		for (int i = 0; i < own; i++) {
			if (ItemSpec.matches(task.item, slots.get(i))) {
				sources.add(i);
			}
		}

		// The stacks of it it has first, then the empty slots.
		final JsonArray targets = new JsonArray();

		for (int i = own; i < slots.size(); i++) {
			if (ItemSpec.matches(task.item, slots.get(i))) {
				targets.add(i);
			}
		}

		for (int i = own; i < slots.size(); i++) {
			if (slots.get(i) == null) {
				targets.add(i);
			}
		}

		final int wanted = task.target - held(task);

		if (sources.size() == 0 || wanted <= 0) {
			task.phase = Phase.FILL;
			onState(bot, BotStatus.IDLE);
			return;
		}

		if (targets.size() == 0) {
			fail(bot, task, task.listener, "grab: expected room in the inventory for " + ItemSpec.describe(task.item) + ", found none");
			return;
		}

		task.phase = Phase.FILL;
		final JsonObject a = new JsonObject();
		a.addProperty("action", "item_fill");
		a.add("item", task.item);
		a.add("sources", sources);
		a.add("targets", targets);
		a.addProperty("count", wanted);
		bots.actionInternal(bot, a.toString());
	}

	/** How many of the item the bot has, by the window last seen. */
	private static int held(Task task) {
		final List<BotInventory.Item> slots = task.window.getSlots();
		int held = 0;

		for (int i = task.window.getContainerSlots(); i < slots.size(); i++) {
			if (ItemSpec.matches(task.item, slots.get(i))) {
				held += slots.get(i).getCount();
			}
		}

		return held;
	}

	private static boolean has(ChestStore.Chest chest, JsonObject item) {
		if (chest.getContents() == null) {
			return false;
		}

		for (BotInventory.Item there : chest.getContents()) {
			if (ItemSpec.matches(item, there)) {
				return true;
			}
		}

		return false;
	}

	private void fail(String bot, Task task, JobRunner.Listener listener, String message) {
		if (task != null) {
			tasks.remove(bot, task);
		}

		errors.put(bot, message);
		listener.failed();
	}
}
