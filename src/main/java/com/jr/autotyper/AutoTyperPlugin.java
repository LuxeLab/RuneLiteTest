package com.jr.autotyper;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Auto Typer",
	description = "Types and sends a chat message on an interval",
	tags = {"chat", "typing", "message"}
)
public class AutoTyperPlugin extends Plugin
{
	private static final int TICKS_PER_SECOND = 2;

	@Inject
	private Client client;

	@Inject
	private AutoTyperConfig config;

	private int ticks;

	@Provides
	AutoTyperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AutoTyperConfig.class);
	}

	@Override
	protected void startUp()
	{
		ticks = 0;
		log.info("Auto Typer started");
	}

	@Override
	protected void shutDown()
	{
		ticks = 0;
		log.info("Auto Typer stopped");
	}

	@Subscribe
	public void onGameTick(GameTick ignored)
	{
		if (!config.enabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			ticks = 0;
			return;
		}

		ticks++;
		if (ticks < config.intervalSeconds() * TICKS_PER_SECOND)
		{
			return;
		}
		ticks = 0;
		sendMessage(config.message());
	}

	private void sendMessage(String message)
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null || message == null || message.isBlank())
		{
			return;
		}

		press(canvas, KeyEvent.VK_ENTER);
		sleep(80);

		for (char c : message.toCharArray())
		{
			KeyEvent typed = new KeyEvent(canvas, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, c);
			canvas.dispatchEvent(typed);
			sleep(15);
		}

		sleep(60);
		press(canvas, KeyEvent.VK_ENTER);
	}

	private void press(Canvas canvas, int keyCode)
	{
		canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED));
		canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED));
	}

	private void sleep(long millis)
	{
		try
		{
			Thread.sleep(millis);
		}
		catch (InterruptedException ignored)
		{
			Thread.currentThread().interrupt();
		}
	}
}
