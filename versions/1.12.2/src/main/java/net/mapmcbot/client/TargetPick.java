package net.mapmcbot.client;

import java.util.function.Consumer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;

/**
 * The single-target pick of an action primitive: the next click (either button) on a block, or on an
 * entity when the primitive takes one, is the target. The block the client points at is highlighted
 * while it runs (see AreaRenderer), as when picking an area. Like AreaPick it lives outside any
 * screen, the world being what is clicked.
 */
public final class TargetPick {
	private static Consumer<BlockHitResult> callback;
	private static boolean entities;
	/** The spot next to the block pointed at, on its face, is the one highlighted (a block put there). */
	private static boolean onFace;

	private TargetPick() {
	}

	public static boolean isActive() {
		return callback != null;
	}

	/** Starts a pick: `then` gets the hit of the next click. */
	public static void begin(MinecraftClient client, String what, boolean allowEntities, Consumer<BlockHitResult> then) {
		begin(client, what, allowEntities, false, then);
	}

	/** As begin, the spot on the face pointed at highlighted when onFace. */
	public static void begin(MinecraftClient client, String what, boolean allowEntities, boolean onFace, Consumer<BlockHitResult> then) {
		callback = then;
		entities = allowEntities;
		TargetPick.onFace = onFace;
		AreaPick.tell(client, what + ": click the " + (allowEntities ? "block or entity" : "block") + " (the areas key cancels)");
	}

	public static void cancel(MinecraftClient client) {
		if (callback != null) {
			reset();
			AreaPick.tell(client, "Target pick cancelled");
		}
	}

	static void reset() {
		callback = null;
	}

	/** A click while picking; true when it was used, so the caller cancels the swing or the use. */
	public static boolean click(MinecraftClient client) {
		if (callback == null) {
			return false;
		}

		final BlockHitResult hit = client.result;
		final boolean block = hit != null && hit.type == BlockHitResult.Type.BLOCK;
		final boolean entity = entities && hit != null && hit.type == BlockHitResult.Type.ENTITY && hit.entity != null;

		if (!block && !entity) {
			AreaPick.tell(client, "Point at a block" + (entities ? " or entity" : ""));
			return true;
		}

		final Consumer<BlockHitResult> then = callback;
		reset();
		then.accept(hit);
		return true;
	}

	/**
	 * The hitbox of the entity a click would pick, a hair larger so it is not drawn into its faces, or
	 * null when pointing at none or the pick takes no entity.
	 */
	public static Box entityBox(MinecraftClient client) {
		final BlockHitResult hit = client.result;

		if (callback == null || !entities || hit == null || hit.type != BlockHitResult.Type.ENTITY || hit.entity == null) {
			return null;
		}

		return hit.entity.getBoundingBox().expand(0.002);
	}

	/** The block a click would pick (the spot on its face for a place), or null when looking at none. */
	public static BlockPos target(MinecraftClient client) {
		final BlockPos pointed = AreaPick.target(client);
		return pointed != null && onFace ? pointed.offset(client.result.direction) : pointed;
	}
}
