package com.librarypilot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

class LibraryPilotOverlay extends Overlay
{
	private final Client client;
	private final LibraryPilotPlugin plugin;
	private final LibraryPilotConfig config;

	@Inject
	LibraryPilotOverlay(Client client, LibraryPilotPlugin plugin, LibraryPilotConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		List<PathFinder.Step> steps = plugin.getPath();
		if (steps.isEmpty())
		{
			return null;
		}

		WorldView wv = client.getTopLevelWorldView();
		int plane = wv.getPlane();
		Color color = config.pathColor();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setStroke(new BasicStroke(config.lineWidth(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		// Draw the part of the path on this floor, up to the first staircase or railing
		Path2D.Double line = new Path2D.Double();
		boolean started = false;
		TileObject transportObject = null;
		for (PathFinder.Step step : steps)
		{
			if (step.getVia() != null)
			{
				transportObject = step.getVia().getObject();
				break;
			}
			if (step.getPoint().getPlane() != plane)
			{
				break;
			}
			Point p = toCanvas(wv, step.getPoint());
			if (p == null)
			{
				continue;
			}
			if (started)
			{
				line.lineTo(p.getX(), p.getY());
			}
			else
			{
				line.moveTo(p.getX(), p.getY());
				started = true;
			}
		}

		Shape highlight = null;
		Point end = null;
		if (transportObject != null)
		{
			if (transportObject.getPlane() == plane)
			{
				highlight = transportObject.getClickbox();
				end = toCanvas(wv, transportObject.getLocalLocation());
			}
		}
		else
		{
			NPC npc = plugin.getTargetNpc();
			WorldPoint bookcase = plugin.getTargetBookcase();
			if (npc != null && npc.getWorldLocation().getPlane() == plane)
			{
				highlight = npc.getConvexHull();
				end = toCanvas(wv, npc.getLocalLocation());
			}
			else if (bookcase != null && bookcase.getPlane() == plane)
			{
				LocalPoint lp = LocalPoint.fromWorld(wv, bookcase);
				if (lp != null)
				{
					highlight = Perspective.getCanvasTilePoly(client, lp);
					end = toCanvas(wv, lp);
				}
			}
		}

		if (started && end != null)
		{
			line.lineTo(end.getX(), end.getY());
		}

		if (started)
		{
			g.setColor(color);
			g.draw(line);
		}

		if (highlight != null && config.highlightDestination())
		{
			g.setColor(color);
			g.draw(highlight);
			g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(color.getAlpha(), 50)));
			g.fill(highlight);
		}
		return null;
	}

	private Point toCanvas(WorldView wv, WorldPoint wp)
	{
		LocalPoint lp = LocalPoint.fromWorld(wv, wp);
		return lp == null ? null : toCanvas(wv, lp);
	}

	private Point toCanvas(WorldView wv, LocalPoint lp)
	{
		return Perspective.localToCanvas(client, lp, wv.getPlane());
	}
}
