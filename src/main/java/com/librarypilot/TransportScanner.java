package com.librarypilot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Finds the staircases and railing shortcuts in the loaded scene and works out where each one leads.
 * <p>
 * Where a transport lands is decided by the server, so it can't be read from the scene. Until the player has used a
 * transport once (see {@link LibraryPilotPlugin} for how landings are learned), its landing is estimated from the
 * collision data of the floor it leads to.
 */
class TransportScanner
{
	static final String CLIMB_UP = "Climb-up";
	static final String CLIMB_DOWN = "Climb-down";
	static final String CLIMB = "Climb";
	static final String JUMP_DOWN = "Jump-down";

	/**
	 * Railings around the hole in the middle of the library, added in the 12 August 2026 update.
	 */
	private static final WorldPoint HOLE_CENTRE = new WorldPoint(1633, 3808, 0);
	private static final int RAILING_MIDDLE_FLOOR_LEVEL = 52;
	private static final int RAILING_TOP_FLOOR_LEVEL = 62;

	// Rough time taken by each transport, in tiles of running (2 tiles per tick)
	private static final int STAIRS_COST = 4 * PathFinder.TILE;
	private static final int RAILING_COST = 5 * PathFinder.TILE;

	private static final int[][] CARDINALS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

	static List<Transport> scan(Client client, CollisionGrid grid, Map<String, WorldPoint> learned, boolean useShortcuts)
	{
		List<Transport> transports = new ArrayList<>();
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		int agility = client.getBoostedSkillLevel(Skill.AGILITY);

		for (int p = 0; p < CollisionGrid.PLANES; p++)
		{
			for (int x = 0; x < CollisionGrid.SIZE; x++)
			{
				for (int y = 0; y < CollisionGrid.SIZE; y++)
				{
					int sx = CollisionGrid.BASE_X + x - wv.getBaseX();
					int sy = CollisionGrid.BASE_Y + y - wv.getBaseY();
					if (sx < 0 || sy < 0 || sx >= tiles[p].length || sy >= tiles[p][sx].length)
					{
						continue;
					}
					Tile tile = tiles[p][sx][sy];
					if (tile == null)
					{
						continue;
					}

					for (GameObject go : tile.getGameObjects())
					{
						if (go == null)
						{
							continue;
						}
						Point min = go.getSceneMinLocation();
						Point max = go.getSceneMaxLocation();
						// Large objects are present on every tile they cover; only handle them once
						if (min.getX() != sx || min.getY() != sy)
						{
							continue;
						}
						WorldPoint sw = WorldPoint.fromScene(wv, min.getX(), min.getY(), p);
						WorldPoint ne = WorldPoint.fromScene(wv, max.getX(), max.getY(), p);
						add(client, grid, learned, useShortcuts, agility, transports, go, sw, ne, false);
					}

					WallObject wall = tile.getWallObject();
					if (wall != null)
					{
						WorldPoint wp = wall.getWorldLocation();
						add(client, grid, learned, useShortcuts, agility, transports, wall, wp, wp, true);
					}
				}
			}
		}
		return transports;
	}

	static ObjectComposition composition(Client client, int id)
	{
		ObjectComposition comp = client.getObjectDefinition(id);
		if (comp != null && comp.getImpostorIds() != null)
		{
			ObjectComposition impostor = comp.getImpostor();
			if (impostor != null)
			{
				comp = impostor;
			}
		}
		return comp;
	}

	private static void add(Client client, CollisionGrid grid, Map<String, WorldPoint> learned, boolean useShortcuts, int agility,
		List<Transport> out, TileObject object, WorldPoint sw, WorldPoint ne, boolean isWall)
	{
		ObjectComposition comp = composition(client, object.getId());
		if (comp == null || comp.getActions() == null)
		{
			return;
		}

		List<String> actions = Arrays.asList(comp.getActions());
		int plane = sw.getPlane();
		List<WorldPoint> origins = null;

		for (String action : actions)
		{
			if (action == null)
			{
				continue;
			}

			int[] destPlanes;
			int cost;
			switch (action)
			{
				case CLIMB_UP:
					destPlanes = new int[]{plane + 1};
					cost = STAIRS_COST;
					break;
				case CLIMB_DOWN:
					destPlanes = new int[]{plane - 1};
					cost = STAIRS_COST;
					break;
				case CLIMB:
					destPlanes = new int[]{plane + 1, plane - 1};
					cost = STAIRS_COST;
					break;
				case JUMP_DOWN:
					int required = plane >= 2 ? RAILING_TOP_FLOOR_LEVEL : RAILING_MIDDLE_FLOOR_LEVEL;
					if (!useShortcuts || plane == 0 || agility < required)
					{
						continue;
					}
					destPlanes = new int[]{0};
					cost = RAILING_COST;
					break;
				default:
					continue;
			}

			if (origins == null)
			{
				origins = origins(grid, sw, ne, isWall);
				if (origins.isEmpty())
				{
					return;
				}
			}

			for (int destPlane : destPlanes)
			{
				if (destPlane < 0 || destPlane >= CollisionGrid.PLANES)
				{
					continue;
				}

				WorldPoint dest = learned.get(Transport.key(sw, destPlane));
				boolean isLearned = dest != null;
				if (dest == null)
				{
					dest = JUMP_DOWN.equals(action)
						? estimateRailingLanding(grid, sw)
						: estimateStairsLanding(grid, sw, ne, origins, destPlane);
				}
				if (dest != null)
				{
					out.add(new Transport(sw, object, action, origins, dest, cost, isLearned));
				}
			}
		}
	}

	/**
	 * Tiles from which the object can be used: walkable tiles next to it with no wall in between.
	 */
	private static List<WorldPoint> origins(CollisionGrid grid, WorldPoint sw, WorldPoint ne, boolean isWall)
	{
		List<WorldPoint> origins = new ArrayList<>();
		for (int x = sw.getX(); x <= ne.getX(); x++)
		{
			for (int y = sw.getY(); y <= ne.getY(); y++)
			{
				WorldPoint f = new WorldPoint(x, y, sw.getPlane());
				if (isWall && grid.walkable(f) && !origins.contains(f))
				{
					origins.add(f);
				}
				for (int[] d : CARDINALS)
				{
					WorldPoint n = f.dx(d[0]).dy(d[1]);
					boolean inside = n.getX() >= sw.getX() && n.getX() <= ne.getX() && n.getY() >= sw.getY() && n.getY() <= ne.getY();
					if (inside || !grid.walkable(n) || origins.contains(n))
					{
						continue;
					}
					if (isWall || !grid.wallBetween(n, f))
					{
						origins.add(n);
					}
				}
			}
		}
		return origins;
	}

	/**
	 * You come off the top (or bottom) of a staircase at the end furthest from where you got on, so pick the walkable
	 * tile around the staircase on the destination floor that is furthest from the tiles it is used from.
	 */
	private static WorldPoint estimateStairsLanding(CollisionGrid grid, WorldPoint sw, WorldPoint ne, List<WorldPoint> origins, int destPlane)
	{
		double cx = origins.stream().mapToInt(WorldPoint::getX).average().orElse(sw.getX());
		double cy = origins.stream().mapToInt(WorldPoint::getY).average().orElse(sw.getY());

		WorldPoint best = null;
		double bestDist = -1;
		for (int x = sw.getX() - 1; x <= ne.getX() + 1; x++)
		{
			for (int y = sw.getY() - 1; y <= ne.getY() + 1; y++)
			{
				WorldPoint c = new WorldPoint(x, y, destPlane);
				if (!grid.walkable(c))
				{
					continue;
				}
				double d = (x - cx) * (x - cx) + (y - cy) * (y - cy);
				if (d > bestDist)
				{
					bestDist = d;
					best = c;
				}
			}
		}
		return best;
	}

	/**
	 * Jumping over a railing drops you onto the ground floor just inside the hole.
	 */
	private static WorldPoint estimateRailingLanding(CollisionGrid grid, WorldPoint railing)
	{
		WorldPoint below = new WorldPoint(
			railing.getX() + Integer.signum(HOLE_CENTRE.getX() - railing.getX()),
			railing.getY() + Integer.signum(HOLE_CENTRE.getY() - railing.getY()),
			0);
		for (int r = 0; r <= 4; r++)
		{
			WorldPoint best = null;
			int bestDist = Integer.MAX_VALUE;
			for (int dx = -r; dx <= r; dx++)
			{
				for (int dy = -r; dy <= r; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
					{
						continue;
					}
					WorldPoint c = below.dx(dx).dy(dy);
					int d = c.distanceTo2D(HOLE_CENTRE);
					if (grid.walkable(c) && d < bestDist)
					{
						best = c;
						bestDist = d;
					}
				}
			}
			if (best != null)
			{
				return best;
			}
		}
		return null;
	}
}
