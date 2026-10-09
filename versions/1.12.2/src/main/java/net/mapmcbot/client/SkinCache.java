package net.mapmcbot.client;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * The skins of the players the bot profiles name, loaded once each through the game's own skin
 * provider (Mojang's name lookup, then the session server's textures). Ask with of(): it answers null
 * until the skin has arrived, and the lookup starts on the first ask. A failed lookup crashes the game
 * on its thread, with the skin's name.
 */
public final class SkinCache {
	public static final class Skin {
		private final Identifier texture;
		private final boolean slim;

		Skin(Identifier texture, boolean slim) {
			this.texture = texture;
			this.slim = slim;
		}

		public Identifier getTexture() {
			return texture;
		}

		/** The thin-arm model (Alex), else the classic one. */
		public boolean isSlim() {
			return slim;
		}
	}

	private static final Map<String, Skin> LOADED = new ConcurrentHashMap<String, Skin>();
	private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

	private SkinCache() {
	}

	/** The skin of the player named, null while it is on its way. */
	public static Skin of(String skinName) {
		if (REQUESTED.add(skinName)) {
			final Thread lookup = new Thread(() -> lookup(skinName), "mapmcbot-skin-" + skinName);
			lookup.setDaemon(true);
			lookup.start();
		}

		return LOADED.get(skinName);
	}

	private static void lookup(String skinName) {
		final MinecraftClient client = MinecraftClient.getInstance();

		try {
			final GameProfile named = new GameProfile(uuidOf(skinName), skinName);
			final GameProfile filled = client.getSessionService().fillProfileProperties(named, true);

			client.submit(() -> client.getSkinProvider().loadProfileSkin(filled, (type, texture, data) -> {
				if (type == MinecraftProfileTexture.Type.SKIN) {
					LOADED.put(skinName, new Skin(texture, "slim".equals(data.getMetadata("model"))));
				}
			}, false));
		} catch (IOException | RuntimeException e) {
			final IllegalStateException failure = new IllegalStateException("could not load the skin of " + skinName, e);
			client.submit(() -> {
				throw failure;
			});
		}
	}

	private static UUID uuidOf(String skinName) throws IOException {
		final HttpURLConnection connection = (HttpURLConnection) new URL("https://api.mojang.com/users/profiles/minecraft/" + skinName).openConnection();

		if (connection.getResponseCode() != 200) {
			throw new IOException("Mojang knows no player " + skinName + " (HTTP " + connection.getResponseCode() + ")");
		}

		try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
			final JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
			final String id = json.get("id").getAsString();
			return UUID.fromString(id.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"));
		}
	}
}
