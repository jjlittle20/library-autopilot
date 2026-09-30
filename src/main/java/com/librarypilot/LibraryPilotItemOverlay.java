package com.librarypilot;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Outlines the Books of Arcane Knowledge in the inventory when it is full, so they can be read to make space.
 */
class LibraryPilotItemOverlay extends WidgetItemOverlay
{
	private final LibraryPilotPlugin plugin;
	private final LibraryPilotConfig config;
	private final ItemManager itemManager;

	@Inject
	LibraryPilotItemOverlay(LibraryPilotPlugin plugin, LibraryPilotConfig config, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		showOnInventory();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (itemId != ItemID.ARCEUUS_LIBRARY_REWARD || !plugin.isClaimXp())
		{
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		BufferedImage outline = itemManager.getItemOutline(itemId, widgetItem.getQuantity(), config.pathColor());
		graphics.drawImage(outline, (int) bounds.getX(), (int) bounds.getY(), null);
	}
}
