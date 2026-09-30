package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.List;

import net.mapmcbot.area.Area;
import net.mapmcbot.area.AreaStore;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The area editor: a list on the left, the selected area's fields on the right.
 *
 * Edits go into the area as they are typed, so its box in the world follows along. Coordinates
 * only commit once they all parse, so a half-typed minus sign does not collapse the area.
 */
public class AreaScreen extends Screen {
	private static final int BUTTON_ADD = 1;
	private static final int BUTTON_DELETE = 2;
	private static final int BUTTON_COLOR = 3;
	private static final int BUTTON_DONE = 4;

	private static final int LIST_WIDTH = 132;
	private static final int ROW_HEIGHT = 22;
	private static final int LIST_TOP = 34;

	private static final int COLOR_PANEL = 0xC0101010;
	private static final int COLOR_ROW = 0x40FFFFFF;
	private static final int COLOR_ROW_SELECTED = 0x80FFFFFF;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_DIM_TEXT = 0xFFA0A0A0;

	private AreaStore store;
	private Area selected;

	private TextFieldWidget nameField;
	private final TextFieldWidget[] coordFields = new TextFieldWidget[6];
	private final List<TextFieldWidget> fields = new ArrayList<TextFieldWidget>();

	private int scroll;

	@Override
	public void init() {
		Keyboard.enableRepeatEvents(true);

		store = MapMcBotClient.areas();
		fields.clear();
		buttons.clear();

		final int editorX = LIST_WIDTH + 16;

		nameField = new TextFieldWidget(0, textRenderer, editorX, LIST_TOP + 12, 150, 16);
		nameField.setMaxLength(48);
		fields.add(nameField);

		for (int i = 0; i < coordFields.length; i++) {
			final TextFieldWidget field = new TextFieldWidget(1 + i, textRenderer,
					editorX + (i % 3) * 52, LIST_TOP + 60 + (i / 3) * 24, 46, 16);
			field.setMaxLength(8);
			coordFields[i] = field;
			fields.add(field);
		}

		addButton(new ButtonWidget(BUTTON_COLOR, editorX, LIST_TOP + 116, 100, 20, "Colour"));
		addButton(new ButtonWidget(BUTTON_DELETE, editorX, LIST_TOP + 140, 100, 20, "Delete area"));
		addButton(new ButtonWidget(BUTTON_ADD, 8, height - 52, LIST_WIDTH, 20, "Add area"));
		addButton(new ButtonWidget(BUTTON_DONE, 8, height - 28, LIST_WIDTH, 20, "Done"));

		if (selected == null && store != null && !store.getAreas().isEmpty()) {
			select(store.getAreas().get(0));
		} else {
			select(selected);
		}
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);

		if (store != null) {
			store.save();
		}
	}

	@Override
	public boolean shouldPauseGame() {
		return false;
	}

	private void select(Area area) {
		selected = area;
		final boolean enabled = area != null;

		for (int i = 0; i < buttons.size(); i++) {
			final ButtonWidget button = buttons.get(i);

			if (button.id == BUTTON_DELETE || button.id == BUTTON_COLOR) {
				button.active = enabled;
			}
		}

		for (int i = 0; i < fields.size(); i++) {
			fields.get(i).setVisible(enabled);
			fields.get(i).setFocused(false);
		}

		if (!enabled) {
			return;
		}

		nameField.setText(area.getName());
		setCoord(0, area.getMinX());
		setCoord(1, area.getMinY());
		setCoord(2, area.getMinZ());
		setCoord(3, area.getMaxX());
		setCoord(4, area.getMaxY());
		setCoord(5, area.getMaxZ());
	}

	private void setCoord(int index, int value) {
		coordFields[index].setText(Integer.toString(value));
		coordFields[index].setCursorToStart();
	}

	/** Pushes the fields back into the selected area, skipping coordinates that do not parse yet. */
	private void commitFields() {
		if (selected == null || store == null) {
			return;
		}

		if (!nameField.getText().equals(selected.getName())) {
			selected.setName(nameField.getText());
			store.markDirty();
		}

		final int[] values = new int[6];

		for (int i = 0; i < coordFields.length; i++) {
			try {
				values[i] = Integer.parseInt(coordFields[i].getText());
			} catch (NumberFormatException e) {
				// Mid-edit text such as "-" or "": not an error, just not committable yet.
				return;
			}
		}

		if (values[0] != selected.getMinX() || values[1] != selected.getMinY() || values[2] != selected.getMinZ()
				|| values[3] != selected.getMaxX() || values[4] != selected.getMaxY() || values[5] != selected.getMaxZ()) {
			selected.setCorners(values[0], values[1], values[2], values[3], values[4], values[5]);
			store.markDirty();
		}
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (store == null) {
			return;
		}

		switch (button.id) {
				case BUTTON_ADD:
				// The pick happens in the world, so the screen gets out of the way.
				client.setScreen(null);
				AreaPick.begin(client, AreaPick.PALETTE[store.getAreas().size() % AreaPick.PALETTE.length]);
				break;

			case BUTTON_DELETE:
				if (selected != null) {
					store.remove(selected);
					select(store.getAreas().isEmpty() ? null : store.getAreas().get(0));
				}
				break;

			case BUTTON_COLOR:
				if (selected != null) {
					selected.setColor(nextColor(selected.getColor()));
					store.markDirty();
				}
				break;

			case BUTTON_DONE:
				client.setScreen(null);
				break;

			default:
				break;
		}
	}

	private static int nextColor(int current) {
		for (int i = 0; i < AreaPick.PALETTE.length; i++) {
			if (AreaPick.PALETTE[i] == current) {
				return AreaPick.PALETTE[(i + 1) % AreaPick.PALETTE.length];
			}
		}

		return AreaPick.PALETTE[0];
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		renderBackground();

		fill(0, 0, width, 24, COLOR_PANEL);
		textRenderer.draw("Areas", 8, 8, COLOR_TEXT);

		if (store == null) {
			textRenderer.draw("Not in a world", 8, LIST_TOP, COLOR_DIM_TEXT);
			super.render(mouseX, mouseY, tickDelta);
			return;
		}

		drawList();
		drawEditor();
		super.render(mouseX, mouseY, tickDelta);

		for (int i = 0; i < fields.size(); i++) {
			if (fields.get(i).isVisible()) {
				fields.get(i).render();
			}
		}
	}

	private void drawList() {
		final List<Area> areas = store.getAreas();

		fill(4, LIST_TOP - 4, 4 + LIST_WIDTH, height - 56, COLOR_PANEL);

		if (areas.isEmpty()) {
			textRenderer.draw("No areas yet", 12, LIST_TOP + 4, COLOR_DIM_TEXT);
			return;
		}

		final int visibleRows = Math.max(1, (height - 56 - LIST_TOP) / ROW_HEIGHT);
		scroll = Math.max(0, Math.min(scroll, areas.size() - visibleRows));

		for (int row = 0; row < visibleRows && row + scroll < areas.size(); row++) {
			final Area area = areas.get(row + scroll);
			final int top = LIST_TOP + row * ROW_HEIGHT;

			fill(8, top, LIST_WIDTH, top + ROW_HEIGHT - 2, area == selected ? COLOR_ROW_SELECTED : COLOR_ROW);
			fill(11, top + 3, 21, top + 13, 0xFF000000 | area.getColor());

			textRenderer.draw(trim(area.getName(), LIST_WIDTH - 40), 26, top + 4, COLOR_TEXT);
			textRenderer.draw(area.getWidth() + "x" + area.getHeight() + "x" + area.getDepth(),
					26, top + 13, COLOR_DIM_TEXT);
		}
	}

	private String trim(String text, int maxWidth) {
		if (textRenderer.getStringWidth(text) <= maxWidth) {
			return text;
		}

		String out = text;

		while (out.length() > 1 && textRenderer.getStringWidth(out + "...") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}

		return out + "...";
	}

	private void drawEditor() {
		final int x = LIST_WIDTH + 16;

		if (selected == null) {
			textRenderer.draw("Select an area, or add one", x, LIST_TOP, COLOR_DIM_TEXT);
			return;
		}

		textRenderer.draw("Name", x, LIST_TOP, COLOR_DIM_TEXT);
		textRenderer.draw("Corner 1", x, LIST_TOP + 48, COLOR_DIM_TEXT);
		textRenderer.draw("Corner 2", x, LIST_TOP + 72, COLOR_DIM_TEXT);
		textRenderer.draw("X", x + 2, LIST_TOP + 38, COLOR_DIM_TEXT);
		textRenderer.draw("Y", x + 54, LIST_TOP + 38, COLOR_DIM_TEXT);
		textRenderer.draw("Z", x + 106, LIST_TOP + 38, COLOR_DIM_TEXT);

		fill(x + 104, LIST_TOP + 116, x + 124, LIST_TOP + 136, 0xFF000000 | selected.getColor());

		textRenderer.draw(selected.getWidth() + " x " + selected.getHeight() + " x " + selected.getDepth()
				+ "  =  " + selected.getVolume() + " blocks", x, LIST_TOP + 168, COLOR_DIM_TEXT);
	}

	@Override
	public void handleMouse() {
		super.handleMouse();

		final int wheel = Mouse.getDWheel();

		if (wheel != 0) {
			// Clamped by the next frame's draw.
			scroll += wheel > 0 ? -1 : 1;
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		super.mouseClicked(mouseX, mouseY, button);

		for (int i = 0; i < fields.size(); i++) {
			// A hidden field taking focus would swallow every keystroke with nothing to show.
			if (fields.get(i).isVisible()) {
				fields.get(i).method_920(mouseX, mouseY, button);
			}
		}

		if (store == null || button != 0 || mouseX > LIST_WIDTH || mouseY < LIST_TOP) {
			return;
		}

		final int row = (mouseY - LIST_TOP) / ROW_HEIGHT + scroll;

		if (row < store.getAreas().size()) {
			select(store.getAreas().get(row));
		}
	}

	@Override
	protected void keyPressed(char character, int keyCode) {
		// Before the fields: a focused field would swallow escape and leave no way out.
		if (keyCode == Keyboard.KEY_ESCAPE) {
			client.setScreen(null);
			return;
		}

		boolean handled = false;

		for (int i = 0; i < fields.size(); i++) {
			if (fields.get(i).isVisible() && fields.get(i).isFocused()) {
				fields.get(i).keyPressed(character, keyCode);
				handled = true;
			}
		}

		if (handled) {
			commitFields();
			return;
		}

		super.keyPressed(character, keyCode);
	}

	@Override
	public void tick() {
		super.tick();

		for (int i = 0; i < fields.size(); i++) {
			fields.get(i).tick();
		}
	}
}
