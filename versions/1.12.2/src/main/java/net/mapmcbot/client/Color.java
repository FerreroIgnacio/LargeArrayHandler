package net.mapmcbot.client;

import net.minecraft.util.Formatting;

/** A bot's colour: the team formatting its glow takes, and the same colour as RGB for its path and goal. */
public enum Color {
	RED(Formatting.RED, 0xFF5555),
	GREEN(Formatting.GREEN, 0x55FF55),
	AQUA(Formatting.AQUA, 0x55FFFF),
	YELLOW(Formatting.YELLOW, 0xFFFF55),
	LIGHT_PURPLE(Formatting.LIGHT_PURPLE, 0xFF55FF),
	GOLD(Formatting.GOLD, 0xFFAA00),
	BLUE(Formatting.BLUE, 0x5555FF),
	WHITE(Formatting.WHITE, 0xFFFFFF);

	private final Formatting formatting;
	private final int rgb;

	Color(Formatting formatting, int rgb) {
		this.formatting = formatting;
		this.rgb = rgb;
	}

	public Formatting getFormatting() {
		return formatting;
	}

	public int getRgb() {
		return rgb;
	}
}
