package com.librarypilot;

import java.util.List;
import lombok.Value;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

/**
 * A staircase or railing shortcut that moves the player between floors of the library.
 */
@Value
class Transport
{
	/**
	 * South-west tile of the object, used as its identity.
	 */
	WorldPoint objectLocation;
	TileObject object;
	String option;
	/**
	 * Tiles the player can stand on to use the object.
	 */
	List<WorldPoint> origins;
	WorldPoint destination;
	int cost;
	/**
	 * True when the destination was observed in-game rather than estimated from collision data.
	 */
	boolean learned;

	String key()
	{
		return key(objectLocation, destination.getPlane());
	}

	static String key(WorldPoint objectLocation, int destPlane)
	{
		return objectLocation.getX() + "," + objectLocation.getY() + "," + objectLocation.getPlane() + ">" + destPlane;
	}
}
