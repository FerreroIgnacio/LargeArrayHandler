package net.mapmcbot.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.mapmcbot.bot.BotProfile;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen;
import net.minecraft.entity.LivingEntity;
import org.lwjgl.input.Keyboard;

/**
 * The bot profiles (see BotProfile): a card for each with a preview of its skin (the head) and its
 * name; a click opens the profile's details, another click or Esc goes back, and Esc from the cards
 * goes back to the bot window.
 */
public class BotProfilesScreen extends Screen {
	private static final int CARD_WIDTH = 90;
	private static final int CARD_HEIGHT = 110;
	private static final int GAP = 6;
	private static final int CARD = 0xC0101216;
	private static final int CARD_HOVER = 0xE0303A48;
	private static final int TEXT = 0xFFE6E8EB;
	private static final int DIM = 0xFF8A919C;
	private static final int[] UNITS_MS = {86400000, 3600000, 60000, 1000};
	private static final String[] UNITS = {"d", "h", "min", "s"};

	private final List<BotProfile> profiles = MapMcBotClient.botProfiles().getProfiles();
	/** The profile whose details are shown, null for the cards. */
	private BotProfile open;
	private final Map<String, PreviewPlayer> previews = new HashMap<String, PreviewPlayer>();

	private int columns() {
		return Math.max(1, (width - GAP) / (CARD_WIDTH + GAP));
	}

	private int cardX(int index) {
		final int columns = columns();
		final int used = Math.min(columns, profiles.size()) * (CARD_WIDTH + GAP) - GAP;
		return (width - used) / 2 + (index % columns) * (CARD_WIDTH + GAP);
	}

	private int cardY(int index) {
		return 30 + (index / columns()) * (CARD_HEIGHT + GAP);
	}

	/** The card under the point, -1 for none. */
	private int cardAt(int mouseX, int mouseY) {
		for (int i = 0; i < profiles.size(); i++) {
			if (mouseX >= cardX(i) && mouseX < cardX(i) + CARD_WIDTH && mouseY >= cardY(i) && mouseY < cardY(i) + CARD_HEIGHT) {
				return i;
			}
		}

		return -1;
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		renderBackground();

		if (open != null) {
			drawDetails(open, mouseX, mouseY);
			super.render(mouseX, mouseY, tickDelta);
			return;
		}

		drawCenteredString(textRenderer, "Bot profiles", width / 2, 12, TEXT);
		final int hovered = cardAt(mouseX, mouseY);

		for (int i = 0; i < profiles.size(); i++) {
			final BotProfile profile = profiles.get(i);
			final int x = cardX(i);
			final int y = cardY(i);
			fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, i == hovered ? CARD_HOVER : CARD);
			drawCenteredString(textRenderer, textRenderer.trimToWidth(profile.getName(), CARD_WIDTH - 8), x + CARD_WIDTH / 2, y + 6, TEXT);
			drawBody(profile, x + CARD_WIDTH / 2, y + CARD_HEIGHT - 8, 30, mouseX, mouseY);
		}

		super.render(mouseX, mouseY, tickDelta);
	}

	private void drawDetails(BotProfile profile, int mouseX, int mouseY) {
		drawCenteredString(textRenderer, profile.getName(), width / 2, 12, TEXT);
		drawBody(profile, 60, 135, 45, mouseX, mouseY);

		final String[][] rows = {
				{"Linked account", profile.getLinkedAccount() == null ? "none (cracked)" : profile.getLinkedAccount()},
				{"Skin", profile.getSkinName()},
				{"Default cracked password", profile.getDefaultCrackedPassword()},
				{"Last server", profile.getLastServer() == null ? "never joined" : profile.getLastServer()},
		};

		for (int i = 0; i < rows.length; i++) {
			textRenderer.drawWithShadow(rows[i][0], 120, 30 + i * 14, DIM);
			textRenderer.drawWithShadow(rows[i][1], 260, 30 + i * 14, TEXT);
		}

		textRenderer.drawWithShadow("Servers joined", 20, 150, DIM);
		final List<BotProfile.Join> joins = profile.getJoins();

		if (joins.isEmpty()) {
			textRenderer.drawWithShadow("none yet", 20, 164, TEXT);
			return;
		}

		// Newest first, as many as fit.
		final long now = System.currentTimeMillis();

		for (int i = 0; i < joins.size() && 164 + i * 12 < height - 12; i++) {
			final BotProfile.Join join = joins.get(joins.size() - 1 - i);
			textRenderer.drawWithShadow(join.getServer(), 20, 164 + i * 12, TEXT);
			textRenderer.drawWithShadow(ago(now - join.getJoinedAt()), 160, 164 + i * 12, DIM);
			textRenderer.drawWithShadow("password: " + join.getPassword(), 230, 164 + i * 12, DIM);
		}
	}

	/**
	 * The profile's bot as the inventory draws the player (feet at (x, y), looking at the mouse): the
	 * bot itself while it is in the world, else a standing copy in its skin.
	 */
	private void drawBody(BotProfile profile, int x, int y, int scale, int mouseX, int mouseY) {
		LivingEntity entity = client.world.getPlayerByName(profile.getName());

		if (entity == null) {
			entity = previews.computeIfAbsent(profile.getName(), name -> new PreviewPlayer(name, profile.getSkinName()));
		}

		SurvivalInventoryScreen.renderEntity(x, y, scale, x - mouseX, y - scale * 5 / 3 - mouseY, entity);
	}

	/** How long ago, in its biggest unit. */
	private static String ago(long ms) {
		for (int i = 0; i < UNITS_MS.length; i++) {
			if (ms >= UNITS_MS[i]) {
				return "hace " + ms / UNITS_MS[i] + " " + UNITS[i];
			}
		}

		return "ahora";
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		if (open != null) {
			open = null;
			return;
		}

		final int card = cardAt(mouseX, mouseY);

		if (button == 0 && card >= 0) {
			open = profiles.get(card);
		}
	}

	@Override
	protected void keyPressed(char chr, int code) {
		if (code != Keyboard.KEY_ESCAPE) {
			return;
		}

		if (open != null) {
			open = null;
		} else {
			client.setScreen(new BotScreen());
		}
	}

	@Override
	public boolean shouldPauseGame() {
		return false;
	}
}
