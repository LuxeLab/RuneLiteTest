package com.jr.autotyper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("autotyper")
public interface AutoTyperConfig extends Config
{
	@ConfigItem(
		keyName = "message",
		name = "Message",
		description = "Message to send"
	)
	default String message()
	{
		return "Selling lobsters";
	}

	@Range(min = 3, max = 600)
	@ConfigItem(
		keyName = "intervalSeconds",
		name = "Interval (seconds)",
		description = "How often to send the message"
	)
	default int intervalSeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "enabled",
		name = "Enabled",
		description = "Enable/disable auto typer"
	)
	default boolean enabled()
	{
		return false;
	}
}
