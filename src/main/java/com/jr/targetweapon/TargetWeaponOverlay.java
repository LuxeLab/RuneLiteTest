package com.jr.targetweapon;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class TargetWeaponOverlay extends OverlayPanel
{
	private final TargetWeaponPlugin plugin;

	@Inject
	private TargetWeaponOverlay(TargetWeaponPlugin plugin)
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
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Target Weapon Logs")
			.color(Color.CYAN)
			.build());

		List<String> lines = plugin.getRecentLogLines();
		if (lines.isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("No target data yet")
				.build());
		}
		else
		{
			for (String line : lines)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(line)
					.build());
			}
		}

		return super.render(graphics);
	}
}
