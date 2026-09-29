package com.librarypilot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Shortest path across all three floors of the library, walking plus stairs and railing shortcuts.
 */
class PathFinder
{
	/**
	 * Cost of walking one tile. Scaled so transports and diagonal tie-breaking can be expressed in fractions of a tile.
	 */
	static final int TILE = 10;

	private static final int[][] DIRECTIONS = {
		{0, 1}, {0, -1}, {1, 0}, {-1, 0},
		{1, 1}, {-1, 1}, {1, -1}, {-1, -1},
	};

	@Value
	static class Step
	{
		WorldPoint point;
		/**
		 * The transport used to arrive at this step, or null if it was walked to.
		 */
		Transport via;
	}

	/**
	 * @return the steps from start (inclusive) to the nearest target, or an empty list if no target is reachable
	 */
	static List<Step> find(CollisionGrid grid, WorldPoint start, Collection<WorldPoint> targets, Collection<Transport> transports)
	{
		if (!CollisionGrid.inRegion(start) || targets.isEmpty())
		{
			return Collections.emptyList();
		}

		boolean[] isTarget = new boolean[CollisionGrid.nodeCount()];
		for (WorldPoint t : targets)
		{
			if (CollisionGrid.inRegion(t))
			{
				isTarget[CollisionGrid.node(t)] = true;
			}
		}

		Map<Integer, List<Transport>> transportsByOrigin = new HashMap<>();
		for (Transport t : transports)
		{
			if (!CollisionGrid.inRegion(t.getDestination()))
			{
				continue;
			}
			for (WorldPoint o : t.getOrigins())
			{
				if (CollisionGrid.inRegion(o))
				{
					transportsByOrigin.computeIfAbsent(CollisionGrid.node(o), k -> new ArrayList<>()).add(t);
				}
			}
		}

		int[] dist = new int[CollisionGrid.nodeCount()];
		int[] prev = new int[CollisionGrid.nodeCount()];
		Transport[] via = new Transport[CollisionGrid.nodeCount()];
		Arrays.fill(dist, Integer.MAX_VALUE);
		Arrays.fill(prev, -1);

		int startNode = CollisionGrid.node(start);
		dist[startNode] = 0;
		PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
		queue.add(new long[]{0, startNode});

		int found = -1;
		while (!queue.isEmpty())
		{
			long[] head = queue.poll();
			int node = (int) head[1];
			if (head[0] > dist[node])
			{
				continue;
			}
			if (isTarget[node])
			{
				found = node;
				break;
			}

			WorldPoint wp = CollisionGrid.point(node);
			int p = wp.getPlane();
			int x = wp.getX() - CollisionGrid.BASE_X;
			int y = wp.getY() - CollisionGrid.BASE_Y;
			for (int[] d : DIRECTIONS)
			{
				if (!grid.canMove(p, x, y, d[0], d[1]))
				{
					continue;
				}
				// Diagonals cost the same number of ticks in game; the +1 only prefers straight lines on ties
				int cost = TILE + (d[0] != 0 && d[1] != 0 ? 1 : 0);
				relax(queue, dist, prev, via, node, CollisionGrid.node(p, x + d[0], y + d[1]), cost, null);
			}

			List<Transport> ts = transportsByOrigin.get(node);
			if (ts != null)
			{
				for (Transport t : ts)
				{
					relax(queue, dist, prev, via, node, CollisionGrid.node(t.getDestination()), t.getCost(), t);
				}
			}
		}

		if (found == -1)
		{
			return Collections.emptyList();
		}

		List<Step> steps = new ArrayList<>();
		for (int n = found; n != -1; n = prev[n])
		{
			steps.add(new Step(CollisionGrid.point(n), via[n]));
		}
		Collections.reverse(steps);
		return steps;
	}

	private static void relax(PriorityQueue<long[]> queue, int[] dist, int[] prev, Transport[] via, int from, int to, int cost, Transport transport)
	{
		int nd = dist[from] + cost;
		if (nd < dist[to])
		{
			dist[to] = nd;
			prev[to] = from;
			via[to] = transport;
			queue.add(new long[]{nd, to});
		}
	}
}
