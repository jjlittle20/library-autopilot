package com.librarypilot;

import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Snapshot of the collision flags for the library region (64x64 tiles, floors 0-2).
 */
class CollisionGrid
{
	static final int BASE_X = 1600;
	static final int BASE_Y = 3776;
	static final int SIZE = 64;
	static final int PLANES = 3;

	private static final int FULL = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	private static final int N = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
	private static final int S = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
	private static final int E = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
	private static final int W = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
	private static final int NE = CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
	private static final int NW = CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
	private static final int SE = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
	private static final int SW = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;

	/**
	 * [plane][x][y], region-local. Tiles outside the loaded scene are fully blocked.
	 */
	private final int[][][] flags;

	CollisionGrid(int[][][] flags)
	{
		this.flags = flags;
	}

	static CollisionGrid fromWorldView(WorldView wv)
	{
		CollisionData[] maps = wv.getCollisionMaps();
		int[][][] flags = new int[PLANES][SIZE][SIZE];
		for (int p = 0; p < PLANES; p++)
		{
			int[][] sceneFlags = maps == null || maps[p] == null ? null : maps[p].getFlags();
			for (int x = 0; x < SIZE; x++)
			{
				for (int y = 0; y < SIZE; y++)
				{
					int sx = BASE_X + x - wv.getBaseX();
					int sy = BASE_Y + y - wv.getBaseY();
					if (sceneFlags == null || sx < 0 || sy < 0 || sx >= sceneFlags.length || sy >= sceneFlags[sx].length)
					{
						flags[p][x][y] = FULL;
					}
					else
					{
						flags[p][x][y] = sceneFlags[sx][sy];
					}
				}
			}
		}
		return new CollisionGrid(flags);
	}

	static boolean inRegion(WorldPoint wp)
	{
		int x = wp.getX() - BASE_X;
		int y = wp.getY() - BASE_Y;
		return x >= 0 && y >= 0 && x < SIZE && y < SIZE && wp.getPlane() >= 0 && wp.getPlane() < PLANES;
	}

	static int node(WorldPoint wp)
	{
		return node(wp.getPlane(), wp.getX() - BASE_X, wp.getY() - BASE_Y);
	}

	static int node(int plane, int x, int y)
	{
		return (plane * SIZE + x) * SIZE + y;
	}

	static WorldPoint point(int node)
	{
		int y = node % SIZE;
		int x = (node / SIZE) % SIZE;
		int plane = node / (SIZE * SIZE);
		return new WorldPoint(BASE_X + x, BASE_Y + y, plane);
	}

	static int nodeCount()
	{
		return PLANES * SIZE * SIZE;
	}

	private boolean blocked(int p, int x, int y, int mask)
	{
		if (x < 0 || y < 0 || x >= SIZE || y >= SIZE)
		{
			return true;
		}
		return (flags[p][x][y] & mask) != 0;
	}

	boolean walkable(WorldPoint wp)
	{
		return inRegion(wp) && !blocked(wp.getPlane(), wp.getX() - BASE_X, wp.getY() - BASE_Y, FULL);
	}

	/**
	 * Whether a player can step from region-local (x, y) by (dx, dy), using the same rules as the game's pathfinder.
	 */
	boolean canMove(int p, int x, int y, int dx, int dy)
	{
		int tx = x + dx;
		int ty = y + dy;
		if (dx == 0 && dy == 1)
		{
			return !blocked(p, tx, ty, S | FULL);
		}
		if (dx == 0 && dy == -1)
		{
			return !blocked(p, tx, ty, N | FULL);
		}
		if (dx == 1 && dy == 0)
		{
			return !blocked(p, tx, ty, W | FULL);
		}
		if (dx == -1 && dy == 0)
		{
			return !blocked(p, tx, ty, E | FULL);
		}
		if (dx == 1 && dy == 1)
		{
			return !blocked(p, tx, ty, SW | S | W | FULL) && !blocked(p, tx, y, W | FULL) && !blocked(p, x, ty, S | FULL);
		}
		if (dx == -1 && dy == 1)
		{
			return !blocked(p, tx, ty, SE | S | E | FULL) && !blocked(p, tx, y, E | FULL) && !blocked(p, x, ty, S | FULL);
		}
		if (dx == 1 && dy == -1)
		{
			return !blocked(p, tx, ty, NW | N | W | FULL) && !blocked(p, tx, y, W | FULL) && !blocked(p, x, ty, N | FULL);
		}
		if (dx == -1 && dy == -1)
		{
			return !blocked(p, tx, ty, NE | N | E | FULL) && !blocked(p, tx, y, E | FULL) && !blocked(p, x, ty, N | FULL);
		}
		return false;
	}

	/**
	 * Whether there is a wall between a tile and its cardinal neighbour. Ignores whether either tile is occupied,
	 * which is what matters when interacting with a solid object such as a bookshelf or an NPC.
	 */
	boolean wallBetween(WorldPoint from, WorldPoint to)
	{
		int p = from.getPlane();
		int x = from.getX() - BASE_X;
		int y = from.getY() - BASE_Y;
		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		int tx = x + dx;
		int ty = y + dy;
		if (dx == 0 && dy == 1)
		{
			return blocked(p, x, y, N) || blocked(p, tx, ty, S);
		}
		if (dx == 0 && dy == -1)
		{
			return blocked(p, x, y, S) || blocked(p, tx, ty, N);
		}
		if (dx == 1 && dy == 0)
		{
			return blocked(p, x, y, E) || blocked(p, tx, ty, W);
		}
		if (dx == -1 && dy == 0)
		{
			return blocked(p, x, y, W) || blocked(p, tx, ty, E);
		}
		return true;
	}
}
