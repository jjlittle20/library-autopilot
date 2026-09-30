package com.librarypilot;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;

/**
 * The library customers who hand out book requests. They wander within a few tiles of their home spot.
 * <p>
 * The server only sends NPCs that are near the player, so from the upper floors or the far side of the library the
 * customers usually aren't loaded. Their home spot is used as the destination until they are.
 */
@AllArgsConstructor
@Getter
enum Customer
{
	VILLIA(NpcID.ARCEUUS_LIBRARY_CUSTOMER_2, "Villia", new WorldPoint(1627, 3815, 0)),
	PROFESSOR_GRACKLEBONE(NpcID.ARCEUUS_LIBRARY_CUSTOMER_3, "Professor Gracklebone", new WorldPoint(1627, 3801, 0)),
	SAM(NpcID.ARCEUUS_LIBRARY_CUSTOMER_4, "Sam", new WorldPoint(1640, 3802, 0));

	private final int npcId;
	private final String displayName;
	private final WorldPoint home;

	static Customer byId(int npcId)
	{
		for (Customer c : values())
		{
			if (c.npcId == npcId)
			{
				return c;
			}
		}
		return null;
	}
}
