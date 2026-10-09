package net.mapmcbot.client;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.OtherClientPlayerEntity;
import net.minecraft.util.Identifier;

/** A player that is only drawn (never added to the world), wearing the skin of a bot profile. */
final class PreviewPlayer extends OtherClientPlayerEntity {
	private final String skinName;

	PreviewPlayer(String name, String skinName) {
		super(MinecraftClient.getInstance().world, new GameProfile(UUID.nameUUIDFromBytes(("preview:" + name).getBytes(StandardCharsets.UTF_8)), name));
		this.skinName = skinName;
	}

	@Override
	public Identifier getSkinId() {
		final SkinCache.Skin skin = SkinCache.of(skinName);
		return skin == null ? super.getSkinId() : skin.getTexture();
	}

	@Override
	public String getModel() {
		final SkinCache.Skin skin = SkinCache.of(skinName);
		return skin == null ? super.getModel() : skin.isSlim() ? "slim" : "default";
	}
}
