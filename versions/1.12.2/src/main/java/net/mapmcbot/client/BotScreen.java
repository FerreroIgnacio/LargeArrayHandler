package net.mapmcbot.client;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;
import net.mapmcbot.bot.BotWindow;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.inventory.slot.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Identifier;
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
 *
 * With a window open on the bot (a chest, a furnace) the screen is a chest's instead: the window's
 * own slots nine to a row over the bot's inventory, each the window's slot of the same number, so
 * clicks go to the bot as they come and are done here the same way.
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

	/**
	 * The primitives' buttons under the inventory: label, and the command each launches (see
	 * BotCommands); null for the ones whose slots are picked here first (see Pick). Trade, narrower,
	 * picks the trade written in the field at its right (see tradeField).
	 */
	private static final String[][] ACTIONS = {
			{"Goto", "#goto %s"},
			{"Interact L", "#interact %s left"},
			{"Interact R", "#interact %s right"},
			{"Break", "#break %s"},
			{"Place", "#place %s"},
			{"Close win", "#close %s"},
			{"Move", null},
			{"ItemFill", null},
			{"Wait slot", null},
			{"Hotbar", null},
			{"Trade", "#trade %s %d"},
			{"Stop", "#stop %s"},
	};
	/** The rows the primitives' buttons take, three to a row. */
	private static final int ACTION_ROWS = (ACTIONS.length + 2) / 3;
	private static final int BUTTON_WIDTH = 58;
	/** Trade's button, the only one sharing its place: the trade's number field takes the rest. */
	private static final int TRADE_BUTTON = 10;
	private static final int TRADE_WIDTH = 34;

	/** The colours over a picked slot: move's origin and destination, a slot waited on, the item one waits for, the hotbar slot to hold. */
	private static final int MOVE_FROM = 0x8000C000;
	private static final int MOVE_TO = 0x800060FF;
	private static final int WAITED = 0x80FF9900;
	private static final int WAIT_ITEM = 0x80B040FF;
	private static final int HOTBAR = 0x80FFFF00;
	private static final int WAIT_ROW = 20;
	/** Where in a wait's row its item, its match button and its count sit, from the panel's left. */
	private static final int ICON_X = 40;
	private static final int MATCH_X = 60;
	private static final int MATCH_WIDTH = 40;
	private static final int MIN_X = 112;
	/** The match panel right of the inventory: its width, a row's height, where a row's value starts. */
	private static final int PANEL_WIDTH = 170;
	private static final int PANEL_ROW = 12;
	private static final int PANEL_VALUE_X = 76;
	private static final int START_ID = 100;
	private static final int CANCEL_ID = 101;

	/**
	 * What a click on a slot does instead of reaching the bot: move's origin then its destination, or a
	 * slot to wait on, each listed in the panel under the inventory with what it waits for; or, from a
	 * wait's item, the slot holding the item it waits for; or the hotbar slot to take into its hand.
	 */
	private enum Pick {
		NONE, MOVE_FROM, MOVE_TO, WAIT, WAIT_ITEM, HOTBAR, FILL_ITEM, FILL_TARGETS
	}

	private Pick pick = Pick.NONE;
	private int moveFrom = -1;
	/** The items an item fill moves and the slots it fills with them. */
	private ItemFilter fillFilter;
	private final List<Integer> fillTargets = new ArrayList<Integer>();
	/** While dragging over the slots: whether they are being selected (true) or deselected (false), by the first one; null with no drag. */
	private Boolean fillDrag;
	/** The wait whose item is being picked (WAIT_ITEM). */
	private Wait choosing;
	private final List<Wait> waits = new ArrayList<Wait>();
	/** The match shown in the panel right of the inventory, a wait's or the item fill's; null for none. */
	private ItemFilter editing;
	private ButtonWidget startButton;
	private ButtonWidget cancelButton;

	/** Each bot's wait sent, its slots lit until it ends: kept between openings. */
	private static final Map<String, Sent> sent = new HashMap<String, Sent>();

	/** A wait sent: its slots, the type of the window they are on (null for the inventory), and the bot's state as it was sent. */
	private static final class Sent {
		final List<Integer> slots;
		final String window;
		final BotStatus before;

		Sent(List<Integer> slots, String window, BotStatus before) {
			this.slots = slots;
			this.window = window;
			this.before = before;
		}
	}

	/**
	 * A slot waited on: the item, how it has to match it (see ItemFilter; null with no item: any item)
	 * and how many at least.
	 */
	private static final class Wait {
		final int slot;
		BotInventory.Item item;
		ItemFilter filter;
		final TextFieldWidget min;

		Wait(int slot, BotInventory.Item item, TextFieldWidget min) {
			this.slot = slot;
			this.item = item;
			this.filter = item == null ? null : new ItemFilter(item);
			this.min = min;
		}
	}

	/** Where the inventory, or the window, has its left edge measured from: right of the cards. */
	private static final int LEFT = MARGIN + CARD_WIDTH + MARGIN;
	private static final Identifier CHEST_TEXTURE = new Identifier("textures/gui/container/generic_54.png");
	/** The chest texture's background, over the slots a last row short of nine lacks. */
	private static final int CHEST_BACKGROUND = 0xFFC6C6C6;

	/** Kept between openings. */
	private static String selected;
	private final List<ButtonWidget> actionButtons = new ArrayList<ButtonWidget>();
	/** The trade Trade picks, digits only; the villager's arrows write theirs in it. */
	private TextFieldWidget tradeField;

	/** The screen handler of the bot's inventory, the screen's while the bot has no window open. */
	private final ScreenHandler inventoryHandler;
	/** The window the screen shows, null while it shows the inventory. */
	private BotWindow shownWindow;
	/** The villager trade the window shows, as its arrows pick it; the server starts at the first. */
	private int trade;
	/** Where the inventory sits; a window, taller, is placed on its own. */
	private int baseY;

	private final BotRegistry bots;
	/** The hotbar slot in the selected bot's hand, -1 before its first inventory. */
	private int held = -1;

	public BotScreen() {
		super(new PlayerScreenHandler(new PlayerInventory(MinecraftClient.getInstance().player), false, MinecraftClient.getInstance().player));
		screenHandler.syncId = SYNC_ID;
		inventoryHandler = screenHandler;
		bots = MapMcBotClient.bots();
	}

	@Override
	public void init() {
		// Laid out as the inventory; a window open is shown again on the first frame.
		screenHandler = inventoryHandler;
		backgroundHeight = 166;
		shownWindow = null;
		super.init();
		place();

		// Each inventory as it comes, onto the client thread; only the selected bot's is shown, and only
		// once it has the clicks sent done.
		bots.setInventoryListener((bot, inventory) -> client.submit(() -> {
			if (client.currentScreen == this && bot.equals(selected) && screenHandler == inventoryHandler && bots.getWindow(bot) == null && bots.coversClicks(bot, inventory)) {
				show(inventory);
			}
		}));

		baseY = y;
		final int top = y + backgroundHeight + 6;
		actionButtons.clear();

		for (int i = 0; i < ACTIONS.length; i++) {
			actionButtons.add(new ButtonWidget(i, x + (i % 3) * (BUTTON_WIDTH + 1), top + (i / 3) * 21, i == TRADE_BUTTON ? TRADE_WIDTH : BUTTON_WIDTH, 20, ACTIONS[i][0]));
		}

		final String tradeText = tradeField == null ? "0" : tradeField.getText();
		tradeField = new TextFieldWidget(2, textRenderer, 0, 0, BUTTON_WIDTH - TRADE_WIDTH - 3, 18);
		tradeField.setMaxLength(3);
		tradeField.setText(tradeText);

		startButton = new ButtonWidget(START_ID, 0, 0, BUTTON_WIDTH, 20, "Start");
		cancelButton = new ButtonWidget(CANCEL_ID, 0, 0, BUTTON_WIDTH, 20, "Cancel");

		final List<String> names = names();

		if (selected == null || !names.contains(selected)) {
			selected = names.isEmpty() ? null : names.get(0);
		}

		select(selected);
	}

	/** The button's command for the selected bot. */
	private void launch(int index) {
		if (ACTIONS[index][1] == null) {
			final String label = ACTIONS[index][0];
			final Pick start = label.equals("Move") ? Pick.MOVE_FROM : label.equals("ItemFill") ? Pick.FILL_ITEM : label.equals("Hotbar") ? Pick.HOTBAR : Pick.WAIT;
			final boolean again = pick == start || (start == Pick.MOVE_FROM && pick == Pick.MOVE_TO) || (start == Pick.FILL_ITEM && pick == Pick.FILL_TARGETS);
			resetPick();

			// The same button again leaves the pick.
			if (!again) {
				pick = start;
			}

			return;
		}

		resetPick();
		MapMcBotClient.command(ACTIONS[index][1].replace("%s", selected).replace("%d", tradeField.getText().trim()));

		// A pick happens in the world, so the screen gets out of the way.
		if (TargetPick.isActive()) {
			client.setScreen(null);
		}
	}

	@Override
	public void removed() {
		bots.setInventoryListener(null);
		// The bot's cursor is not the player's: emptied before the screen handler would drop it, and
		// the player's own window back as the one open. Closed by a disconnect the player is already gone,
		// and nothing of its is left to drop the cursor.
		if (client.player == null) {
			return;
		}

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
		resetPick();
		selected = bot;
		final BotInventory inventory = bot == null ? null : bots.getInventory(bot);
		// Back to the inventory; the bot's window, if it has one, is shown on the next frame.
		use(inventoryHandler);
		shownWindow = null;

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

		// The bot left while the screen was open: the click has nobody to go to; another bot is picked.
		if (!bots.getBots().contains(selected)) {
			final List<String> names = names();
			select(names.isEmpty() ? null : names.get(0));
			return;
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

		refreshWindow(bots.getWindow(selected));
		place();
		textRenderer.drawWithShadow(selected + (held < 0 ? "  (waiting for its inventory)" : ""), x, y - 11, TEXT);
		super.render(mouseX, mouseY, tickDelta);
		renderTooltip(mouseX, mouseY);
		if (shownWindow != null && !shownWindow.getTrades().isEmpty()) {
			drawTrade(shownWindow, mouseX, mouseY);
		}

		final int top = y + backgroundHeight + 6;

		for (ButtonWidget button : actionButtons) {
			button.x = x + (button.id % 3) * (BUTTON_WIDTH + 1);
			button.y = top + (button.id / 3) * 21;
			button.method_891(client, mouseX, mouseY, tickDelta);
		}

		final ButtonWidget tradeButton = actionButtons.get(TRADE_BUTTON);
		tradeField.x = tradeButton.x + TRADE_WIDTH + 2;
		tradeField.y = tradeButton.y + 1;
		tradeField.render();

		// The bot's whole state under the buttons: an error with its message.
		textRenderer.drawTrimmed(bots.getStatus(selected), x, top + ACTION_ROWS * 21 + 4, backgroundWidth, TEXT);
		drawPickPanel(top + ACTION_ROWS * 21 + 16, mouseX, mouseY);
		drawMatchPanel(mouseX, mouseY);
	}

	/** A row of the match panel: a field of the item, whether it counts, and what turns it on or off. */
	private static final class MatchRow {
		final String label;
		final String value;
		final boolean on;
		final Runnable toggle;

		MatchRow(String label, String value, boolean on, Runnable toggle) {
			this.label = label;
			this.value = value;
			this.on = on;
			this.toggle = toggle;
		}
	}

	/** The panel's rows, under its title: the item's name, its metadata, each tag of its NBT, and exact last. */
	private static List<MatchRow> matchRows(ItemFilter filter) {
		final List<MatchRow> rows = new ArrayList<MatchRow>();
		rows.add(new MatchRow("Item", filter.reference.getName(), filter.name, () -> filter.name = !filter.name));
		rows.add(new MatchRow("Metadata", String.valueOf(filter.reference.getMetadata()), filter.metadata, () -> filter.metadata = !filter.metadata));

		for (ItemFilter.Field field : filter.fields) {
			rows.add(new MatchRow(field.label, field.value, filter.on.contains(field.path), () -> {
				if (!filter.on.remove(field.path)) {
					filter.on.add(field.path);
				}
			}));
		}

		rows.add(new MatchRow("No additional nbt fields", "", filter.exact, () -> filter.exact = !filter.exact));
		return rows;
	}

	private int panelX() {
		return x + backgroundWidth + MARGIN;
	}

	/** The panel's top: level with the inventory, under the window's own slots while one is open. */
	private int panelTop() {
		return shownWindow == null ? y : y + ((WindowHandler) screenHandler).layout.inventoryTop - 12;
	}

	/** The top of the panel's row, the title's row above the first. */
	private int panelRowTop(int index) {
		return panelTop() + PANEL_ROW + 4 + index * PANEL_ROW;
	}

	/**
	 * The match being edited, right of the inventory: a box for each field of the item, on the ones
	 * that count. The last, exact, has the whole NBT be the item's but at the tags off.
	 */
	private void drawMatchPanel(int mouseX, int mouseY) {
		if (editing == null) {
			return;
		}

		final List<MatchRow> rows = matchRows(editing);
		final int left = panelX();
		final int bottom = panelRowTop(rows.size()) + 2;
		fill(left - 3, panelTop() - 3, left + PANEL_WIDTH, bottom, CARD);
		textRenderer.drawWithShadow("Match: " + editing.label(), left, panelTop(), TEXT);
		String tooltip = null;

		for (int i = 0; i < rows.size(); i++) {
			final MatchRow row = rows.get(i);
			// The exact row apart from the fields.
			final int rowY = panelRowTop(i) + (i == rows.size() - 1 ? 2 : 0);
			fill(left, rowY + 1, left + 8, rowY + 9, 0xFF8B8B8B);
			fill(left + 1, rowY + 2, left + 7, rowY + 8, row.on ? 0xFF55CC55 : 0xFF373737);
			textRenderer.drawWithShadow(textRenderer.trimToWidth(row.label, row.value.isEmpty() ? PANEL_WIDTH - 16 : PANEL_VALUE_X - 14), left + 12, rowY + 1, row.on ? TEXT : DIM);
			final int room = PANEL_WIDTH - PANEL_VALUE_X - 4;
			final String value = textRenderer.trimToWidth(row.value, room);
			textRenderer.drawWithShadow(value, left + PANEL_VALUE_X, rowY + 1, row.on ? TEXT : DIM);

			if (mouseX >= left && mouseX < left + PANEL_WIDTH && mouseY >= rowY && mouseY < rowY + PANEL_ROW && !value.equals(row.value)) {
				tooltip = row.label + ": " + row.value;
			}
		}

		if (tooltip != null) {
			renderTooltip(tooltip, mouseX, mouseY);
		}
	}

	/** A click on the panel: the row under it turned on or off; whether it was on the panel. */
	private boolean clickMatchPanel(int mouseX, int mouseY) {
		if (editing == null) {
			return false;
		}

		final List<MatchRow> rows = matchRows(editing);
		final int left = panelX();

		if (mouseX < left - 3 || mouseX >= left + PANEL_WIDTH || mouseY < panelTop() - 3 || mouseY >= panelRowTop(rows.size()) + 2) {
			return false;
		}

		for (int i = 0; i < rows.size(); i++) {
			final int rowY = panelRowTop(i) + (i == rows.size() - 1 ? 2 : 0);

			if (mouseY >= rowY && mouseY < rowY + PANEL_ROW) {
				rows.get(i).toggle.run();
				break;
			}
		}

		return true;
	}

	/** What the pick asks for, and while waiting each slot picked with its item and count, Start and Cancel under them. */
	private void drawPickPanel(int top, int mouseX, int mouseY) {
		if (pick == Pick.NONE) {
			return;
		}

		final String prompt = pick == Pick.MOVE_FROM ? "Move: click the slot to take from"
				: pick == Pick.MOVE_TO ? "Move: click the slot to put it on"
				: pick == Pick.WAIT_ITEM ? "Wait: click a slot holding the item to wait for"
				: pick == Pick.HOTBAR ? "Hotbar: click the hotbar slot to hold"
				: pick == Pick.FILL_ITEM ? "ItemFill: click a slot holding the item to fill with"
				: pick == Pick.FILL_TARGETS ? "ItemFill: click the slots to fill"
				: "Wait: click the slots to wait on, an item to change it";
		textRenderer.drawWithShadow(prompt, x, top, TEXT);
		int rowY = top + 12;
		ItemStack hovered = null;

		if (pick == Pick.WAIT || pick == Pick.WAIT_ITEM) {
			for (Wait wait : waits) {
				textRenderer.drawWithShadow("slot " + wait.slot, x, rowY + 6, DIM);
				// The item, framed as a slot, lit while it is the one being picked.
				final int iconX = x + ICON_X;
				final int iconY = rowY + 2;
				fill(iconX - 1, iconY - 1, iconX + 17, iconY + 17, wait == choosing ? WAIT_ITEM | 0xFF000000 : 0xFF8B8B8B);
				fill(iconX, iconY, iconX + 16, iconY + 16, 0xFF373737);

				if (wait.item != null) {
					final ItemStack stack = stackOf(wait.item);
					drawItem(stack, iconX, iconY);

					if (mouseX >= iconX && mouseX < iconX + 16 && mouseY >= iconY && mouseY < iconY + 16) {
						hovered = stack;
					}
				}

				final ButtonWidget match = matchButton(wait, rowY);
				match.method_891(client, mouseX, mouseY, 0);
				textRenderer.drawWithShadow("x", x + MIN_X - 8, rowY + 6, DIM);
				wait.min.x = x + MIN_X;
				wait.min.y = rowY + 2;
				wait.min.render();
				rowY += WAIT_ROW;
			}

			startButton.x = x;
			startButton.y = rowY + 2;
			startButton.active = !waits.isEmpty();
			startButton.method_891(client, mouseX, mouseY, 0);
		}

		if (pick == Pick.FILL_TARGETS) {
			textRenderer.drawWithShadow(fillFilter.reference.getName() + " (" + fillFilter.label() + ") -> " + fillTargets, x, rowY + 2, DIM);
			rowY += 12;
			startButton.x = x;
			startButton.y = rowY + 2;
			startButton.active = !fillTargets.isEmpty();
			startButton.method_891(client, mouseX, mouseY, 0);
		}

		cancelButton.x = pick == Pick.WAIT || pick == Pick.WAIT_ITEM || pick == Pick.FILL_TARGETS ? x + BUTTON_WIDTH + 1 : x;
		cancelButton.y = rowY + 2;
		cancelButton.method_891(client, mouseX, mouseY, 0);

		if (hovered != null) {
			renderTooltip(hovered, mouseX, mouseY);
		}
	}

	/** The wait's match button where its row puts it, off with no item to match; it shows its match in the panel. */
	private ButtonWidget matchButton(Wait wait, int rowY) {
		final ButtonWidget button = new ButtonWidget(0, x + MATCH_X, rowY + 2, MATCH_WIDTH, 16, wait.filter == null ? "any" : wait.filter.label());
		button.active = wait.filter != null;
		return button;
	}

	/** The top of the wait's row, as drawPickPanel lays them. */
	private int rowTop(int index) {
		return y + backgroundHeight + 6 + ACTION_ROWS * 21 + 16 + 12 + index * WAIT_ROW;
	}

	private void drawItem(ItemStack stack, int itemX, int itemY) {
		GlStateManager.pushMatrix();
		DiffuseLighting.enable();
		GlStateManager.enableRescaleNormal();
		GlStateManager.enableColorMaterial();
		GlStateManager.enableLighting();
		itemRenderer.method_12461(stack, itemX, itemY);
		itemRenderer.renderGuiItemOverlay(textRenderer, stack, itemX, itemY);
		GlStateManager.disableLighting();
		DiffuseLighting.disable();
		GlStateManager.popMatrix();
	}

	private void resetPick() {
		pick = Pick.NONE;
		moveFrom = -1;
		fillFilter = null;
		editing = null;
		fillTargets.clear();
		fillDrag = null;
		choosing = null;
		waits.clear();
	}

	/** The slot of the screen handler under the point, null for none. */
	private Slot slotAt(int mouseX, int mouseY) {
		for (Slot slot : screenHandler.slots) {
			if (isPointWithinBounds(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
				return slot;
			}
		}

		return null;
	}

	/** A slot clicked while picking: move's origin, then its destination and the move sent; or a slot waited on, or no longer. */
	private void pickSlot(int slot) {
		switch (pick) {
			case MOVE_FROM:
				moveFrom = slot;
				pick = Pick.MOVE_TO;
				return;

			case MOVE_TO:
				if (slot == moveFrom) {
					return;
				}

				MapMcBotClient.command("#move " + selected + " " + moveFrom + " " + slot);
				resetPick();
				return;

			case FILL_ITEM: {
				final BotInventory.Item there = itemOn(slot);

				// An empty slot names no item: the pick goes on.
				if (there == null) {
					return;
				}

				fillFilter = new ItemFilter(there);
				editing = fillFilter;
				pick = Pick.FILL_TARGETS;
				return;
			}

			case FILL_TARGETS:
				fillDrag = !fillTargets.contains(slot);
				dragOver(slot);
				return;

			case HOTBAR:
				MapMcBotClient.command("#hotbar " + selected + " " + slot);
				resetPick();
				return;

			case WAIT:
				for (int i = 0; i < waits.size(); i++) {
					if (waits.get(i).slot == slot) {
						if (editing == waits.get(i).filter) {
							editing = null;
						}

						waits.remove(i);
						return;
					}
				}

				// What the slot holds now as the item, to change at will.
				final TextFieldWidget min = new TextFieldWidget(1, textRenderer, 0, 0, 30, 16);
				min.setMaxLength(4);
				min.setText("1");
				waits.add(new Wait(slot, itemOn(slot), min));
				return;

			case WAIT_ITEM: {
				final BotInventory.Item there = itemOn(slot);

				// An empty slot shows no item: the pick goes on.
				if (there == null) {
					return;
				}

				// A new item, its match anew: every field counting, shown to change.
				choosing.item = there;
				choosing.filter = new ItemFilter(there);
				editing = choosing.filter;
				choosing = null;
				pick = Pick.WAIT;
				return;
			}

			default:
				throw new IllegalStateException("a slot picked with no pick going on");
		}
	}

	/** The slot dragged over, selected or deselected as the drag began. */
	private void dragOver(int slot) {
		final boolean in = fillTargets.contains(slot);

		if (fillDrag && !in) {
			fillTargets.add(slot);
		} else if (!fillDrag && in) {
			fillTargets.remove(Integer.valueOf(slot));
		}
	}

	/** The bot's item on the slot of what the screen shows, null for none. */
	private BotInventory.Item itemOn(int slot) {
		if (shownWindow != null) {
			return shownWindow.getSlots().get(slot);
		}

		final BotInventory inventory = bots.getInventory(selected);
		return inventory == null ? null : inventory.getSlots().get(slot);
	}

	/** The wait sent: each slot with its count, and its item as it has to match it (see ItemFilter). */
	private void startWait() {
		final JsonArray waited = new JsonArray();
		final List<Integer> slots = new ArrayList<Integer>();

		for (Wait wait : waits) {
			final String min = wait.min.getText().trim();

			// Told here, the panel kept to fix it: not sent, nothing to light.
			if (!min.matches("[1-9][0-9]*")) {
				AreaPick.tell(client, "waitslot_err: the count of slot " + wait.slot + " must be a positive number, got \"" + min + "\"");
				return;
			}

			slots.add(wait.slot);
			final JsonObject one = new JsonObject();
			one.addProperty("slot", wait.slot);
			one.addProperty("min", Integer.parseInt(min));

			if (wait.filter != null) {
				one.add("item", wait.filter.toJson());
			}

			waited.add(one);
		}

		final JsonObject a = new JsonObject();
		a.addProperty("action", "wait_slot");
		a.add("slots", waited);
		sent.put(selected, new Sent(slots, shownWindow == null ? null : shownWindow.getType(), bots.getState(selected)));
		resetPick();
		bots.action(selected, a.toString());
	}

	/** The item fill sent: the items its match takes onto the slots picked. */
	private void startFill() {
		final JsonObject a = new JsonObject();
		a.addProperty("action", "item_fill");
		a.add("item", fillFilter.toJson());
		final JsonArray targets = new JsonArray();

		for (int target : fillTargets) {
			targets.add(target);
		}

		a.add("targets", targets);
		resetPick();
		bots.action(selected, a.toString());
	}

	/**
	 * Whether the bot's wait sent is still going on: its state not yet reported since (the very one it
	 * was sent over), or wait_slot being done; any other is its end.
	 */
	private boolean stillWaiting(Sent wait) {
		final BotStatus now = bots.getState(selected);
		return now == wait.before || (now.getKind() == BotStatus.Kind.DOING && now.getPrimitive().equals("wait_slot"));
	}

	@Override
	public void tick() {
		super.tick();
		tradeField.tick();

		for (Wait wait : waits) {
			wait.min.tick();
		}
	}

	/**
	 * The screen follows the bot's window: a chest's laid out for it while one is open, made again only
	 * for another kind, and every slot and the cursor from each the fleet reports; the inventory once
	 * it is closed.
	 */
	private void refreshWindow(BotWindow window) {
		if (window == shownWindow) {
			return;
		}

		final BotWindow before = shownWindow;
		shownWindow = window;

		if (window == null) {
			use(inventoryHandler);
			final BotInventory inventory = bots.getInventory(selected);

			if (inventory != null) {
				show(inventory);
			}

			return;
		}

		if (before == null || !before.getType().equals(window.getType()) || before.getSlots().size() != window.getSlots().size()) {
			use(new WindowHandler(window));
			trade = 0;
		}

		for (int i = 0; i < window.getSlots().size(); i++) {
			screenHandler.getSlot(i).setStack(stackOf(window.getSlots().get(i)));
		}

		client.player.inventory.setCursorStack(stackOf(window.getCursor()));
	}

	/** The screen handler shown, and the screen sized and placed for it, the buttons still under it. */
	private void use(ScreenHandler handler) {
		if (handler == screenHandler) {
			return;
		}

		// The slots picked were the other handler's.
		resetPick();
		screenHandler = handler;
		client.player.openScreenHandler = handler;
		cursorDragSlots.clear();
		isCursorDragging = false;

		if (handler == inventoryHandler) {
			backgroundWidth = 176;
			backgroundHeight = 166;
			y = baseY;
		} else {
			final Layout layout = ((WindowHandler) handler).layout;
			backgroundWidth = layout.width;
			backgroundHeight = layout.height;
			// The name over it and the buttons and the status under it.
			y = Math.max(13, (height - backgroundHeight - ACTION_ROWS * 21 - 20) / 2);
		}

		place();
	}

	/** Right of the cards, centred in what they leave, the match panel's room aside while it shows. */
	private void place() {
		x = LEFT + Math.max(0, (width - LEFT - backgroundWidth - (editing != null ? PANEL_WIDTH + MARGIN : 0)) / 2);
	}

	/** The window's name as the game titles it, else its type's. */
	private static String title(String key, String type) {
		if (I18n.method_12500(key)) {
			return I18n.translate(key);
		}

		return Character.toUpperCase(type.charAt(0)) + type.substring(1).replace('_', ' ');
	}

	/**
	 * How the game's own screen for a kind of window looks: its texture and size, where each of the
	 * window's slots and the inventory's sit on it, and its title. A chest's (no texture of its own:
	 * drawn a row at a time) for the kinds the game lays out as one.
	 */
	private static final class Layout {
		/** Null for a chest's. */
		final Identifier texture;
		final int width;
		final int height;
		/** Each of the window's own slots, x and y. */
		final int[][] slots;
		final int inventoryX;
		final int inventoryTop;
		/** Null for none. */
		final String title;
		/** -1 for centred. */
		final int titleX;
		final int titleY;
		/** Chest rows, 0 for another kind. */
		final int rows;

		private Layout(String texture, int width, int height, int[][] slots, int inventoryX, int inventoryTop, String title, int titleX, int titleY, int rows) {
			this.texture = texture == null ? null : new Identifier("textures/gui/container/" + texture + ".png");
			this.width = width;
			this.height = height;
			this.slots = slots;
			this.inventoryX = inventoryX;
			this.inventoryTop = inventoryTop;
			this.title = title;
			this.titleX = titleX;
			this.titleY = titleY;
			this.rows = rows;
		}

		/** The 176 by 166 screens, the inventory at the bottom as the player's own. */
		private static Layout plain(String texture, String title, int titleX, int titleY, int[]... slots) {
			return new Layout(texture, 176, 166, slots, 8, 84, title, titleX, titleY, 0);
		}

		/** A grid of count slots, columns to a row, its first at x, y. */
		private static int[][] grid(int x, int y, int columns, int count) {
			final int[][] slots = new int[count][];

			for (int i = 0; i < count; i++) {
				slots[i] = new int[] {x + (i % columns) * 18, y + (i / columns) * 18};
			}

			return slots;
		}

		static Layout of(BotWindow window) {
			final String type = window.getType().replace("minecraft:", "");
			final int own = window.getContainerSlots();
			final Layout layout;

			switch (type) {
				case "dispenser":
				case "dropper":
					layout = plain("dispenser", title("container." + type, type), -1, 6, grid(62, 17, 3, 9));
					break;
				case "hopper":
					layout = new Layout("hopper", 176, 133, grid(44, 20, 5, 5), 8, 51, title("container.hopper", type), 8, 6, 0);
					break;
				case "furnace":
					layout = plain("furnace", title("container.furnace", type), -1, 6, new int[] {56, 17}, new int[] {56, 53}, new int[] {116, 35});
					break;
				case "brewing_stand":
					layout = plain("brewing_stand", title("container.brewing", type), -1, 6, new int[] {56, 51}, new int[] {79, 58}, new int[] {102, 51}, new int[] {79, 17}, new int[] {17, 17});
					break;
				case "crafting_table": {
					final int[][] slots = new int[10][];
					slots[0] = new int[] {124, 35};
					System.arraycopy(grid(30, 17, 3, 9), 0, slots, 1, 9);
					layout = plain("crafting_table", title("container.crafting", type), 28, 6, slots);
					break;
				}
				case "enchanting_table":
					layout = plain("enchanting_table", title("container.enchant", type), 12, 5, new int[] {15, 47}, new int[] {35, 47});
					break;
				case "anvil":
					layout = plain("anvil", title("container.repair", type), 60, 6, new int[] {27, 47}, new int[] {76, 47}, new int[] {134, 47});
					break;
				case "villager":
					layout = plain("villager", title("entity.Villager.name", type), -1, 6, new int[] {36, 53}, new int[] {62, 53}, new int[] {120, 53});
					break;
				case "beacon":
					layout = new Layout("beacon", 230, 219, new int[][] {{136, 110}}, 36, 137, null, 0, 0, 0);
					break;
				case "shulker_box":
					layout = plain("shulker_box", title("container.shulkerBox", type), 8, 6, grid(8, 18, 9, 27));
					break;
				default: {
					// A chest's, as the game shows every window of slots in rows of nine.
					final int rows = Math.max(1, (own + 8) / 9);
					final String key = type.equals("chest") && own > 27 ? "container.chestDouble" : "container." + type;
					layout = new Layout(null, 176, 114 + rows * 18, grid(8, 18, 9, own), 8, 103 + (rows - 4) * 18, title(key, type), 8, 6, rows);
				}
			}

			if (layout.slots.length != own) {
				throw new IllegalStateException("a " + window.getType() + " window with " + own + " slots of its own, its screen " + layout.slots.length);
			}

			return layout;
		}
	}

	/**
	 * The bot's window laid out as the game's screen for it (see Layout): its own slots, then the
	 * inventory and the hotbar, slot i being the window's slot i. Only the items are here: what the
	 * window does with them (a furnace's output, a shift click) is the bot's to answer.
	 */
	private static final class WindowHandler extends ScreenHandler {
		private final Layout layout;

		WindowHandler(BotWindow window) {
			final int own = window.getContainerSlots();

			if (window.getSlots().size() != own + 36) {
				throw new IllegalStateException("a " + window.getType() + " window of " + window.getSlots().size() + " slots, " + own + " its own: not the inventory's 36 after them");
			}

			layout = Layout.of(window);
			syncId = SYNC_ID;
			final SimpleInventory items = new SimpleInventory("", false, own + 36);

			for (int i = 0; i < own; i++) {
				addSlot(new Slot(items, i, layout.slots[i][0], layout.slots[i][1]));
			}

			for (int i = 0; i < 27; i++) {
				addSlot(new Slot(items, own + i, layout.inventoryX + (i % 9) * 18, layout.inventoryTop + (i / 9) * 18));
			}

			for (int i = 0; i < 9; i++) {
				addSlot(new Slot(items, own + 27 + i, layout.inventoryX + i * 18, layout.inventoryTop + 58));
			}
		}

		@Override
		public boolean canUse(PlayerEntity player) {
			return true;
		}

		/**
		 * A shift click moves nothing here: where it goes is the window's (the bot's) to answer. The
		 * game's own would hand back the slot's stack, and the click would take it again and again.
		 */
		@Override
		public ItemStack transferSlot(PlayerEntity player, int index) {
			return ItemStack.EMPTY;
		}
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
			textRenderer.drawWithShadow(textRenderer.trimToWidth(bots.getStatus(name) + "  " + where, CARD_WIDTH - 14), MARGIN + 7, top + 13, DIM);
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

		if (shownWindow != null) {
			final Layout layout = ((WindowHandler) screenHandler).layout;

			if (layout.texture != null) {
				client.getTextureManager().bindTexture(layout.texture);
				drawTexture(x, y, 0, 0, backgroundWidth, backgroundHeight);
				drawProgress(shownWindow);
				return;
			}

			// A chest's, a row of slots for each nine of the window's, the slots past its last blank.
			final int rows = layout.rows;
			client.getTextureManager().bindTexture(CHEST_TEXTURE);
			drawTexture(x, y, 0, 0, backgroundWidth, 17);

			for (int row = 0; row < rows; row++) {
				drawTexture(x, y + 17 + row * 18, 0, 17, backgroundWidth, 18);
			}

			drawTexture(x, y + 17 + rows * 18, 0, 126, backgroundWidth, 96);

			for (int i = shownWindow.getContainerSlots(); i < rows * 9; i++) {
				final int sx = x + 7 + (i % 9) * 18;
				final int sy = y + 17 + (i / 9) * 18;
				fill(sx, sy, sx + 18, sy + 18, CHEST_BACKGROUND);
			}

			return;
		}

		client.getTextureManager().bindTexture(INVENTORY_TEXTURE);
		drawTexture(x, y, 0, 0, backgroundWidth, backgroundHeight);
		// The bot itself where the player stands in the client's, when it is in sight.
		final PlayerEntity bot = client.world.getPlayerByName(selected);

		if (bot != null) {
			SurvivalInventoryScreen.renderEntity(x + 51, y + 75, 30, x + 51 - mouseX, y + 75 - 50 - mouseY, bot);
		}
	}

	/** The villager's arrows, as the game's: the trade before (x 17) and after (x 147), 12 by 19 at y 23. */
	private static final int TRADE_ARROW_Y = 23;
	private static final int[] TRADE_ARROW_X = {17, 147};

	/** The trade the arrow at the point picks, -1 for none (or an arrow with no trade that way). */
	private int tradeArrowAt(BotWindow window, int mouseX, int mouseY) {
		for (int i = 0; i < 2; i++) {
			final int next = trade + (i == 0 ? -1 : 1);

			if (next >= 0 && next < window.getTrades().size() && isPointWithinBounds(TRADE_ARROW_X[i], TRADE_ARROW_Y, 12, 19, mouseX, mouseY)) {
				return next;
			}
		}

		return -1;
	}

	/**
	 * The villager's trade at the top, as the game's screen draws it: the arrows to the others, the
	 * items it takes and gives, their tooltips.
	 */
	private void drawTrade(BotWindow window, int mouseX, int mouseY) {
		trade = Math.min(trade, window.getTrades().size() - 1);
		final BotWindow.Trade shown = window.getTrades().get(trade);
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		client.getTextureManager().bindTexture(((WindowHandler) screenHandler).layout.texture);

		for (int i = 0; i < 2; i++) {
			final int next = trade + (i == 0 ? -1 : 1);
			final boolean enabled = next >= 0 && next < window.getTrades().size();
			final boolean hovered = isPointWithinBounds(TRADE_ARROW_X[i], TRADE_ARROW_Y, 12, 19, mouseX, mouseY);
			final int u = 176 + (!enabled ? 24 : hovered ? 12 : 0);
			drawTexture(x + TRADE_ARROW_X[i], y + TRADE_ARROW_Y, u, i == 0 ? 19 : 0, 12, 19);
		}

		// A used up trade: crossed out over both arrows.
		if (shown.isDisabled()) {
			drawTexture(x + 83, y + 21, 212, 0, 28, 21);
			drawTexture(x + 83, y + 51, 212, 0, 28, 21);
		}

		final BotInventory.Item[] items = {shown.getFirst(), shown.getSecond(), shown.getResult()};
		final int[] at = {36, 62, 120};
		ItemStack hovered = null;
		GlStateManager.pushMatrix();
		DiffuseLighting.enable();
		GlStateManager.enableRescaleNormal();
		GlStateManager.enableColorMaterial();
		GlStateManager.enableLighting();

		for (int i = 0; i < 3; i++) {
			final ItemStack stack = stackOf(items[i]);

			if (stack.isEmpty()) {
				continue;
			}

			itemRenderer.method_12461(stack, x + at[i], y + 24);
			itemRenderer.renderGuiItemOverlay(textRenderer, stack, x + at[i], y + 24);

			if (isPointWithinBounds(at[i], 24, 16, 16, mouseX, mouseY)) {
				hovered = stack;
			}
		}

		GlStateManager.disableLighting();
		DiffuseLighting.disable();
		GlStateManager.popMatrix();

		if (hovered != null) {
			renderTooltip(hovered, mouseX, mouseY);
		}
	}

	/** Brewing's bubbles, by brew time left: the game's own lengths. */
	private static final int[] BUBBLES = {29, 24, 20, 16, 11, 6, 0};

	/**
	 * What the game's screen draws from the window's properties over its texture (still bound): a
	 * furnace's flame and arrow, a brewing stand's fuel, arrow and bubbles, as the game's screens do.
	 */
	private void drawProgress(BotWindow window) {
		switch (window.getType().replace("minecraft:", "")) {
			case "furnace": {
				final int burnTime = window.getProperty(0);

				if (burnTime > 0) {
					final int fuelTime = window.getProperty(1);
					final int flame = burnTime * 13 / (fuelTime == 0 ? 200 : fuelTime);
					drawTexture(x + 56, y + 36 + 12 - flame, 176, 12 - flame, 14, flame + 1);
				}

				final int cookTime = window.getProperty(2);
				final int totalCookTime = window.getProperty(3);
				final int arrow = cookTime != 0 && totalCookTime != 0 ? cookTime * 24 / totalCookTime : 0;
				drawTexture(x + 79, y + 34, 176, 14, arrow + 1, 16);
				break;
			}

			case "brewing_stand": {
				final int fuel = Math.max(0, Math.min(18, (18 * window.getProperty(1) + 20 - 1) / 20));

				if (fuel > 0) {
					drawTexture(x + 60, y + 44, 176, 29, fuel, 4);
				}

				final int brewTime = window.getProperty(0);

				if (brewTime > 0) {
					final int arrow = (int) (28.0F * (1.0F - brewTime / 400.0F));

					if (arrow > 0) {
						drawTexture(x + 97, y + 16, 176, 0, 9, arrow);
					}

					final int bubbles = BUBBLES[brewTime / 2 % 7];

					if (bubbles > 0) {
						drawTexture(x + 63, y + 14 + 29 - bubbles, 185, 29 - bubbles, 12, bubbles);
					}
				}

				break;
			}

			default:
		}
	}

	@Override
	protected void drawForeground(int mouseX, int mouseY) {
		if (shownWindow != null) {
			final Layout layout = ((WindowHandler) screenHandler).layout;

			// The beacon's screen names nothing.
			if (layout.title != null) {
				final int titleX = layout.titleX < 0 ? (backgroundWidth - textRenderer.getStringWidth(layout.title)) / 2 : layout.titleX;
				textRenderer.draw(layout.title, titleX, layout.titleY, 0x404040);
				textRenderer.draw(I18n.translate("container.inventory"), 8, layout.inventoryTop - (layout.rows > 0 ? 11 : 12), 0x404040);
			}
		} else {
			textRenderer.draw("Crafting", 97, 8, 0x404040);
		}

		if (moveFrom >= 0) {
			fillSlot(moveFrom, MOVE_FROM);
		}

		for (Wait wait : waits) {
			fillSlot(wait.slot, WAITED);
		}

		for (int slot : fillTargets) {
			fillSlot(slot, MOVE_TO);
		}

		// The wait sent, on the slots of the window it was sent on.
		final Sent waiting = sent.get(selected);

		if (waiting != null) {
			if (!stillWaiting(waiting)) {
				sent.remove(selected);
			} else if (Objects.equals(waiting.window, shownWindow == null ? null : shownWindow.getType())) {
				for (int slot : waiting.slots) {
					fillSlot(slot, WAITED);
				}
			}
		}

		// The slot a click would pick, in the colour it would take.
		if (pick != Pick.NONE) {
			final Slot hovered = slotAt(mouseX, mouseY);

			if (hovered != null && hovered.id != moveFrom) {
				fillSlot(hovered.id, pick == Pick.MOVE_FROM ? MOVE_FROM : pick == Pick.MOVE_TO ? MOVE_TO : pick == Pick.WAIT ? WAITED : pick == Pick.HOTBAR ? HOTBAR : pick == Pick.FILL_ITEM ? WAIT_ITEM : pick == Pick.FILL_TARGETS ? MOVE_TO : WAIT_ITEM);
			}
		}

		if (held < 0) {
			return;
		}

		// The hotbar slot in its hand, framed as the hotbar frames it.
		final Slot slot = screenHandler.getSlot((shownWindow != null ? shownWindow.getContainerSlots() + 27 : 36) + held);
		fill(slot.x - 1, slot.y - 1, slot.x + 17, slot.y, HELD);
		fill(slot.x - 1, slot.y + 16, slot.x + 17, slot.y + 17, HELD);
		fill(slot.x - 1, slot.y, slot.x, slot.y + 16, HELD);
		fill(slot.x + 16, slot.y, slot.x + 17, slot.y + 16, HELD);
	}

	private void fillSlot(int id, int color) {
		final Slot slot = screenHandler.getSlot(id);
		GlStateManager.disableLighting();
		GlStateManager.disableDepthTest();
		fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
		GlStateManager.enableDepthTest();
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

		// The villager's arrows: the trade before or after, the server's to show in the result slot.
		if (button == 0 && shownWindow != null && !shownWindow.getTrades().isEmpty()) {
			final int next = tradeArrowAt(shownWindow, mouseX, mouseY);

			if (next >= 0) {
				trade = next;
				tradeField.setText(String.valueOf(next));
				bots.tradeSelect(selected, next);
				return;
			}
		}

		if (selected != null) {
			tradeField.method_920(mouseX, mouseY, button);

			if (button == 0) {
				for (ButtonWidget action : actionButtons) {
					if (action.isMouseOver(client, mouseX, mouseY)) {
						launch(action.id);
						return;
					}
				}
			}

			if (pick == Pick.NONE) {
				super.mouseClicked(mouseX, mouseY, button);
				return;
			}

			// While picking a click goes to the panel or picks a slot, never to the bot.
			if (button == 0 && pick == Pick.WAIT && !waits.isEmpty() && startButton.isMouseOver(client, mouseX, mouseY)) {
				startWait();
				return;
			}

			if (button == 0 && pick == Pick.FILL_TARGETS && !fillTargets.isEmpty() && startButton.isMouseOver(client, mouseX, mouseY)) {
				startFill();
				return;
			}

			if (button == 0 && clickMatchPanel(mouseX, mouseY)) {
				return;
			}

			if (button == 0 && cancelButton.isMouseOver(client, mouseX, mouseY)) {
				resetPick();
				return;
			}

			// A wait's item: the next slot clicked holds the one it waits for; its match button: its match in the panel, or out of it.
			if (button == 0 && (pick == Pick.WAIT || pick == Pick.WAIT_ITEM)) {
				for (int i = 0; i < waits.size(); i++) {
					final Wait wait = waits.get(i);
					final int rowY = rowTop(i);

					if (mouseX >= x + ICON_X && mouseX < x + ICON_X + 16 && mouseY >= rowY + 2 && mouseY < rowY + 18) {
						choosing = wait == choosing ? null : wait;
						pick = choosing == null ? Pick.WAIT : Pick.WAIT_ITEM;
						return;
					}

					if (wait.filter != null && matchButton(wait, rowY).isMouseOver(client, mouseX, mouseY)) {
						editing = editing == wait.filter ? null : wait.filter;
						return;
					}
				}
			}

			for (Wait wait : waits) {
				wait.min.method_920(mouseX, mouseY, button);
			}

			final Slot slot = slotAt(mouseX, mouseY);

			if (button == 0 && slot != null) {
				pickSlot(slot.id);
			}
		}
	}

	@Override
	protected void mouseDragged(int mouseX, int mouseY, int button, long held) {
		if (fillDrag != null) {
			final Slot over = slotAt(mouseX, mouseY);

			if (over != null) {
				dragOver(over.id);
			}

			return;
		}

		if (selected != null && pick == Pick.NONE) {
			super.mouseDragged(mouseX, mouseY, button, held);
		}
	}

	@Override
	protected void mouseReleased(int mouseX, int mouseY, int button) {
		fillDrag = null;

		if (selected != null && pick == Pick.NONE) {
			super.mouseReleased(mouseX, mouseY, button);
		}
	}

	@Override
	protected void keyPressed(char character, int keyCode) {
		// A focused field takes every key but escape, B and E among them.
		if (keyCode != 1) {
			// Digits only, and the keys that edit (backspace, the arrows), which type no character.
			if (tradeField.isFocused()) {
				if (Character.isDigit(character) || character < ' ') {
					tradeField.keyPressed(character, keyCode);
				}

				return;
			}

			for (Wait wait : waits) {
				if (wait.min.isFocused()) {
					wait.min.keyPressed(character, keyCode);
					return;
				}
			}
		}

		// B closes it as the inventory key closes the player's.
		if (keyCode == MapMcBotClient.botsKeyCode()) {
			client.player.closeHandledScreen();
			return;
		}

		super.keyPressed(character, keyCode);
	}
}
