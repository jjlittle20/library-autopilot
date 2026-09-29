package com.librarypilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * Uses the real object positions of the library's south-west and top-floor staircases, taken from the game cache.
 */
public class TransportScannerTest
{
	private static int[][][] grid;

	private static TransportScanner.Found found(int x0, int y0, int x1, int y1, int plane, int direction)
	{
		for (int x = x0; x <= x1; x++)
		{
			for (int y = y0; y <= y1; y++)
			{
				grid[plane][x - CollisionGrid.BASE_X][y - CollisionGrid.BASE_Y] = CollisionDataFlag.BLOCK_MOVEMENT_OBJECT;
			}
		}
		return new TransportScanner.Found(null, "Climb", new WorldPoint(x0, y0, plane), new WorldPoint(x1, y1, plane), false, direction);
	}

	@Test
	public void groundFloorStairsBoardEastLandWest()
	{
		grid = new int[CollisionGrid.PLANES][CollisionGrid.SIZE][CollisionGrid.SIZE];
		TransportScanner.Found up1 = found(1614, 3794, 1616, 3795, 0, 1);
		TransportScanner.Found up2 = found(1614, 3796, 1616, 3797, 0, 1);
		TransportScanner.Found down1 = found(1611, 3794, 1614, 3795, 1, -1);
		TransportScanner.Found down2 = found(1611, 3796, 1614, 3797, 1, -1);
		List<TransportScanner.Found> all = Arrays.asList(up1, up2, down1, down2);
		CollisionGrid g = new CollisionGrid(grid);

		Transport up = TransportScanner.stairs(g, Collections.emptyMap(), all, up1);
		assertEquals(1, up.getDestination().getPlane());
		assertEquals(1610, up.getDestination().getX());
		assertTrue(up.getOrigins().stream().allMatch(o -> o.getX() == 1617));

		Transport down = TransportScanner.stairs(g, Collections.emptyMap(), all, down1);
		assertEquals(0, down.getDestination().getPlane());
		assertEquals(1617, down.getDestination().getX());
		assertTrue(down.getOrigins().stream().allMatch(o -> o.getX() == 1610));
	}

	@Test
	public void middleFloorStairsBoardSouthLandNorth()
	{
		grid = new int[CollisionGrid.PLANES][CollisionGrid.SIZE][CollisionGrid.SIZE];
		TransportScanner.Found up = found(1621, 3792, 1622, 3794, 1, 1);
		TransportScanner.Found down = found(1621, 3794, 1622, 3797, 2, -1);
		List<TransportScanner.Found> all = Arrays.asList(up, down);
		CollisionGrid g = new CollisionGrid(grid);

		Transport t = TransportScanner.stairs(g, Collections.emptyMap(), all, up);
		assertEquals(new WorldPoint(1621, 3798, 2).getY(), t.getDestination().getY());
		assertTrue(t.getOrigins().stream().allMatch(o -> o.getY() == 3791));

		Transport back = TransportScanner.stairs(g, Collections.emptyMap(), all, down);
		assertEquals(3791, back.getDestination().getY());
		assertEquals(1, back.getDestination().getPlane());
		assertTrue(back.getOrigins().stream().allMatch(o -> o.getY() == 3798));
	}

	@Test
	public void learnedLandingWins()
	{
		grid = new int[CollisionGrid.PLANES][CollisionGrid.SIZE][CollisionGrid.SIZE];
		TransportScanner.Found up = found(1621, 3792, 1622, 3794, 1, 1);
		TransportScanner.Found down = found(1621, 3794, 1622, 3797, 2, -1);
		WorldPoint real = new WorldPoint(1622, 3799, 2);
		Transport t = TransportScanner.stairs(new CollisionGrid(grid),
			Collections.singletonMap(Transport.key(up.sw, 2), real), Arrays.asList(up, down), up);
		assertEquals(real, t.getDestination());
		assertTrue(t.isLearned());
	}
}
