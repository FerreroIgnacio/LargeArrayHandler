package net.mapmcbot.client;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mojang.blaze3d.platform.GlStateManager;
import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.slot.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.ItemAction;

/**
 * The bot window (B): a card for each bot on the left, the inventory of the one picked on the right,
 * handled as the player's own. It is the game's own container screen over a player screen handler
 * holding the bot's items, so hovering, tooltips, the item on the cursor, splitting and dragging look
 * and behave as the client's; every click it would send the server goes to the bot instead (see
 * BotRegistry#windowClick), as the same slot, button and mode.
 *
 * As the client does, each click is also done here at once on the screen handler, so the next one
 * (a drag right after picking up, a double click) is decided on what it left. What the bot's
 * inventory becomes comes back from the fleet (INVENTORY) and replaces it, once it has every click
 * sent done: an older one would undo the clicks after it until the next came.
 *
 * While it is open the player's own cursor holds the bot's, since the container screen draws and
 * decides by it; it is emptied as the screen closes, before the game would drop it.
 */
public class BotScreen extends HandledScreen {
	/**
	 * The screen handler's window id: one the server never uses (1 to 100), so none of its slot updates
	 * land here while the game takes this for the player's open window.
	 */
	private static final int SYNC_ID = -128;

	private static final int MARGIN = 6;
	private static final int CARD_WIDTH = 130;
	private static final int CARD_HEIGHT = 24;
	private static final int CARD = 0xC0101216;
	private static final int CARD_SELECTED = 0xE0303A48;
	private static final int TEXT = 0xFFE6E8EB;
	private static final int DIM = 0xFF8A919C;
	/** The colour of the frame on the hotbar slot in the bot's hand. */
	private static final int HELD = 0xFFFFFFFF;

	/** Kept between openings. */
	private static String selected;

	private final BotRegistry bots;
	/** The hotbar slot in the selected bot's hand, -1 before its first inventory. */
	private int held = -1;

	public BotScreen() {
		super(new PlayerScreenHandler(new PlayerInventory(MinecraftClient.getInstance().player), false, MinecraftClient.getInstance().player));
		screenHandler.syncId = SYNC_ID;
		bots = MapMcBotClient.bots();
	}

	@Override
	public void init() {
		super.init();
		// Right of the cards, centred in what they leave.
		final int left = MARGIN + CARD_WIDTH + MARGIN;
		x = left + Math.max(0, (width - left - backgroundWidth) / 2);

		// Each inventory as it comes, onto the client thread; only the selected bot's is shown, and only
		// once it has the clicks sent done.
		bots.setInventoryListener((bot, inventory) -> client.submit(() -> {
			if (client.currentScreen == this && bot.equals(selected) && bots.coversClicks(bot, inventory)) {
				show(inventory);
			}
		}));

		final List<String> names = names();

		if (selected == null || !names.contains(selected)) {
			selected = names.isEmpty() ? null : names.get(0);
		}

		select(selected);
	}

	@Override
	public void removed() {
		bots.setInventoryListener(null);
		// The bot's cursor is not the player's: emptied before the screen handler would drop it, and
		// the player's own window back as the one open.
		client.player.inventory.setCursorStack(ItemStack.EMPTY);
		client.player.openScreenHandler = client.player.playerScreenHandler;
		super.removed();
	}

	@Override
	public boolean shouldPauseGame() {
		return false;
	}

	/** The bots by name, as the cards list them. */
	private List<String> names() {
		final List<String> names = new ArrayList<String>(bots.getBots());
		Collections.sort(names, (a, b) -> {
			// PandaBot2 before PandaBot10.
			final int byLength = Integer.compare(a.length(), b.length());
			return byLength != 0 ? byLength : a.compareTo(b);
		});
		return names;
	}

	private void select(String bot) {
		selected = bot;
		final BotInventory inventory = bot == null ? null : bots.getInventory(bot);

		if (inventory == null) {
			clear();
		} else {
			show(inventory);
		}
	}

	/** The bot's inventory in the screen handler's slots, its cursor on the player's. */
	private void show(BotInventory inventory) {
		final List<BotInventory.Item> slots = inventory.getSlots();

		if (slots.size() != screenHandler.slots.size()) {
			throw new IllegalStateException(selected + " has " + slots.size() + " inventory slots, the player's window " + screenHandler.slots.size());
		}

		for (int i = 0; i < slots.size(); i++) {
			screenHandler.getSlot(i).setStack(stackOf(slots.get(i)));
		}

		client.player.inventory.setCursorStack(stackOf(inventory.getCursor()));
		held = inventory.getHeld();
	}

	/** No inventory of the bot yet: nothing in it. */
	private void clear() {
		for (int i = 0; i < screenHandler.slots.size(); i++) {
			screenHandler.getSlot(i).setStack(ItemStack.EMPTY);
		}

		client.player.inventory.setCursorStack(ItemStack.EMPTY);
		held = -1;
	}

	private static ItemStack stackOf(BotInventory.Item item) {
		if (item == null) {
			return ItemStack.EMPTY;
		}

		final Item type = Item.getFromId(item.getName());

		if (type == null) {
			throw new IllegalStateException("no item " + item.getName() + " in this version, in a bot's inventory");
		}

		final ItemStack stack = new ItemStack(type, item.getCount(), item.getMetadata());

		if (item.getNbt().length > 0) {
			try {
				stack.setNbt(NbtIo.read(new DataInputStream(new ByteArrayInputStream(item.getNbt()))));
			} catch (IOException e) {
				throw new UncheckedIOException("bad NBT on " + item.getName() + " in a bot's inventory", e);
			}
		}

		return stack;
	}

	/**
	 * Every click the container screen makes, the bot's to do: as the same slot, button and mode a
	 * client sends, and done here at once as the client does. Not a swap with the hotbar or a clone:
	 * the game does those on the player's own inventory and abilities, not the bot's.
	 */
	@Override
	protected void method_1131(Slot slot, int slotId, int button, ItemAction action) {
		if (selected == null) {
			throw new IllegalStateException("a click on the bot window with no bot picked");
		}

		final int id = slot != null ? slot.id : slotId;
		bots.windowClick(selected, id, button, action.ordinal());

		if (action != ItemAction.SWAP && action != ItemAction.CLONE) {
			screenHandler.method_3252(id, button, action, client.player);
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		renderBackground();
		drawCards(mouseX, mouseY);

		if (selected == null) {
			textRenderer.drawWithShadow("No bots", MARGIN + CARD_WIDTH + MARGIN * 2, MARGIN, DIM);
			return;
		}

		textRenderer.drawWithShadow(selected + (held < 0 ? "  (waiting for its inventory)" : ""), x, y - 11, TEXT);
		super.render(mouseX, mouseY, tickDelta);
		renderTooltip(mouseX, mouseY);
	}

	private void drawCards(int mouseX, int mouseY) {
		final List<String> names = names();

		for (int i = 0; i < names.size(); i++) {
			final String name = names.get(i);
			final int top = MARGIN + i * (CARD_HEIGHT + 2);
			fill(MARGIN, top, MARGIN + CARD_WIDTH, top + CARD_HEIGHT, name.equals(selected) ? CARD_SELECTED : CARD);
			fill(MARGIN, top, MARGIN + 3, top + CARD_HEIGHT, 0xFF000000 | MapMcBotClient.colorOf(name).getRgb());
			textRenderer.drawWithShadow(name, MARGIN + 7, top + 3, TEXT);
			final Integer fleet = bots.getFleet(name);
			final String where = fleet == null ? "" : fleet == 0 ? "local" : "relay " + fleet;
			textRenderer.drawWithShadow(bots.getStatus(name) + "  " + where, MARGIN + 7, top + 13, DIM);
		}
	}

	/** The card under the point, null for none. */
	private String cardAt(int mouseX, int mouseY) {
		if (mouseX < MARGIN || mouseX >= MARGIN + CARD_WIDTH || mouseY < MARGIN) {
			return null;
		}

		final int index = (mouseY - MARGIN) / (CARD_HEIGHT + 2);
		final List<String> names = names();

		if (index >= names.size() || (mouseY - MARGIN) % (CARD_HEIGHT + 2) >= CARD_HEIGHT) {
			return null;
		}

		return names.get(index);
	}

	@Override
	protected void drawBackground(float tickDelta, int mouseX, int mouseY) {
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		client.getTextureManager().bindTexture(INVENTORY_TEXTURE);
		drawTexture(x, y, 0, 0, backgroundWidth, backgroundHeight);
		// The bot itself where the player stands in the client's, when it is in sight.
		final PlayerEntity bot = client.world.getPlayerByName(selected);

		if (bot != null) {
			SurvivalInventoryScreen.renderEntity(x + 51, y + 75, 30, x + 51 - mouseX, y + 75 - 50 - mouseY, bot);
		}
	}

	@Override
	protected void drawForeground(int mouseX, int mouseY) {
		textRenderer.draw("Crafting", 97, 8, 0x404040);

		if (held < 0) {
			return;
		}

		// The hotbar slot in its hand, framed as the hotbar frames it.
		final Slot slot = screenHandler.getSlot(36 + held);
		fill(slot.x - 1, slot.y - 1, slot.x + 17, slot.y, HELD);
		fill(slot.x - 1, slot.y + 16, slot.x + 17, slot.y + 17, HELD);
		fill(slot.x - 1, slot.y, slot.x, slot.y + 16, HELD);
		fill(slot.x + 16, slot.y, slot.x + 17, slot.y + 16, HELD);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		final String card = cardAt(mouseX, mouseY);

		if (card != null) {
			if (!card.equals(selected)) {
				// The cursor of the bot left behind is its own: not carried over.
				select(card);
			}

			return;
		}

		if (selected != null) {
			super.mouseClicked(mouseX, mouseY, button);
		}
	}

	@Override
	protected void mouseDragged(int mouseX, int mouseY, int button, long held) {
		if (selected != null) {
			super.mouseDragged(mouseX, mouseY, button, held);
		}
	}

	@Override
	protected void mouseReleased(int mouseX, int mouseY, int button) {
		if (selected != null) {
			super.mouseReleased(mouseX, mouseY, button);
		}
	}

	@Override
	protected void keyPressed(char character, int keyCode) {
		// B closes it as the inventory key closes the player's.
		if (keyCode == MapMcBotClient.botsKeyCode()) {
			client.player.closeHandledScreen();
			return;
		}

		super.keyPressed(character, keyCode);
	}
}
