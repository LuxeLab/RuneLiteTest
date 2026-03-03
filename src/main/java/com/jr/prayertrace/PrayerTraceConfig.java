package com.jr.prayertrace;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("prayertrace")
public interface PrayerTraceConfig extends Config
{
	@ConfigItem(
		keyName = "logAllMenuClicks",
		name = "Log all menu clicks",
		description = "If off, logs only prayer-related clicks"
	)
	default boolean logAllMenuClicks()
	{
		return false;
	}

	@ConfigItem(
		keyName = "logVarbitsEachTick",
		name = "Log prayer varbits each tick",
		description = "Verbose: log Thick Skin varbit/value every game tick"
	)
	default boolean logVarbitsEachTick()
	{
		return false;
	}
}
