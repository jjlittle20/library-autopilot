package com.librarypilot;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(LibraryPilotConfig.GROUP)
public interface LibraryPilotConfig extends Config
{
	String GROUP = "librarypilot";
	String LEARNED_TRANSPORTS = "learnedTransports";

	@Alpha
	@ConfigItem(
		keyName = "pathColor",
		name = "Path colour",
		description = "Colour of the line to the next destination",
		position = 0
	)
	default Color pathColor()
	{
		return new Color(0, 255, 255, 220);
	}

	@Range(min = 1, max = 8)
	@ConfigItem(
		keyName = "lineWidth",
		name = "Line width",
		description = "Width of the path line",
		position = 1
	)
	default int lineWidth()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "highlightDestination",
		name = "Highlight destination",
		description = "Outline the bookshelf, customer, staircase or railing the line leads to",
		position = 2
	)
	default boolean highlightDestination()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useShortcuts",
		name = "Use railing shortcuts",
		description = "Route over the railings around the central hole (52/62 Agility) when that is faster than the stairs",
		position = 3
	)
	default boolean useShortcuts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "searchUnknown",
		name = "Path to unsearched shelves",
		description = "When the requested book's location isn't known yet, lead to the nearest shelf that could have it",
		position = 4
	)
	default boolean searchUnknown()
	{
		return true;
	}
}
