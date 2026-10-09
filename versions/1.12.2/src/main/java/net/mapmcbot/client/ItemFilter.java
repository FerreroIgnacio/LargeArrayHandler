package net.mapmcbot.client;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mapmcbot.bot.BotInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtString;

/**
 * Which items an order means, told from a reference item picked off a slot: each of its fields (its
 * name, its metadata, each tag of its NBT) counts or not, and with exact on its NBT has to be the
 * reference's but at the tags that do not count. As the fleet's itemMatcher (actions.js) takes it.
 */
final class ItemFilter {
	/** A tag of the reference's NBT: where it is (keys joined by dots), what to call it, its value as shown. */
	static final class Field {
		final String path;
		final String label;
		final String value;

		Field(String path, String label, String value) {
			this.path = path;
			this.label = label;
			this.value = value;
		}
	}

	/** What the panel calls the tags it knows; the rest by their path. */
	private static final Map<String, String> LABELS = new HashMap<String, String>();

	static {
		LABELS.put("Potion", "Potion");
		LABELS.put("CustomPotionEffects", "Effects");
		LABELS.put("display.Name", "Name");
		LABELS.put("display.Lore", "Lore");
		LABELS.put("display.color", "Colour");
		LABELS.put("ench", "Enchants");
		LABELS.put("StoredEnchantments", "Stored ench");
		LABELS.put("RepairCost", "Repair cost");
		LABELS.put("Unbreakable", "Unbreakable");
	}

	final BotInventory.Item reference;
	final List<Field> fields;
	boolean name = true;
	boolean metadata = true;
	/** The whole NBT as the reference's, the tags off aside; off (as it starts): only the tags on. */
	boolean exact;
	/** The tags that count, by path. */
	final Set<String> on = new HashSet<String>();

	/** The reference as it is: every field counting, other tags than its let be. */
	ItemFilter(BotInventory.Item reference) {
		this.reference = reference;
		final List<Field> fields = new ArrayList<Field>();

		if (reference.getNbt().length > 0) {
			try {
				flatten(NbtIo.read(new DataInputStream(new ByteArrayInputStream(reference.getNbt()))), "", fields);
			} catch (IOException e) {
				throw new UncheckedIOException("bad NBT on " + reference.getName() + ", picked as an item to match", e);
			}
		}

		this.fields = Collections.unmodifiableList(fields);

		for (Field field : fields) {
			on.add(field.path);
		}
	}

	/** Each tag that is no compound, a compound's by each of its own; a list as a whole. */
	private static void flatten(NbtCompound compound, String prefix, List<Field> out) {
		final List<String> keys = new ArrayList<String>(compound.getKeys());
		Collections.sort(keys);

		for (String key : keys) {
			if (key.contains(".")) {
				throw new IllegalStateException("NBT key \"" + key + "\" has a dot, which joins the keys of a path");
			}

			final String path = prefix + key;
			final NbtElement tag = compound.get(key);

			if (tag instanceof NbtCompound) {
				flatten((NbtCompound) tag, path + ".", out);
			} else {
				out.add(new Field(path, LABELS.getOrDefault(path, path), tag instanceof NbtString ? compound.getString(key) : tag.toString()));
			}
		}
	}

	/** Whether it matches any item: nothing counts. */
	boolean any() {
		return !name && !metadata && !exact && on.isEmpty();
	}

	/** What the match button says: exact, same (every field, other tags let be), item (by name only), any, or custom. */
	String label() {
		if (any()) {
			return "any";
		}

		if (name && metadata && on.size() == fields.size()) {
			return exact ? "exact" : "same";
		}

		if (name && !metadata && !exact && on.isEmpty()) {
			return "item";
		}

		return "custom";
	}

	/** As the fleet's itemMatcher takes it (actions.js). */
	JsonObject toJson() {
		final JsonObject spec = new JsonObject();

		if (name) {
			spec.addProperty("name", reference.getName());
		}

		if (metadata) {
			spec.addProperty("metadata", reference.getMetadata());
		}

		if (!exact && on.isEmpty()) {
			return spec;
		}

		final StringBuilder hex = new StringBuilder();

		for (byte b : reference.getNbt()) {
			hex.append(String.format("%02x", b & 0xFF));
		}

		spec.addProperty("nbt", hex.toString());
		final JsonArray paths = new JsonArray();

		for (Field field : fields) {
			// Exact: the tags off are the ones ignored; else the ones on, the ones matched.
			if (on.contains(field.path) != exact) {
				paths.add(field.path);
			}
		}

		spec.add(exact ? "ignore" : "match", paths);
		return spec;
	}
}
