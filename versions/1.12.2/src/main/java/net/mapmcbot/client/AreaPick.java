package net.mapmcbot.client;

import net.mapmcbot.area.Area;
import net.mapmcbot.area.AreaStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.LiteralText;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/**
 * The two-corner pick that runs in the world after "Add area": each corner is the block clicked
 * (either button) or the player's own feet on sneak. Lives outside the screen because the screen
 * is closed the whole time.
 */
public final class AreaPick {
	/** Colours offered to new areas, cycled by the editor's swatch. */
	public static final int[] PALETTE = {
			0xFF4040, 0xFF9840, 0xFFE040, 0x9CFF40, 0x40FF60, 0x40FFC0,
			0x40D0FF, 0x4070FF, 0xA040FF, 0xFF40D0, 0xFFFFFF, 0x909090,
	};

	private static boolean active;
	private static int color;
	private static int[] first;

	/** Edge detection, so holding sneak places one corner rather than a stream of them. */
	private static boolean wasSneaking;

	private AreaPick() {
	}

	public static boolean isActive() {
		return active;
	}

	/** Starts a pick; the caller closes the screen first. */
	public static void begin(MinecraftClient client, int areaColor) {
		active = true;
		color = areaColor;
		first = null;
		tell(client, "Defining area - point 1: sneak to use your position, or click a block");
		tell(client, "Press the areas key again to cancel");
	}

	public static void cancel(MinecraftClient client) {
		if (active) {
			reset();
			tell(client, "Area definition cancelled");
		}
	}

	static void reset() {
		active = false;
		first = null;
	}

	static void tick(MinecraftClient client) {
		final boolean sneaking = client.player.isSneaking();
		final boolean justSneaked = sneaking && !wasSneaking;
		wasSneaking = sneaking;

		if (active && justSneaked && client.currentScreen == null) {
			accept(client, (int) Math.floor(client.player.x), (int) Math.floor(client.player.y),
					(int) Math.floor(client.player.z));
		}
	}

	/**
	 * A click while picking: takes the targeted block as the next corner. Returns whether the click
	 * was used, so the caller cancels the swing or the use.
	 */
	public static boolean click(MinecraftClient client) {
		if (!active) {
			return false;
		}

		final BlockPos target = target(client);

		if (target == null) {
			// Clicking thin air must say something, or it looks broken.
			tell(client, "Point at a block, or sneak to use your own position");
		} else {
			accept(client, target.getX(), target.getY(), target.getZ());
		}

		return true;
	}

	private static void accept(MinecraftClient client, int x, int y, int z) {
		final AreaStore store = MapMcBotClient.areas();

		if (store == null) {
			return;
		}

		if (first == null) {
			first = new int[] {x, y, z};
			tell(client, "Point 1 set at " + x + ", " + y + ", " + z + " - point 2: sneak or click a block");
			return;
		}

		final Area area = new Area("Area " + (store.getAreas().size() + 1), color,
				first[0], first[1], first[2], x, y, z);
		store.add(area);
		reset();

		tell(client, "Area '" + area.getName() + "' created: " + area.getWidth() + "x" + area.getHeight()
				+ "x" + area.getDepth() + " (" + area.getVolume() + " blocks)");
	}

	/** The block a click would pick, or null when looking at nothing. */
	public static BlockPos target(MinecraftClient client) {
		final BlockHitResult hit = client.result;
		return hit == null || hit.type != BlockHitResult.Type.BLOCK ? null : hit.getBlockPos();
	}

	public static void tell(MinecraftClient client, String message) {
		if (client.inGameHud != null) {
			client.inGameHud.getChatHud().addMessage(new LiteralText("[MapMcBot] " + message));
		}
	}
}
