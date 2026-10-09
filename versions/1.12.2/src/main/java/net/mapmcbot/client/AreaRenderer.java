package net.mapmcbot.client;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.GlStateManager;
import net.mapmcbot.area.Area;
import net.mapmcbot.area.AreaStore;
import net.mapmcbot.bot.BotRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.opengl.GL11;

/**
 * Each area as a coloured box in the world, plus the block a click would pick while picking, and
 * the path each walking bot is on with its target, and the chests of each grab going on: the one
 * its bot is going to as a box, the ones it may look in after as an outline, in the bot's colour.
 *
 * Drawn at the end of entity rendering, where the camera transform is set with the camera at the
 * origin: boxes are shifted by the interpolated camera position, the raw one makes them jitter.
 */
public final class AreaRenderer {
	/** Beyond this a box is a couple of pixels and not worth drawing. */
	private static final double MAX_RENDER_DISTANCE_SQ = 512.0 * 512.0;

	/** Pulled in a hair so the outline does not z-fight with the faces of the blocks. */
	private static final double EXPANSION = -0.01;
	private static final int EDGE_ALPHA = 0xFF;
	private static final int FILL_ALPHA = 0x20;

	private AreaRenderer() {
	}

	public static void render(float tickDelta) {
		final MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null || client.player == null) {
			return;
		}

		final AreaStore store = MapMcBotClient.areas();
		final BlockPos target = AreaPick.isActive() ? AreaPick.target(client) : TargetPick.isActive() ? TargetPick.target(client) : null;
		final Box entityTarget = TargetPick.isActive() ? TargetPick.entityBox(client) : null;
		final BotRegistry bots = MapMcBotClient.botsOrNull();
		final Map<String, int[]> paths = bots == null ? Collections.<String, int[]>emptyMap() : bots.getPaths();
		final Grab grab = MapMcBotClient.grabOrNull();
		final List<Grab.Highlight> chests = grab == null ? Collections.<Grab.Highlight>emptyList() : grab.highlights();

		if ((store == null || store.getAreas().isEmpty()) && target == null && entityTarget == null && paths.isEmpty() && chests.isEmpty()) {
			return;
		}

		final Entity camera = client.getCameraEntity() == null ? client.player : client.getCameraEntity();
		final double camX = camera.prevTickX + (camera.x - camera.prevTickX) * tickDelta;
		final double camY = camera.prevTickY + (camera.y - camera.prevTickY) * tickDelta;
		final double camZ = camera.prevTickZ + (camera.z - camera.prevTickZ) * tickDelta;

		GlStateManager.pushMatrix();
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
				GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.disableTexture();
		GlStateManager.disableCull();

		// No depth writes so one box's fill does not hide another; the depth test stays on.
		GlStateManager.depthMask(false);
		GL11.glLineWidth(2.0F);

		if (store != null) {
			final List<Area> areas = store.getAreas();

			for (int i = 0; i < areas.size(); i++) {
				final Area area = areas.get(i);

				if (!isTooFar(area, camX, camY, camZ)) {
					drawBox(area.getMinX(), area.getMinY(), area.getMinZ(),
							area.getMaxX() + 1, area.getMaxY() + 1, area.getMaxZ() + 1,
							area.getColor(), camX, camY, camZ);
				}
			}
		}

		if (target != null) {
			drawBox(target.getX(), target.getY(), target.getZ(),
					target.getX() + 1, target.getY() + 1, target.getZ() + 1, 0xFFFFFF, camX, camY, camZ);
		}

		if (entityTarget != null) {
			drawBox(entityTarget.minX, entityTarget.minY, entityTarget.minZ, entityTarget.maxX, entityTarget.maxY, entityTarget.maxZ, 0xFFFFFF, camX, camY, camZ);
		}

		// TODO(world): a bot may be on another server or dimension than this client's (a relay's, or a local
		// fleet's), and its path is drawn here all the same. Needs the bot's world with its PATH, and only the paths of this world drawn.
		for (Map.Entry<String, int[]> entry : paths.entrySet()) {
			final int[] path = entry.getValue();
			final int rgb = MapMcBotClient.colorOf(entry.getKey()).getRgb();
			drawBox(path[0], path[1], path[2], path[0] + 1, path[1] + 1, path[2] + 1, rgb, camX, camY, camZ);
			drawPath(path, rgb, camX, camY, camZ);
		}

		for (Grab.Highlight chest : chests) {
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

			for (int[] block : chest.chest.getBlocks()) {
				minX = Math.min(minX, block[0]);
				minY = Math.min(minY, block[1]);
				minZ = Math.min(minZ, block[2]);
				maxX = Math.max(maxX, block[0]);
				maxY = Math.max(maxY, block[1]);
				maxZ = Math.max(maxZ, block[2]);
			}

			final int rgb = MapMcBotClient.colorOf(chest.bot).getRgb();

			if (chest.current) {
				drawBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1, rgb, camX, camY, camZ);
			} else {
				drawOutline(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1, rgb, camX, camY, camZ);
			}
		}

		// Line width is global state the rest of the frame would inherit.
		GL11.glLineWidth(1.0F);
		GlStateManager.depthMask(true);
		GlStateManager.enableCull();
		GlStateManager.enableTexture();
		GlStateManager.disableBlend();
		GlStateManager.popMatrix();
	}

	/** The nodes of a path {target x, y, z, node x, y, z, ...} as a line through the block centres, over everything. */
	private static void drawPath(int[] path, int rgb, double camX, double camY, double camZ) {
		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();
		final int r = (rgb >> 16) & 0xFF;
		final int g = (rgb >> 8) & 0xFF;
		final int b = rgb & 0xFF;

		GlStateManager.disableDepthTest();
		buffer.begin(GL11.GL_LINE_STRIP, VertexFormats.POSITION_COLOR);

		for (int i = 3; i < path.length; i += 3) {
			buffer.vertex(path[i] + 0.5 - camX, path[i + 1] + 0.1 - camY, path[i + 2] + 0.5 - camZ).color(r, g, b, EDGE_ALPHA).next();
		}

		tessellator.draw();
		GlStateManager.enableDepthTest();
	}

	private static boolean isTooFar(Area area, double camX, double camY, double camZ) {
		final double dx = (area.getMinX() + area.getMaxX() + 1) * 0.5 - camX;
		final double dy = (area.getMinY() + area.getMaxY() + 1) * 0.5 - camY;
		final double dz = (area.getMinZ() + area.getMaxZ() + 1) * 0.5 - camZ;
		return dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ;
	}

	private static void drawBox(double x0, double y0, double z0, double x1, double y1, double z1,
			int rgb, double camX, double camY, double camZ) {
		final double ax = x0 - EXPANSION - camX;
		final double ay = y0 - EXPANSION - camY;
		final double az = z0 - EXPANSION - camZ;
		final double bx = x1 + EXPANSION - camX;
		final double by = y1 + EXPANSION - camY;
		final double bz = z1 + EXPANSION - camZ;

		final int r = (rgb >> 16) & 0xFF;
		final int g = (rgb >> 8) & 0xFF;
		final int b = rgb & 0xFF;

		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();

		// The translucent fill respects depth so the box sits in the world...
		buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
		quad(buffer, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz, r, g, b, FILL_ALPHA);
		quad(buffer, ax, by, az, ax, by, bz, bx, by, bz, bx, by, az, r, g, b, FILL_ALPHA);
		quad(buffer, ax, ay, az, ax, by, az, bx, by, az, bx, ay, az, r, g, b, FILL_ALPHA);
		quad(buffer, ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz, r, g, b, FILL_ALPHA);
		quad(buffer, ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az, r, g, b, FILL_ALPHA);
		quad(buffer, bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz, r, g, b, FILL_ALPHA);
		tessellator.draw();

		// ...but the outline ignores it, so an area behind a hill can still be found.
		drawOutline(x0, y0, z0, x1, y1, z1, rgb, camX, camY, camZ);
	}

	/** The edges of the box alone, over everything. */
	private static void drawOutline(double x0, double y0, double z0, double x1, double y1, double z1,
			int rgb, double camX, double camY, double camZ) {
		final double ax = x0 - EXPANSION - camX;
		final double ay = y0 - EXPANSION - camY;
		final double az = z0 - EXPANSION - camZ;
		final double bx = x1 + EXPANSION - camX;
		final double by = y1 + EXPANSION - camY;
		final double bz = z1 + EXPANSION - camZ;
		final int r = (rgb >> 16) & 0xFF;
		final int g = (rgb >> 8) & 0xFF;
		final int b = rgb & 0xFF;
		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();
		GlStateManager.disableDepthTest();
		buffer.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);

		line(buffer, ax, ay, az, ax, by, az, r, g, b);
		line(buffer, bx, ay, az, bx, by, az, r, g, b);
		line(buffer, bx, ay, bz, bx, by, bz, r, g, b);
		line(buffer, ax, ay, bz, ax, by, bz, r, g, b);

		for (double y : new double[] {ay, by}) {
			line(buffer, ax, y, az, bx, y, az, r, g, b);
			line(buffer, bx, y, az, bx, y, bz, r, g, b);
			line(buffer, bx, y, bz, ax, y, bz, r, g, b);
			line(buffer, ax, y, bz, ax, y, az, r, g, b);
		}

		tessellator.draw();
		GlStateManager.enableDepthTest();
	}

	private static void quad(BufferBuilder buffer,
			double x1, double y1, double z1, double x2, double y2, double z2,
			double x3, double y3, double z3, double x4, double y4, double z4,
			int r, int g, int b, int a) {
		buffer.vertex(x1, y1, z1).color(r, g, b, a).next();
		buffer.vertex(x2, y2, z2).color(r, g, b, a).next();
		buffer.vertex(x3, y3, z3).color(r, g, b, a).next();
		buffer.vertex(x4, y4, z4).color(r, g, b, a).next();
	}

	private static void line(BufferBuilder buffer,
			double x1, double y1, double z1, double x2, double y2, double z2, int r, int g, int b) {
		buffer.vertex(x1, y1, z1).color(r, g, b, EDGE_ALPHA).next();
		buffer.vertex(x2, y2, z2).color(r, g, b, EDGE_ALPHA).next();
	}
}
