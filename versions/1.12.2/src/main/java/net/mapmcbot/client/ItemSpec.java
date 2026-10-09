package net.mapmcbot.client;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.mapmcbot.bot.BotInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;

/**
 * An item match as the fleet's itemMatcher takes it (actions.js, written by ItemFilter#toJson), tested
 * here: by name, metadata, and the NBT tags listed in match (the reference's at each path) or all of
 * it but those in ignore.
 */
final class ItemSpec {
	private ItemSpec() {
	}

	static boolean matches(JsonObject spec, BotInventory.Item item) {
		if (item == null) {
			return false;
		}

		if (spec.has("name") && !bare(spec.get("name").getAsString()).equals(bare(item.getName()))) {
			return false;
		}

		if (spec.has("metadata") && spec.get("metadata").getAsInt() != item.getMetadata()) {
			return false;
		}

		if (!spec.has("match") && !spec.has("ignore")) {
			return true;
		}

		final NbtCompound reference = read(hex(spec.get("nbt").getAsString()), "the match's reference");
		final NbtCompound own = read(item.getNbt(), item.getName());

		if (spec.has("match")) {
			for (JsonElement path : spec.getAsJsonArray("match")) {
				final NbtElement wanted = tagAt(reference, path.getAsString());
				final NbtElement found = tagAt(own, path.getAsString());

				if (wanted == null ? found != null : !wanted.equals(found)) {
					return false;
				}
			}

			return true;
		}

		final JsonArray ignore = spec.getAsJsonArray("ignore");
		return without(reference, ignore).equals(without(own, ignore));
	}

	/** What the spec matches, for messages: its name, or any item. */
	static String describe(JsonObject spec) {
		return spec.has("name") ? bare(spec.get("name").getAsString()) : "any item";
	}

	private static String bare(String name) {
		return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
	}

	private static NbtCompound read(byte[] nbt, String what) {
		if (nbt.length == 0) {
			return new NbtCompound();
		}

		try {
			return NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbt)));
		} catch (IOException e) {
			throw new UncheckedIOException("bad NBT on " + what, e);
		}
	}

	private static byte[] hex(String text) {
		final byte[] bytes = new byte[text.length() / 2];

		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
		}

		return bytes;
	}

	/** The tag at the path (keys joined by dots), null when it is not there. */
	private static NbtElement tagAt(NbtCompound root, String path) {
		NbtElement at = root;

		for (String key : path.split("\\.")) {
			if (!(at instanceof NbtCompound) || !((NbtCompound) at).contains(key)) {
				return null;
			}

			at = ((NbtCompound) at).get(key);
		}

		return at;
	}

	/** A copy without the tags at the paths. */
	private static NbtCompound without(NbtCompound root, JsonArray paths) {
		final NbtCompound copy = root.copy();

		for (JsonElement path : paths) {
			final String[] keys = path.getAsString().split("\\.");
			NbtElement at = copy;

			for (int i = 0; i < keys.length - 1 && at instanceof NbtCompound; i++) {
				at = ((NbtCompound) at).get(keys[i]);
			}

			if (at instanceof NbtCompound) {
				((NbtCompound) at).remove(keys[keys.length - 1]);
			}
		}

		return copy;
	}
}
