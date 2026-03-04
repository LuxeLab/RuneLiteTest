package com.jr.pvphelpr;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class PvpHelprOverlay extends OverlayPanel
{
	private final PvpHelprPlugin plugin;

	@Inject
	private PvpHelprOverlay(PvpHelprPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder().text("PvP Helpr").color(Color.CYAN).build());
		for (String line : plugin.getOverlayLines())
		{
			panelComponent.getChildren().add(LineComponent.builder().left(line).build());
		}
		return super.render(graphics);
	}
}
