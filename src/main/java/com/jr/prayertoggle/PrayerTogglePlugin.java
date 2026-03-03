package com.jr.prayertoggle;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Prayer;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Prayer Name Toggle",
	description = "Toggle a typed prayer name with a hotkey",
	tags = {"prayer", "hotkey", "toggle"}
)
public class PrayerTogglePlugin extends Plugin implements KeyListener
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	@Inject
	private PrayerToggleConfig config;

	@Provides
	PrayerToggleConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PrayerToggleConfig.class);
	}

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(this);
		log.info("Prayer Name Toggle started");
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		log.info("Prayer Name Toggle stopped");
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (config.hotkey().matches(e))
		{
			clientThread.invoke(this::toggleConfiguredPrayer);
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	private void toggleConfiguredPrayer()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final String configuredPrayer = normalize(config.prayerName());
		if (configuredPrayer.isEmpty())
		{
			log.warn("Prayer name is empty in config");
			return;
		}

		final Prayer prayer = parsePrayer(configuredPrayer);
		if (prayer == null)
		{
			log.warn("Could not map prayer name '{}' to Prayer enum", config.prayerName());
			return;
		}

		final Widget prayerWidget = findPrayerWidget(configuredPrayer);
		if (prayerWidget == null)
		{
			log.warn("Prayer widget not found for '{}'. Open prayer tab at least once.", config.prayerName());
			return;
		}

		final boolean active = client.isPrayerActive(prayer);
		final String option = active ? "Deactivate" : "Activate";
		final String target = prayerWidget.getName() != null && !prayerWidget.getName().isBlank()
			? prayerWidget.getName()
			: config.prayerName();

		client.menuAction(
			-1,
			prayerWidget.getId(),
			MenuAction.CC_OP,
			1,
			0,
			option,
			target
		);

		log.info("Requested {} for prayer '{}' (widgetId={})", option, config.prayerName(), prayerWidget.getId());
	}

	private Widget findPrayerWidget(String configuredPrayer)
	{
		Widget root = client.getWidget(WidgetID.PRAYER_GROUP_ID, 0);
		if (root == null)
		{
			return null;
		}

		Deque<Widget> queue = new ArrayDeque<>();
		queue.add(root);

		while (!queue.isEmpty())
		{
			Widget w = queue.poll();
			if (w == null)
			{
				continue;
			}

			String name = normalize(w.getName());
			if (!name.isEmpty() && name.contains(configuredPrayer))
			{
				return w;
			}

			enqueue(queue, w.getChildren());
			enqueue(queue, w.getDynamicChildren());
			enqueue(queue, w.getStaticChildren());
			enqueue(queue, w.getNestedChildren());
		}

		return null;
	}

	private static void enqueue(Deque<Widget> queue, Widget[] widgets)
	{
		if (widgets == null)
		{
			return;
		}
		for (Widget w : widgets)
		{
			if (w != null)
			{
				queue.add(w);
			}
		}
	}

	private static Prayer parsePrayer(String normalizedPrayerName)
	{
		String candidate = normalizedPrayerName
			.toUpperCase(Locale.ROOT)
			.replace(' ', '_');

		for (Prayer prayer : Prayer.values())
		{
			if (prayer.name().equals(candidate))
			{
				return prayer;
			}
		}

		return null;
	}

	private static String normalize(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s
			.toLowerCase(Locale.ROOT)
			.replace("<col=ff9040>", "")
			.replace("</col>", "")
			.trim();
	}
}
