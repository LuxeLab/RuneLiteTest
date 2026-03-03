package com.jr.thickskin;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Prayer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.MenuAction;
import net.runelite.api.widgets.WidgetInfo;

@Slf4j
@PluginDescriptor(
	name = "Thick Skin Activator",
	description = "Activates Thick Skin prayer via hotkey",
	tags = {"prayer", "thick skin"}
)
public class ThickSkinPlugin extends Plugin implements KeyListener
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ThickSkinConfig config;

	private boolean attemptedThisLogin;

	@Provides
	ThickSkinConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ThickSkinConfig.class);
	}

	@Override
	protected void startUp()
	{
		attemptedThisLogin = false;
		keyManager.registerKeyListener(this);
		log.info("Thick Skin Activator started");
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		log.info("Thick Skin Activator stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			attemptedThisLogin = false;
			return;
		}

		if (event.getGameState() == GameState.LOGGED_IN && config.autoOnLogin() && !attemptedThisLogin)
		{
			attemptedThisLogin = true;
			clientThread.invoke(this::activateThickSkin);
		}
	}

	private void activateThickSkin()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER) <= 0)
		{
			log.debug("No prayer points available");
			return;
		}

		if (client.isPrayerActive(Prayer.THICK_SKIN))
		{
			return;
		}

		// RuneLite API does not expose direct prayer-widget interaction helpers.
		// This plugin toggles Quick Prayer orb, so configure Quick Prayer preset to Thick Skin.
		client.menuAction(
			-1,
			WidgetInfo.MINIMAP_QUICK_PRAYER_ORB.getId(),
			MenuAction.CC_OP,
			1,
			0,
			"Activate",
			"Quick-prayers"
		);
	}

	@Override
	public void keyTyped(java.awt.event.KeyEvent e)
	{
	}

	@Override
	public void keyPressed(java.awt.event.KeyEvent e)
	{
		if (config.hotkey().matches(e))
		{
			clientThread.invoke(this::activateThickSkin);
		}
	}

	@Override
	public void keyReleased(java.awt.event.KeyEvent e)
	{
	}
}
