package com.librarypilot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
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
 * layout around it.
 */
class TransportScanner
{
	static final String CLIMB_UP = "Climb-up";
	static final String CLIMB_DOWN = "Climb-down";
	static final String CLIMB = "Climb";
	static final String JUMP_DOWN = "Jump-down";

	/**
	 * Every library staircase only has a plain "Climb" option, so which way each one goes comes from its object id.
	 * Each staircase is made of an object on the floor below and a matching object on the floor above.
	 */
	private static final Set<Integer> STAIRS_UP = Set.of(
		27853, 27854, // ground floor to middle floor
		27851 // middle floor to top floor
	);
	private static final Set<Integer> STAIRS_DOWN = Set.of(
		27855, 27856, // middle floor to ground floor
		27852 // top floor to middle floor
	);

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

	/**
	 * A usable object in the scene, before its landing has been worked out.
	 */
	@AllArgsConstructor
	static class Found
	{
		final TileObject object;
		final String option;
		final WorldPoint sw;
		final WorldPoint ne;
		final boolean isWall;
		/**
		 * +1 for up, -1 for down, 0 for a railing.
		 */
		final int direction;

		int plane()
		{
			return sw.getPlane();
		}

		double centreX()
		{
			return (sw.getX() + ne.getX()) / 2.0;
		}

		double centreY()
		{
			return (sw.getY() + ne.getY()) / 2.0;
		}

		/**
		 * Whether the two footprints overlap or touch, ignoring floors.
		 */
		boolean touches(Found o)
		{
			return o.sw.getX() <= ne.getX() + 1 && o.ne.getX() >= sw.getX() - 1
				&& o.sw.getY() <= ne.getY() + 1 && o.ne.getY() >= sw.getY() - 1;
		}
	}

	static List<Transport> scan(Client client, CollisionGrid grid, Map<String, WorldPoint> learned, boolean useShortcuts)
	{
		List<Found> found = new ArrayList<>();
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();

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
						find(client, found, go, sw, ne, false);
					}

					WallObject wall = tile.getWallObject();
					if (wall != null)
					{
						WorldPoint wp = wall.getWorldLocation();
						find(client, found, wall, wp, wp, true);
					}
				}
			}
		}

		int agility = client.getBoostedSkillLevel(Skill.AGILITY);
		List<Transport> transports = new ArrayList<>();
		for (Found f : found)
		{
			Transport t = f.direction == 0
				? railing(grid, learned, useShortcuts, agility, f)
				: stairs(grid, learned, found, f);
			if (t != null)
			{
				transports.add(t);
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

	private static void find(Client client, List<Found> out, TileObject object, WorldPoint sw, WorldPoint ne, boolean isWall)
	{
		ObjectComposition comp = composition(client, object.getId());
		if (comp == null || comp.getActions() == null)
		{
			return;
		}

		int plane = sw.getPlane();
		for (String action : Arrays.asList(comp.getActions()))
		{
			if (action == null)
			{
				continue;
			}
			switch (action)
			{
				case CLIMB_UP:
					out.add(new Found(object, action, sw, ne, isWall, 1));
					break;
				case CLIMB_DOWN:
					out.add(new Found(object, action, sw, ne, isWall, -1));
					break;
				case CLIMB:
					for (int direction : climbDirections(comp.getId(), plane))
					{
						out.add(new Found(object, action, sw, ne, isWall, direction));
					}
					break;
				case JUMP_DOWN:
					if (plane > 0)
					{
						out.add(new Found(object, action, sw, ne, isWall, 0));
					}
					break;
			}
		}
	}

	private static int[] climbDirections(int id, int plane)
	{
		if (STAIRS_UP.contains(id))
		{
			return new int[]{1};
		}
		if (STAIRS_DOWN.contains(id))
		{
			return new int[]{-1};
		}
		// Not a known library staircase: assume it goes wherever there is a floor to go to
		if (plane == 0)
		{
			return new int[]{1};
		}
		if (plane == CollisionGrid.PLANES - 1)
		{
			return new int[]{-1};
		}
		return new int[]{1, -1};
	}

	static Transport stairs(CollisionGrid grid, Map<String, WorldPoint> learned, List<Found> found, Found f)
	{
		int destPlane = f.plane() + f.direction;
		if (destPlane < 0 || destPlane >= CollisionGrid.PLANES)
		{
			return null;
		}

		List<WorldPoint> origins = origins(grid, f);
		WorldPoint landing = null;

		// The other half of the staircase: the object on the destination floor that goes the other way
		List<Found> pair = new ArrayList<>();
		for (Found o : found)
		{
			if (o.plane() == destPlane && o.direction == -f.direction && o.touches(f))
			{
				pair.add(o);
			}
		}

		if (!pair.isEmpty())
		{
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
			for (Found o : pair)
			{
				minX = Math.min(minX, o.sw.getX());
				minY = Math.min(minY, o.sw.getY());
				maxX = Math.max(maxX, o.ne.getX());
				maxY = Math.max(maxY, o.ne.getY());
			}

			// A staircase runs from the end of this object away from its other half, to the far end of the other half.
			// Board at the first, land past the second.
			double dx = f.centreX() - (minX + maxX) / 2.0;
			double dy = f.centreY() - (minY + maxY) / 2.0;
			List<WorldPoint> boarding = new ArrayList<>();
			List<WorldPoint> landings = new ArrayList<>();
			if (Math.abs(dx) >= Math.abs(dy))
			{
				int boardX = dx > 0 ? f.ne.getX() + 1 : f.sw.getX() - 1;
				int landX = dx > 0 ? minX - 1 : maxX + 1;
				for (WorldPoint o : origins)
				{
					if (o.getX() == boardX)
					{
						boarding.add(o);
					}
				}
				for (int y = minY; y <= maxY; y++)
				{
					landings.add(new WorldPoint(landX, y, destPlane));
				}
			}
			else
			{
				int boardY = dy > 0 ? f.ne.getY() + 1 : f.sw.getY() - 1;
				int landY = dy > 0 ? minY - 1 : maxY + 1;
				for (WorldPoint o : origins)
				{
					if (o.getY() == boardY)
					{
						boarding.add(o);
					}
				}
				for (int x = minX; x <= maxX; x++)
				{
					landings.add(new WorldPoint(x, landY, destPlane));
				}
			}

			landing = nearestWalkable(grid, landings, f.centreX(), f.centreY());
			if (!boarding.isEmpty() && landing != null)
			{
				origins = boarding;
			}
		}

		if (origins.isEmpty())
		{
			return null;
		}
		if (landing == null)
		{
			landing = estimateStairsLanding(grid, f, origins, destPlane);
		}

		WorldPoint dest = learned.get(Transport.key(f.sw, destPlane));
		boolean isLearned = dest != null;
		if (dest == null)
		{
			dest = landing;
		}
		return dest == null ? null : new Transport(f.sw, f.object, f.option, origins, dest, STAIRS_COST, isLearned);
	}

	private static Transport railing(CollisionGrid grid, Map<String, WorldPoint> learned, boolean useShortcuts, int agility, Found f)
	{
		int required = f.plane() >= 2 ? RAILING_TOP_FLOOR_LEVEL : RAILING_MIDDLE_FLOOR_LEVEL;
		if (!useShortcuts || agility < required)
		{
			return null;
		}
		List<WorldPoint> origins = origins(grid, f);
		if (origins.isEmpty())
		{
			return null;
		}
		WorldPoint dest = learned.get(Transport.key(f.sw, 0));
		boolean isLearned = dest != null;
		if (dest == null)
		{
			dest = estimateRailingLanding(grid, f.sw);
		}
		return dest == null ? null : new Transport(f.sw, f.object, f.option, origins, dest, RAILING_COST, isLearned);
	}

	/**
	 * Tiles from which the object can be used: walkable tiles next to it with no wall in between.
	 */
	private static List<WorldPoint> origins(CollisionGrid grid, Found f)
	{
		WorldPoint sw = f.sw;
		WorldPoint ne = f.ne;
		List<WorldPoint> origins = new ArrayList<>();
		for (int x = sw.getX(); x <= ne.getX(); x++)
		{
			for (int y = sw.getY(); y <= ne.getY(); y++)
			{
				WorldPoint t = new WorldPoint(x, y, sw.getPlane());
				if (f.isWall && grid.walkable(t) && !origins.contains(t))
				{
					origins.add(t);
				}
				for (int[] d : CARDINALS)
				{
					WorldPoint n = t.dx(d[0]).dy(d[1]);
					boolean inside = n.getX() >= sw.getX() && n.getX() <= ne.getX() && n.getY() >= sw.getY() && n.getY() <= ne.getY();
					if (inside || !grid.walkable(n) || origins.contains(n))
					{
						continue;
					}
					if (f.isWall || !grid.wallBetween(n, t))
					{
						origins.add(n);
					}
				}
			}
		}
		return origins;
	}

	private static WorldPoint nearestWalkable(CollisionGrid grid, List<WorldPoint> candidates, double x, double y)
	{
		WorldPoint best = null;
		double bestDist = Double.MAX_VALUE;
		for (WorldPoint c : candidates)
		{
			double d = (c.getX() - x) * (c.getX() - x) + (c.getY() - y) * (c.getY() - y);
			if (grid.walkable(c) && d < bestDist)
			{
				best = c;
				bestDist = d;
			}
		}
		return best;
	}

	/**
	 * Fallback for a staircase without a matching object on the other floor: the walkable tile around it on the
	 * destination floor that is furthest from where it is used.
	 */
	private static WorldPoint estimateStairsLanding(CollisionGrid grid, Found f, List<WorldPoint> origins, int destPlane)
	{
		double cx = origins.stream().mapToInt(WorldPoint::getX).average().orElse(f.sw.getX());
		double cy = origins.stream().mapToInt(WorldPoint::getY).average().orElse(f.sw.getY());

		WorldPoint best = null;
		double bestDist = -1;
		for (int x = f.sw.getX() - 1; x <= f.ne.getX() + 1; x++)
		{
			for (int y = f.sw.getY() - 1; y <= f.ne.getY() + 1; y++)
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
