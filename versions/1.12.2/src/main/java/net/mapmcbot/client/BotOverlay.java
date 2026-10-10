package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.mapmcbot.area.ChestStore;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.profile.ProfileRegistry;
import net.mapmcbot.profile.ProfileReport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.Window;

/** The bots and their status, top right of the HUD, and a card per relay with a preview of its profile, top left. */
public final class BotOverlay {
	private static final int MARGIN = 4;
	private static final int CARD_WIDTH = 150;
	private static final int CARD_PADDING = 3;
	private static final int CARD_BACKGROUND = 0xA0101216;
	private static final int DIM = 0xFF8A919C;

	/** Where a fleet's own rows are in its report: the mod's own fleet (rid 0) and each relay (rid 1, 2, ...) are profiled the same way. */
	private static final String MAIN_SCOPE = "node";

	private BotOverlay() {
	}

	public static void render() {
		final BotRegistry bots = MapMcBotClient.botsOrNull();

		if (bots == null || bots.getBots().isEmpty()) {
			return;
		}

		final MinecraftClient client = MinecraftClient.getInstance();
		final Window window = new Window(client);
		final List<String> names = new ArrayList<String>(bots.getBots());
		Collections.sort(names);

		int y = MARGIN;

		for (String name : names) {
			final String line = client.textRenderer.trimToWidth(name + " " + bots.getStatus(name), window.getWidth() / 2);
			client.textRenderer.drawWithShadow(line, window.getWidth() - MARGIN - client.textRenderer.getStringWidth(line), y, 0xFFFFFF);
			y += client.textRenderer.fontHeight + 2;
		}

		final ProfileRegistry profile = MapMcBotClient.profile();
		final ProfileReport main = profile.getLatest();
		int cardY = MARGIN;

		if (main != null) {
			cardY += card(client, MARGIN, cardY, 0, MAIN_SCOPE, main) + MARGIN;
		}

		for (int rid : MapMcBotClient.relays().rids()) {
			final ProfileReport report = profile.getRelayLatest(rid);

			if (report != null) {
				cardY += card(client, MARGIN, cardY, rid, MAIN_SCOPE, report) + MARGIN;
			}
		}

		for (Map.Entry<String, String> relay : MapMcBotClient.relays().pending().entrySet()) {
			cardY += pendingCard(client, MARGIN, cardY, relay.getKey(), relay.getValue()) + MARGIN;
		}
	}

	/** A relay not connected yet: its address and what it is at (connecting, updating, update failed, crashed). */
	private static int pendingCard(MinecraftClient client, int x, int y, String address, String state) {
		final int line = client.textRenderer.fontHeight + 2;
		final int height = CARD_PADDING * 2 + 2 * line - 2;
		DrawableHelper.fill(x, y, x + CARD_WIDTH, y + height, CARD_BACKGROUND);
		client.textRenderer.drawWithShadow(address, x + CARD_PADDING, y + CARD_PADDING, 0xFFFFFF);
		client.textRenderer.drawWithShadow(state, x + CARD_PADDING, y + CARD_PADDING + line, DIM);
		return height;
	}

	/** One relay: its bots, CPU as % of the whole server, the server's RAM in use over its total, and under them the relay's own. */
	private static int card(MinecraftClient client, int x, int y, int rid, String scope, ProfileReport report) {
		final int line = client.textRenderer.fontHeight + 2;
		final double cores = report.value(scope, "cpu.count");
		// ms rates are % of one core: over the server's cores, a share of all of it.
		final double cpu = (report.rate(scope, "cpu.user") + report.rate(scope, "cpu.system")) / cores;
		final double machine = 100 * report.rate(scope, "cpu.system.busy") / report.rate(scope, "cpu.system.all");
		final List<String> lines = new ArrayList<String>();
		lines.add("rid=" + rid + (rid == 0 ? " (main)" : "") + "  bots " + MapMcBotClient.bots().countOn(rid));
		lines.add("CPU " + (Double.isNaN(cpu) ? "--" : String.format(Locale.ROOT, "%.1f%%", cpu))
			+ " / " + (Double.isNaN(machine) ? "--" : String.format(Locale.ROOT, "%.1f%%", machine)) + " total");
		lines.add("RAM " + bytes(report.value(scope, "mem.system.used")) + " / " + bytes(report.value(scope, "mem.system.total")));
		lines.add("columns " + bytes(report.value(scope, "mem.columns")));

		if (rid == 0) {
			final ChestStore chests = MapMcBotClient.chests();
			lines.add("chests " + (chests == null ? "--" : bytes(chests.memoryBytes())));
		}

		lines.add("relay " + bytes(report.value(scope, "mem.rss")));

		final int height = CARD_PADDING * 2 + lines.size() * line - 2;
		DrawableHelper.fill(x, y, x + CARD_WIDTH, y + height, CARD_BACKGROUND);

		for (int i = 0; i < lines.size(); i++) {
			client.textRenderer.drawWithShadow(lines.get(i), x + CARD_PADDING, y + CARD_PADDING + i * line, i == 0 ? 0xFFFFFF : i == lines.size() - 1 ? DIM : 0xE6E8EB);
		}

		return height;
	}

	private static String bytes(double value) {
		if (value < 1L << 20) {
			return String.format(Locale.ROOT, "%.0f KB", value / (1L << 10));
		}

		return value >= 1L << 30 ? String.format(Locale.ROOT, "%.1f GB", value / (1L << 30)) : String.format(Locale.ROOT, "%.0f MB", value / (1L << 20));
	}
}
