package net.mapmcbot.client;

import java.util.Arrays;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mapmcbot.bot.BotRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/**
 * Launches one action primitive on one bot (the fleet's actions.js runs it; the bot's state shows in
 * the bot window and the overlay). A primitive with a target is aimed by clicking it in the world,
 * and what it expects follows from what is clicked (see actions.js); the slots of move, waitslot and hotbar
 * are the open window's, or the bot's inventory's with none, as the bot window picks them.
 *
 * <pre>
 * #goto BOT         click the block to stand on
 * #interact BOT left|right   click the block or entity
 * #break BOT        click the block
 * #place BOT        click the block to put the one in hand against
 * #close BOT
  * #move BOT FROM TO                    the whole stack on slot FROM onto slot TO
 * #itemfill BOT ITEM SLOT...           every ITEM off the other slots onto the slots SLOT, as much as fits
 * #waitslot BOT WAIT...                every slot holding x MIN or more at once, each WAIT one of:
 *     SLOT,MIN                          any item
 *     SLOT,MIN,item,NAME                the item by name, whatever its kind (any potion)
 *     SLOT,MIN,exact,NAME,META[,NBT]    that very item, its metadata and NBT (hex) too (a potion's kind)
 * #hotbar BOT SLOT                     the hotbar slot SLOT into its hand
 * #trade BOT TRADE                     the trade TRADE of the open villager window (its arrows)
 * #lookat BOT                          turned to look where the player looks now
 * #drop BOT SLOT...                    the whole stack of each slot SLOT thrown where it looks
 * #stop BOT                            whatever it is doing stopped, then idle
 * #status BOT
 * </pre>
 *
 * A mistake in a command is told in chat and nothing is sent.
 */
final class BotCommands {
	private BotCommands() {
	}

	/** Whether the line was one of these commands. */
	static boolean handle(MinecraftClient client, String line) {
		final String[] words = line.trim().split("\\s+");
		final String command = words[0];

		if (!Arrays.asList("#goto", "#interact", "#break", "#place", "#close", "#move", "#itemfill", "#waitslot", "#hotbar", "#trade", "#lookat", "#drop", "#stop", "#status").contains(command)) {
			return false;
		}

		try {
			run(client, command, words);
		} catch (IllegalArgumentException e) {
			AreaPick.tell(client, command.substring(1) + "_err: " + e.getMessage());
		}

		return true;
	}

	private static void run(MinecraftClient client, String command, String[] w) {
		final BotRegistry bots = MapMcBotClient.bots();

		if (w.length < 2) {
			throw new IllegalArgumentException(command + " needs a bot");
		}

		final String bot = w[1];

		if (!bots.getSpawned().contains(bot)) {
			throw new IllegalArgumentException("no bot named " + bot + " in the world");
		}

		final JsonObject a = new JsonObject();

		switch (command) {
			case "#status":
				AreaPick.tell(client, bot + " " + bots.getStatus(bot));
				return;

			case "#goto":
				TargetPick.begin(client, "goto " + bot, false, hit -> {
					a.addProperty("action", "goto");
					final JsonObject spot = new JsonObject();
					position(spot, hit.getBlockPos().up());
					a.add("spot", spot);
					bots.action(bot, a.toString());
				});
				return;

			case "#interact": {
				if (w.length < 3 || !(w[2].equals("left") || w[2].equals("right"))) {
					throw new IllegalArgumentException("#interact BOT left|right");
				}

				final String button = w[2];
				TargetPick.begin(client, "interact " + bot, true, hit -> {
					a.addProperty("action", "interact");
					a.addProperty("button", button);
					final JsonObject target = new JsonObject();

					if (hit.type == BlockHitResult.Type.ENTITY) {
						target.addProperty("kind", "entity");
						// Its UUID: the id is the server's for the entity as loaded now, another once it loads again.
						target.addProperty("uuid", hit.entity.getUuid().toString());
					} else {
						target.addProperty("kind", "block");
						position(target, hit.getBlockPos());
					}

					a.add("target", target);
					bots.action(bot, a.toString());
				});
				return;
			}

			case "#break":
				TargetPick.begin(client, "break " + bot, false, hit -> {
					a.addProperty("action", "break");
					final JsonObject target = new JsonObject();
					position(target, hit.getBlockPos());
					a.add("target", target);
					bots.action(bot, a.toString());
				});
				return;

			case "#place":
				// The block clicked is the one it is put against: the target is the spot next to it, on the face clicked.
				TargetPick.begin(client, "place " + bot, false, true, hit -> {
					a.addProperty("action", "place");
					final JsonObject target = new JsonObject();
					position(target, hit.getBlockPos().offset(hit.direction));
					a.add("target", target);
					bots.action(bot, a.toString());
				});
				return;

			case "#stop":
				a.addProperty("action", "stop");
				bots.action(bot, a.toString());
				return;

			case "#close":
				a.addProperty("action", "close_window");
				bots.action(bot, a.toString());
				return;

			case "#move":
				if (w.length != 4) {
					throw new IllegalArgumentException("#move BOT FROM TO");
				}

				a.addProperty("action", "move");
				a.addProperty("from", number(w[2], "FROM"));
				a.addProperty("to", number(w[3], "TO"));
				bots.action(bot, a.toString());
				return;

			case "#itemfill":
				if (w.length < 4) {
					throw new IllegalArgumentException("#itemfill BOT ITEM SLOT...");
				}

				a.addProperty("action", "item_fill");
				final JsonObject item = new JsonObject();
				item.addProperty("name", w[2]);
				a.add("item", item);
				final JsonArray targets = new JsonArray();
				for (int i = 3; i < w.length; i++) {
					targets.add(number(w[i], "SLOT"));
				}
				a.add("targets", targets);
				bots.action(bot, a.toString());
				return;

			case "#hotbar":
				if (w.length != 3) {
					throw new IllegalArgumentException("#hotbar BOT SLOT");
				}

				a.addProperty("action", "hotbar");
				a.addProperty("slot", number(w[2], "SLOT"));
				bots.action(bot, a.toString());
				return;

			case "#lookat":
				a.addProperty("action", "look_at");
				a.addProperty("yaw", client.player.yaw);
				a.addProperty("pitch", client.player.pitch);
				bots.action(bot, a.toString());
				return;

			case "#drop": {
				if (w.length < 3) {
					throw new IllegalArgumentException("#drop BOT SLOT...");
				}

				a.addProperty("action", "drop");
				final JsonArray slots = new JsonArray();
				for (int i = 2; i < w.length; i++) {
					slots.add(number(w[i], "SLOT"));
				}
				a.add("slots", slots);
				bots.action(bot, a.toString());
				return;
			}

			case "#trade":
				if (w.length != 3) {
					throw new IllegalArgumentException("#trade BOT TRADE");
				}

				a.addProperty("action", "select_trade");
				a.addProperty("trade", number(w[2], "TRADE"));
				bots.action(bot, a.toString());
				return;

			case "#waitslot": {
				if (w.length < 3) {
					throw new IllegalArgumentException("#waitslot BOT SLOT,MIN[,item,NAME | ,exact,NAME,META[,NBT]]...");
				}

				final JsonArray slots = new JsonArray();

				for (int i = 2; i < w.length; i++) {
					final String[] parts = w[i].split(",");

					final String match = parts.length > 2 ? parts[2] : "any";
					final boolean valid = match.equals("any") ? parts.length == 2
							: match.equals("item") ? parts.length == 4
							: match.equals("exact") && (parts.length == 5 || parts.length == 6);

					if (!valid) {
						throw new IllegalArgumentException("expected SLOT,MIN[,item,NAME | ,exact,NAME,META[,NBT]], got " + w[i]);
					}

					final JsonObject wait = new JsonObject();
					wait.addProperty("slot", number(parts[0], "SLOT"));
					wait.addProperty("min", number(parts[1], "MIN"));

					// As the fleet's itemMatcher takes it (actions.js): by name, or exact, nothing of the NBT ignored.
					if (!match.equals("any")) {
						final JsonObject filter = new JsonObject();
						filter.addProperty("name", parts[3]);

						if (match.equals("exact")) {
							filter.addProperty("metadata", number(parts[4], "META"));
							filter.addProperty("nbt", parts.length == 6 ? parts[5] : "");
							filter.add("ignore", new JsonArray());
						}

						wait.add("item", filter);
					}

					slots.add(wait);
				}

				a.addProperty("action", "wait_slot");
				a.add("slots", slots);
				bots.action(bot, a.toString());
				return;
			}

			default:
				throw new IllegalStateException("command " + command + " has no handler");
		}
	}

	private static int number(String word, String what) {
		try {
			return Integer.parseInt(word);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(what + " must be a number, got " + word);
		}
	}

	private static void position(JsonObject out, BlockPos pos) {
		out.addProperty("x", pos.getX());
		out.addProperty("y", pos.getY());
		out.addProperty("z", pos.getZ());
	}
}
