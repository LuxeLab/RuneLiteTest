package com.jr.wikiassistant;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Wiki Assistant MVP",
	description = "Ask OSRS questions grounded in OSRS Wiki + RuneLite context",
	tags = {"wiki", "assistant", "bank", "skills"}
)
public class WikiAssistantPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private WikiAssistantPanel panel;

	private NavigationButton navButton;

	@Provides
	WikiAssistantConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WikiAssistantConfig.class);
	}

	@Override
	protected void startUp()
	{
		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "wiki_icon.png");
		}
		catch (Exception ignored)
		{
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		}
		navButton = NavigationButton.builder()
			.tooltip("Wiki Assistant")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		log.info("Wiki Assistant MVP started");
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		log.info("Wiki Assistant MVP stopped");
	}
}
