package com.librarypilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class PathFinderTest
{
	private static final int BX = CollisionGrid.BASE_X;
	private static final int BY = CollisionGrid.BASE_Y;

	private static WorldPoint wp(int x, int y, int plane)
	{
		return new WorldPoint(BX + x, BY + y, plane);
	}

	private static int[][][] open()
	{
		return new int[CollisionGrid.PLANES][CollisionGrid.SIZE][CollisionGrid.SIZE];
	}

	@Test
	public void walksStraightLine()
	{
		CollisionGrid grid = new CollisionGrid(open());
		List<PathFinder.Step> steps = PathFinder.find(grid, wp(10, 10, 0), Collections.singletonList(wp(15, 10, 0)), Collections.emptyList());
		assertEquals(6, steps.size());
		for (PathFinder.Step s : steps)
		{
			assertEquals(BY + 10, s.getPoint().getY());
		}
	}

	@Test
	public void goesAroundWall()
	{
		int[][][] flags = open();
		// A wall along the east side of x=12 for y 5..15, open at both ends
		for (int y = 5; y <= 15; y++)
		{
			flags[0][12][y] |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
			flags[0][13][y] |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		CollisionGrid grid = new CollisionGrid(flags);
		List<PathFinder.Step> steps = PathFinder.find(grid, wp(10, 10, 0), Collections.singletonList(wp(15, 10, 0)), Collections.emptyList());
		assertTrue(steps.size() > 6);
		assertTrue(steps.stream().anyMatch(s -> s.getPoint().getY() >= BY + 16 || s.getPoint().getY() <= BY + 4));
	}

	@Test
	public void prefersFasterTransport()
	{
		CollisionGrid grid = new CollisionGrid(open());
		Transport stairs = new Transport(wp(40, 10, 1), null, "Climb-down", Collections.singletonList(wp(40, 10, 1)), wp(40, 11, 0), 40, false);
		Transport railing = new Transport(wp(12, 10, 1), null, "Jump-down", Collections.singletonList(wp(12, 10, 1)), wp(13, 10, 0), 50, false);
		List<PathFinder.Step> steps = PathFinder.find(grid, wp(10, 10, 1), Collections.singletonList(wp(10, 10, 0)),
			Arrays.asList(stairs, railing));
		assertSame(railing, steps.stream().filter(s -> s.getVia() != null).findFirst().get().getVia());
		assertEquals(wp(10, 10, 0), steps.get(steps.size() - 1).getPoint());
	}

	@Test
	public void unreachableGivesEmptyPath()
	{
		CollisionGrid grid = new CollisionGrid(open());
		List<PathFinder.Step> steps = PathFinder.find(grid, wp(10, 10, 2), Collections.singletonList(wp(10, 10, 0)), Collections.emptyList());
		assertTrue(steps.isEmpty());
	}
}
