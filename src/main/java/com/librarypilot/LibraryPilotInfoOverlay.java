package com.librarypilot;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

class LibraryPilotInfoOverlay extends OverlayPanel
{
	private static final int WIDTH = 190;

	private final LibraryPilotPlugin plugin;
	private final LibraryPilotConfig config;

	@Inject
	LibraryPilotInfoOverlay(LibraryPilotPlugin plugin, LibraryPilotConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		LibraryPilotPlugin.Task task = plugin.getTask();
		if (!config.showInfoBox() || task == null)
		{
			return null;
		}

		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Task")
			.right(task.getLabel())
			.rightColor(config.pathColor())
			.build());

		Book book = plugin.getTaskBook();
		if (book != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Book")
				.right(book.getShortName())
				.build());
		}

		Customer customer = plugin.getTaskCustomer();
		if (customer != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(task == LibraryPilotPlugin.Task.RETURN_BOOK ? "To" : "From")
				.right(customer.getDisplayName())
				.build());
		}

		Book detour = plugin.getDetourBook();
		if (detour != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(detour == plugin.getRegrabBook() ? "Re-grabbing" : "Grabbing")
				.right(detour.getShortName())
				.rightColor(Color.YELLOW)
				.build());
		}

		return super.render(graphics);
	}
}
